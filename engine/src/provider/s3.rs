//! S3-compatible provider (AWS, MinIO, Ceph, ...) with a hand-written SigV4 signer on reqwest + rustls.
use super::tls::{connect_with_pinning, map_reqwest, Slot};
use super::{Progress, Provider, Reader, Trust, CHUNK};
use crate::crypto::{hmac_sha256, sha256_hex};
use crate::dates::{amz_datetime, now_secs, parse_rfc3339};
use crate::error::{Code, NdError, Result};
use crate::types::{EntryKind, Profile, RawEntry, S3Cfg, Secrets};
use async_trait::async_trait;
use futures::{StreamExt, TryStreamExt};
use percent_encoding::{utf8_percent_encode, AsciiSet, NON_ALPHANUMERIC};
use quick_xml::events::Event;
use reqwest::{Client, Method, RequestBuilder, Response, StatusCode};
use std::sync::Arc;
use tokio::io::AsyncReadExt;
use tokio_util::io::{ReaderStream, StreamReader};

const UNRESERVED: &AsciiSet = &NON_ALPHANUMERIC.remove(b'-').remove(b'.').remove(b'_').remove(b'~');
const SINGLE_PUT_MAX: u64 = 64 * 1024 * 1024;
const PART: usize = 16 * 1024 * 1024;
const MAX_XML: usize = 32 * 1024 * 1024;

fn enc(s: &str) -> String {
    utf8_percent_encode(s, UNRESERVED).to_string()
}
fn enc_key(k: &str) -> String {
    k.split('/').map(enc).collect::<Vec<_>>().join("/")
}

pub struct S3Provider {
    client: Client,
    slot: Slot,
    scheme: String,
    host: String, // host[:port] used in Host header / URL authority (bucket prefix included for virtual style)
    path_prefix: String, // "" or "/bucket"
    bucket: String,
    region: String,
    ak: String,
    sk: String,
}

pub fn signing_key(sk: &str, date: &str, region: &str) -> [u8; 32] {
    let k = hmac_sha256(format!("AWS4{sk}").as_bytes(), date.as_bytes());
    let k = hmac_sha256(&k, region.as_bytes());
    let k = hmac_sha256(&k, b"s3");
    hmac_sha256(&k, b"aws4_request")
}

/// Build the SigV4 `Authorization` value. `headers` must contain lowercase names, including host/x-amz-*.
pub fn sigv4_authorization(
    method: &str,
    canonical_uri: &str,
    query: &[(String, String)],
    headers: &[(String, String)],
    payload_hash: &str,
    ak: &str,
    sk: &str,
    region: &str,
    datetime: &str,
    date: &str,
) -> String {
    let mut q: Vec<(String, String)> = query.iter().map(|(k, v)| (enc(k), enc(v))).collect();
    q.sort();
    let cq = q.iter().map(|(k, v)| format!("{k}={v}")).collect::<Vec<_>>().join("&");
    let mut hs: Vec<(String, String)> = headers.iter().map(|(k, v)| (k.to_ascii_lowercase(), v.trim().to_string())).collect();
    hs.sort();
    let ch: String = hs.iter().map(|(k, v)| format!("{k}:{v}\n")).collect();
    let sh = hs.iter().map(|(k, _)| k.as_str()).collect::<Vec<_>>().join(";");
    let creq = format!("{method}\n{canonical_uri}\n{cq}\n{ch}\n{sh}\n{payload_hash}");
    let scope = format!("{date}/{region}/s3/aws4_request");
    let sts = format!("AWS4-HMAC-SHA256\n{datetime}\n{scope}\n{}", sha256_hex(creq.as_bytes()));
    let sig = hmac_sha256(&signing_key(sk, date, region), sts.as_bytes());
    let sig_hex: String = sig.iter().map(|b| format!("{b:02x}")).collect();
    format!("AWS4-HMAC-SHA256 Credential={ak}/{scope}, SignedHeaders={sh}, Signature={sig_hex}")
}

fn key_of(path: &str) -> String {
    path.trim_start_matches('/').to_string()
}

fn s3_err(status: StatusCode, body: &str, path: &str) -> NdError {
    let code_str = body.split("<Code>").nth(1).and_then(|s| s.split("</Code>").next()).unwrap_or("");
    let code = match code_str {
        "NoSuchKey" | "NoSuchBucket" | "NotFound" | "NoSuchUpload" => Code::NotFound,
        "AccessDenied" | "AllAccessDisabled" => Code::PermissionDenied,
        "InvalidAccessKeyId" | "SignatureDoesNotMatch" | "ExpiredToken" | "InvalidToken" => Code::AuthFailed,
        "PreconditionFailed" | "ConditionalRequestConflict" | "BucketAlreadyOwnedByYou" => Code::AlreadyExists,
        "EntityTooLarge" | "QuotaExceeded" => Code::DiskFull,
        "RequestTimeout" | "SlowDown" | "ServiceUnavailable" | "InternalError" => Code::Network,
        _ => match status.as_u16() {
            401 => Code::AuthFailed,
            403 => Code::PermissionDenied,
            404 => Code::NotFound,
            409 | 412 => Code::AlreadyExists,
            500..=599 => Code::Network,
            _ => Code::ServerAnomaly,
        },
    };
    NdError::new(code).with("path", path).with("http_status", status.as_u16()).with("s3_code", code_str)
}

impl S3Provider {
    fn new_parts(p: &Profile, c: &S3Cfg) -> (String, String, String, String) {
        let scheme = if p.tls { "https" } else { "http" }.to_string();
        let port = if p.port == 0 { String::new() } else { format!(":{}", p.port) };
        let region = if c.region.is_empty() { "us-east-1".to_string() } else { c.region.clone() };
        let base_host = if p.host.is_empty() { format!("s3.{region}.amazonaws.com") } else { p.host.clone() };
        let (host, prefix) = if c.path_style || (!p.host.is_empty() && c.bucket.contains('.')) {
            (format!("{base_host}{port}"), format!("/{}", c.bucket))
        } else if p.host.is_empty() {
            (format!("{}.{base_host}{port}", c.bucket), String::new())
        } else {
            // custom endpoint, virtual-hosted style requested explicitly (path_style=false)
            (format!("{}.{base_host}{port}", c.bucket), String::new())
        };
        (scheme, host, prefix, region)
    }

    pub async fn connect(p: &Profile, secrets: &Secrets, trust: Arc<dyn Trust>) -> Result<Arc<S3Provider>> {
        let c = p.s3.clone().ok_or_else(|| NdError::invalid("s3 config missing"))?;
        if c.bucket.is_empty() || c.access_key.is_empty() {
            return Err(NdError::invalid("bucket/access_key"));
        }
        let sk = match secrets.secret_key.clone() {
            Some(x) => x,
            None => trust.ask_secret("password").await?.ok_or_else(|| NdError::new(Code::AuthFailed).detail("cancelled"))?,
        };
        let (scheme, host, path_prefix, region) = Self::new_parts(p, &c);
        let port = if p.port == 0 { if p.tls { 443 } else { 80 } } else { p.port };
        let tls_host = if p.host.is_empty() { host.split(':').next().unwrap().to_string() } else { p.host.clone() };
        let tmpl = (scheme.clone(), host.clone(), path_prefix.clone(), c.bucket.clone(), region.clone(), c.access_key.clone(), sk.clone());
        let (client, slot) = connect_with_pinning(&trust, &tls_host, port, move |cl, slot| {
            let t = tmpl.clone();
            async move {
                let probe = S3Provider { client: cl, slot, scheme: t.0, host: t.1, path_prefix: t.2, bucket: t.3, region: t.4, ak: t.5, sk: t.6 };
                let q = vec![("list-type".to_string(), "2".to_string()), ("max-keys".to_string(), "1".to_string())];
                probe.send_q("GET", "", &q, vec![], "/").await.map(|_| ())
            }
        })
        .await?;
        Ok(Arc::new(S3Provider { client, slot, scheme, host, path_prefix, bucket: c.bucket, region, ak: c.access_key, sk }))
    }

    fn request(&self, method: &str, key: &str, query: &[(String, String)], extra: Vec<(String, String)>, payload: &str) -> RequestBuilder {
        let uri = format!("{}/{}", self.path_prefix, enc_key(key));
        let uri = if uri.is_empty() { "/".to_string() } else { uri };
        let (dt, date) = amz_datetime(now_secs());
        let mut hdrs: Vec<(String, String)> = vec![
            ("host".into(), self.host.clone()),
            ("x-amz-content-sha256".into(), payload.to_string()),
            ("x-amz-date".into(), dt.clone()),
        ];
        hdrs.extend(extra.iter().filter(|(k, _)| k.starts_with("x-amz-")).cloned());
        let auth = sigv4_authorization(method, &uri, query, &hdrs, payload, &self.ak, &self.sk, &self.region, &dt, &date);
        let mut qs: Vec<(String, String)> = query.iter().map(|(k, v)| (enc(k), enc(v))).collect();
        qs.sort();
        let qstr = if qs.is_empty() { String::new() } else { format!("?{}", qs.iter().map(|(k, v)| format!("{k}={v}")).collect::<Vec<_>>().join("&")) };
        let url = format!("{}://{}{}{}", self.scheme, self.host, uri, qstr);
        let mut rb = self
            .client
            .request(Method::from_bytes(method.as_bytes()).unwrap(), url)
            .header("x-amz-content-sha256", payload)
            .header("x-amz-date", dt)
            .header("Authorization", auth);
        for (k, v) in extra {
            rb = rb.header(k, v);
        }
        rb
    }

    async fn send_q(&self, method: &str, key: &str, query: &[(String, String)], extra: Vec<(String, String)>, path: &str) -> Result<Response> {
        let empty = sha256_hex(b"");
        let r = self.request(method, key, query, extra, &empty).send().await.map_err(|e| map_reqwest(e, &self.slot))?;
        self.check(r, path).await
    }

    async fn check(&self, r: Response, path: &str) -> Result<Response> {
        if r.status().is_success() {
            return Ok(r);
        }
        let st = r.status();
        let body = r.text().await.unwrap_or_default();
        Err(s3_err(st, &body, path))
    }

    async fn body_limited(&self, r: Response, path: &str) -> Result<Vec<u8>> {
        let mut buf = Vec::new();
        let mut st = r.bytes_stream();
        while let Some(c) = st.next().await {
            buf.extend_from_slice(&c.map_err(|e| map_reqwest(e, &self.slot))?);
            if buf.len() > MAX_XML {
                return Err(NdError::new(Code::LimitExceeded).with("path", path));
            }
        }
        Ok(buf)
    }

    /// Lists ALL keys under a prefix (no delimiter) - used for directory rename.
    async fn list_all_keys(&self, prefix: &str) -> Result<Vec<String>> {
        let mut out = vec![];
        let mut token: Option<String> = None;
        loop {
            let mut q = vec![("list-type".into(), "2".into()), ("prefix".into(), prefix.to_string())];
            if let Some(t) = &token {
                q.push(("continuation-token".into(), t.clone()));
            }
            let r = self.send_q("GET", "", &q, vec![], prefix).await?;
            let body = self.body_limited(r, prefix).await?;
            let page = parse_list(&body)?;
            out.extend(page.contents.into_iter().map(|c| c.0));
            if out.len() > 250_000 {
                return Err(NdError::new(Code::LimitExceeded));
            }
            if page.truncated && page.next.is_some() {
                token = page.next;
            } else {
                break;
            }
        }
        Ok(out)
    }

    async fn copy_key(&self, from: &str, to: &str) -> Result<()> {
        let src = format!("/{}/{}", self.bucket, enc_key(from));
        self.send_q("PUT", to, &[], vec![("x-amz-copy-source".into(), src)], to).await.map(|_| ())
    }
}

pub struct ListPage {
    pub contents: Vec<(String, u64, Option<i64>)>,
    pub prefixes: Vec<String>,
    pub truncated: bool,
    pub next: Option<String>,
}

pub fn parse_list(xml: &[u8]) -> Result<ListPage> {
    let mut rd = quick_xml::Reader::from_reader(xml);
    rd.config_mut().trim_text(true);
    let mut page = ListPage { contents: vec![], prefixes: vec![], truncated: false, next: None };
    let mut stack: Vec<String> = vec![];
    let (mut key, mut size, mut lm) = (String::new(), 0u64, None::<i64>);
    loop {
        match rd.read_event() {
            Err(e) => return Err(NdError::new(Code::ServerAnomaly).detail(e)),
            Ok(Event::Eof) => break,
            Ok(Event::DocType(_)) => return Err(NdError::new(Code::ServerAnomaly).detail("DOCTYPE not allowed")),
            Ok(Event::Start(e)) => {
                let n = AsRef::<str>::as_ref(&e.local_name()).to_string();
                if n == "Contents" {
                    key.clear();
                    size = 0;
                    lm = None;
                }
                stack.push(n);
            }
            Ok(Event::Text(t)) => {
                let s = t.xml10_content().to_string();
                let cur = stack.last().map(|s| s.as_str()).unwrap_or("");
                let parent = if stack.len() >= 2 { stack[stack.len() - 2].as_str() } else { "" };
                match (parent, cur) {
                    ("Contents", "Key") => key = s,
                    ("Contents", "Size") => size = s.parse().unwrap_or(0),
                    ("Contents", "LastModified") => lm = parse_rfc3339(&s),
                    ("CommonPrefixes", "Prefix") => page.prefixes.push(s),
                    (_, "IsTruncated") => page.truncated = s == "true",
                    (_, "NextContinuationToken") => page.next = Some(s),
                    _ => {}
                }
            }
            Ok(Event::End(e)) => {
                let n = AsRef::<str>::as_ref(&e.local_name()).to_string();
                if n == "Contents" {
                    page.contents.push((key.clone(), size, lm));
                }
                stack.pop();
            }
            _ => {}
        }
    }
    Ok(page)
}

#[async_trait]
impl Provider for S3Provider {
    async fn list(&self, path: &str) -> Result<Vec<RawEntry>> {
        let key = key_of(path);
        let prefix = if key.is_empty() { String::new() } else { format!("{key}/") };
        let mut out = vec![];
        let mut token: Option<String> = None;
        loop {
            let mut q = vec![
                ("list-type".to_string(), "2".to_string()),
                ("delimiter".to_string(), "/".to_string()),
                ("prefix".to_string(), prefix.clone()),
            ];
            if let Some(t) = &token {
                q.push(("continuation-token".into(), t.clone()));
            }
            let r = self.send_q("GET", "", &q, vec![], path).await?;
            let body = self.body_limited(r, path).await?;
            let page = parse_list(&body)?;
            for (k, size, lm) in page.contents {
                if k == prefix {
                    continue; // folder marker
                }
                out.push(RawEntry { name: k[prefix.len().min(k.len())..].to_string(), kind: EntryKind::File, size: Some(size), mtime_ms: lm });
            }
            for pf in page.prefixes {
                let n = pf[prefix.len().min(pf.len())..].trim_end_matches('/').to_string();
                out.push(RawEntry { name: n, kind: EntryKind::Dir, size: None, mtime_ms: None });
            }
            if out.len() > 250_000 {
                return Err(NdError::new(Code::LimitExceeded).with("path", path));
            }
            if page.truncated && page.next.is_some() {
                token = page.next;
            } else {
                break;
            }
        }
        Ok(out)
    }

    async fn stat(&self, path: &str) -> Result<RawEntry> {
        let key = key_of(path);
        let name = crate::pathguard::basename(path).to_string();
        if key.is_empty() {
            return Ok(RawEntry { name, kind: EntryKind::Dir, size: None, mtime_ms: None });
        }
        match self.send_q("HEAD", &key, &[], vec![], path).await {
            Ok(r) => {
                let size = r.headers().get("content-length").and_then(|v| v.to_str().ok()).and_then(|v| v.parse().ok());
                let lm = r.headers().get("last-modified").and_then(|v| v.to_str().ok()).and_then(crate::dates::parse_http_date);
                return Ok(RawEntry { name, kind: EntryKind::File, size, mtime_ms: lm });
            }
            Err(e) if e.code == Code::NotFound => {}
            // some S3 clones answer 5xx when a "directory" shares the key: fall back to a prefix listing
            Err(e) if e.params.get("http_status").and_then(|v| v.as_u64()).map(|s| s >= 500).unwrap_or(false) => {}
            Err(e) => return Err(e),
        }
        let q = vec![
            ("list-type".to_string(), "2".to_string()),
            ("prefix".to_string(), format!("{key}/")),
            ("max-keys".to_string(), "1".to_string()),
        ];
        let r = self.send_q("GET", "", &q, vec![], path).await?;
        let body = self.body_limited(r, path).await?;
        let page = parse_list(&body)?;
        if page.contents.is_empty() && page.prefixes.is_empty() {
            Err(NdError::new(Code::NotFound).with("path", path))
        } else {
            Ok(RawEntry { name, kind: EntryKind::Dir, size: None, mtime_ms: None })
        }
    }

    async fn mkdir(&self, path: &str) -> Result<()> {
        if super::exists(self, path).await? {
            return Err(NdError::new(Code::AlreadyExists).with("path", path));
        }
        let key = format!("{}/", key_of(path));
        let empty = sha256_hex(b"");
        let r = self.request("PUT", &key, &[], vec![], &empty).header("Content-Length", 0).send().await.map_err(|e| map_reqwest(e, &self.slot))?;
        self.check(r, path).await.map(|_| ())
    }

    async fn rename(&self, from: &str, to: &str) -> Result<()> {
        if super::exists(self, to).await? {
            return Err(NdError::new(Code::AlreadyExists).with("path", to));
        }
        let st = self.stat(from).await?;
        let (fk, tk) = (key_of(from), key_of(to));
        if st.kind == EntryKind::Dir {
            let fp = format!("{fk}/");
            let tp = format!("{tk}/");
            let keys = self.list_all_keys(&fp).await?;
            for k in &keys {
                self.copy_key(k, &format!("{tp}{}", &k[fp.len()..])).await?;
            }
            for k in &keys {
                self.send_q("DELETE", k, &[], vec![], from).await?;
            }
            Ok(())
        } else {
            self.copy_key(&fk, &tk).await?;
            self.send_q("DELETE", &fk, &[], vec![], from).await.map(|_| ())
        }
    }

    async fn delete_file(&self, path: &str) -> Result<()> {
        self.send_q("DELETE", &key_of(path), &[], vec![], path).await.map(|_| ())
    }

    async fn delete_dir(&self, path: &str) -> Result<()> {
        match self.send_q("DELETE", &format!("{}/", key_of(path)), &[], vec![], path).await {
            Ok(_) => Ok(()),
            Err(e) if e.code == Code::NotFound => Ok(()),
            Err(e) => Err(e),
        }
    }

    async fn open_read(&self, path: &str, offset: u64) -> Result<Reader> {
        let extra = if offset > 0 { vec![("range".to_string(), format!("bytes={offset}-"))] } else { vec![] };
        let empty = sha256_hex(b"");
        let mut rb = self.request("GET", &key_of(path), &[], vec![], &empty);
        for (k, v) in extra {
            rb = rb.header(k, v);
        }
        let r = rb.send().await.map_err(|e| map_reqwest(e, &self.slot))?;
        let r = self.check(r, path).await?;
        let partial = r.status() == StatusCode::PARTIAL_CONTENT;
        let slot = self.slot.clone();
        let mut reader = StreamReader::new(r.bytes_stream().map_err(move |e| std::io::Error::other(map_reqwest(e, &slot).to_string())));
        if offset > 0 && !partial {
            tokio::io::copy(&mut AsyncReadExt::take(&mut reader, offset), &mut tokio::io::sink()).await?;
        }
        Ok(Box::new(reader))
    }

    async fn put(&self, path: &str, mut src: Reader, size: u64, overwrite: bool, pr: &Progress) -> Result<()> {
        if !overwrite && super::exists(self, path).await? {
            return Err(NdError::new(Code::AlreadyExists).with("path", path));
        }
        let key = key_of(path);
        if size <= SINGLE_PUT_MAX {
            let pr2 = pr.clone();
            let stream = ReaderStream::with_capacity(src, CHUNK).map(move |r| match r {
                Ok(b) => {
                    if pr2.cancel.is_cancelled() {
                        return Err(std::io::Error::new(std::io::ErrorKind::Interrupted, "cancelled"));
                    }
                    pr2.report(b.len() as u64);
                    Ok(b)
                }
                Err(e) => Err(e),
            });
            let mut rb = self
                .request("PUT", &key, &[], vec![], "UNSIGNED-PAYLOAD")
                .header("Content-Length", size)
                .header("Content-Type", "application/octet-stream")
                .body(reqwest::Body::wrap_stream(stream));
            if !overwrite {
                rb = rb.header("If-None-Match", "*");
            }
            let slot = self.slot.clone();
            let res = pr
                .run(async {
                    let r = rb.send().await.map_err(|e| map_reqwest(e, &slot))?;
                    self.check(r, path).await.map(|_| ())
                })
                .await;
            return if pr.cancel.is_cancelled() { Err(NdError::new(Code::Cancelled)) } else { res };
        }
        // ---- multipart ----
        let r = self.send_q("POST", &key, &[("uploads".into(), String::new())], vec![], path).await?;
        let body = self.body_limited(r, path).await?;
        let xml = String::from_utf8_lossy(&body).to_string();
        let upload_id = xml.split("<UploadId>").nth(1).and_then(|s| s.split("</UploadId>").next()).ok_or_else(|| NdError::new(Code::ServerAnomaly).detail("no UploadId"))?.to_string();
        let res: Result<()> = async {
            let mut etags: Vec<String> = vec![];
            let mut part_no = 1u32;
            loop {
                pr.check()?;
                let mut buf = vec![0u8; PART];
                let mut filled = 0;
                while filled < PART {
                    let n = src.read(&mut buf[filled..]).await?;
                    if n == 0 {
                        break;
                    }
                    filled += n;
                }
                if filled == 0 && part_no > 1 {
                    break;
                }
                buf.truncate(filled);
                let hash = sha256_hex(&buf);
                let q = vec![("partNumber".to_string(), part_no.to_string()), ("uploadId".to_string(), upload_id.clone())];
                let rb = self.request("PUT", &key, &q, vec![], &hash).header("Content-Length", filled).body(buf);
                let slot = self.slot.clone();
                let r = pr.run(async { rb.send().await.map_err(|e| map_reqwest(e, &slot)) }).await?;
                let r = self.check(r, path).await?;
                let etag = r.headers().get("etag").and_then(|v| v.to_str().ok()).unwrap_or("").to_string();
                etags.push(etag);
                pr.report(filled as u64);
                part_no += 1;
                if filled < PART {
                    break;
                }
            }
            let mut xml = String::from("<CompleteMultipartUpload>");
            for (i, e) in etags.iter().enumerate() {
                xml.push_str(&format!("<Part><PartNumber>{}</PartNumber><ETag>{}</ETag></Part>", i + 1, e.replace('"', "&quot;")));
            }
            xml.push_str("</CompleteMultipartUpload>");
            let hash = sha256_hex(xml.as_bytes());
            let q = vec![("uploadId".to_string(), upload_id.clone())];
            let r = self.request("POST", &key, &q, vec![], &hash).body(xml).send().await.map_err(|e| map_reqwest(e, &self.slot))?;
            self.check(r, path).await.map(|_| ())
        }
        .await;
        if res.is_err() {
            let q = vec![("uploadId".to_string(), upload_id)];
            let _ = self.send_q("DELETE", &key, &q, vec![], path).await;
        }
        res
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    /// AWS documentation example: "GET Object" (examplebucket, test.txt, Range header) — we use the
    /// well-known "GET Bucket Lifecycle"-style vector from the SigV4 S3 docs.
    #[test]
    fn sigv4_aws_example_get_object() {
        let headers = vec![
            ("host".to_string(), "examplebucket.s3.amazonaws.com".to_string()),
            ("range".to_string(), "bytes=0-9".to_string()),
            ("x-amz-content-sha256".to_string(), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855".to_string()),
            ("x-amz-date".to_string(), "20130524T000000Z".to_string()),
        ];
        let a = sigv4_authorization(
            "GET",
            "/test.txt",
            &[],
            &headers,
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "AKIAIOSFODNN7EXAMPLE",
            "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            "us-east-1",
            "20130524T000000Z",
            "20130524",
        );
        assert!(a.ends_with("Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"), "{a}");
    }
    #[test]
    fn list_parse() {
        let xml = br#"<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><IsTruncated>true</IsTruncated><NextContinuationToken>tok</NextContinuationToken>
<Contents><Key>a/b.txt</Key><LastModified>2023-11-14T22:13:20.000Z</LastModified><Size>5</Size></Contents>
<CommonPrefixes><Prefix>a/sub/</Prefix></CommonPrefixes></ListBucketResult>"#;
        let p = parse_list(xml).unwrap();
        assert_eq!(p.contents, vec![("a/b.txt".to_string(), 5, Some(1_700_000_000_000))]);
        assert_eq!(p.prefixes, vec!["a/sub/".to_string()]);
        assert!(p.truncated);
        assert_eq!(p.next.as_deref(), Some("tok"));
    }
}

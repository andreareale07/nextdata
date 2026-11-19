//! WebDAV provider (HTTP Basic or anonymous) on reqwest + rustls with TOFU pinning.
//! XML is parsed with quick-xml (no entity expansion); DOCTYPE is rejected outright.
use super::tls::{connect_with_pinning, map_reqwest, Slot};
use super::{Progress, Provider, Reader, Trust, CHUNK};
use crate::dates::{parse_http_date, parse_rfc3339};
use crate::error::{Code, NdError, Result};
use crate::pathguard::{basename, norm_ref, parent_of};
use crate::types::{EntryKind, Profile, RawEntry, RemoteVersion, Secrets, SpaceInfo, VersionStrength};
use async_trait::async_trait;
use futures::{StreamExt, TryStreamExt};
use percent_encoding::{percent_decode_str, utf8_percent_encode, AsciiSet, NON_ALPHANUMERIC};
use quick_xml::events::Event;
use reqwest::{Client, Method, RequestBuilder, Response, StatusCode};
use std::sync::Arc;
use tokio_util::io::{ReaderStream, StreamReader};

const SEG: &AsciiSet = &NON_ALPHANUMERIC.remove(b'-').remove(b'.').remove(b'_').remove(b'~');
const MAX_XML: usize = 32 * 1024 * 1024;

pub fn enc_path(path: &str) -> String {
    path.split('/').map(|s| utf8_percent_encode(s, SEG).to_string()).collect::<Vec<_>>().join("/")
}

pub struct WebDavProvider {
    client: Client,
    slot: Slot,
    origin: String,    // scheme://host:port
    base_path: String, // "" or "/dav"
    auth: Option<(String, String)>,
}

fn status_err(s: StatusCode, path: &str) -> NdError {
    let code = match s.as_u16() {
        401 => Code::AuthFailed,
        403 => Code::PermissionDenied,
        404 | 410 => Code::NotFound,
        405 | 409 | 412 => Code::AlreadyExists,
        413 | 507 => Code::DiskFull,
        408 | 504 => Code::Timeout,
        423 => Code::PermissionDenied,
        500..=599 => Code::Network,
        _ => Code::ServerAnomaly,
    };
    NdError::new(code).with("path", path).with("http_status", s.as_u16())
}

impl WebDavProvider {
    pub async fn connect(p: &Profile, secrets: &Secrets, trust: Arc<dyn Trust>) -> Result<Arc<WebDavProvider>> {
        let scheme = if p.tls { "https" } else { "http" };
        let port = if p.port == 0 { if p.tls { 443 } else { 80 } } else { p.port };
        let origin = format!("{scheme}://{}:{port}", p.host);
        let base_path = {
            let n = norm_ref(&p.base_path)?;
            if n == "/" { String::new() } else { n }
        };
        let auth = if p.auth == "none" {
            None
        } else {
            let pw = match secrets.password.clone() {
                Some(x) => x,
                None => trust.ask_secret("password").await?.ok_or_else(|| NdError::new(Code::AuthFailed).detail("cancelled"))?,
            };
            Some((p.username.clone(), pw))
        };
        let url0 = format!("{origin}{}/", enc_path(&base_path));
        let auth2 = auth.clone();
        let (client, slot) = connect_with_pinning(&trust, &p.host, port, move |c, slot| {
            let url0 = url0.clone();
            let auth2 = auth2.clone();
            async move {
                let mut rb = c.request(Method::from_bytes(b"PROPFIND").unwrap(), &url0).header("Depth", "0");
                if let Some((u, pw)) = &auth2 {
                    rb = rb.basic_auth(u, Some(pw));
                }
                let r = rb.send().await.map_err(|e| map_reqwest(e, &slot))?;
                match r.status().as_u16() {
                    200 | 207 => Ok(()),
                    s => Err(status_err(StatusCode::from_u16(s).unwrap(), "/")),
                }
            }
        })
        .await?;
        Ok(Arc::new(WebDavProvider { client, slot, origin, base_path, auth }))
    }

    fn url(&self, path: &str) -> String {
        let mut p = enc_path(&format!("{}{}", self.base_path, if path == "/" { "" } else { path }));
        if p.is_empty() {
            p.push('/');
        }
        format!("{}{}", self.origin, p)
    }

    fn req(&self, m: &str, url: &str) -> RequestBuilder {
        let mut rb = self.client.request(Method::from_bytes(m.as_bytes()).unwrap(), url);
        if let Some((u, p)) = &self.auth {
            rb = rb.basic_auth(u, Some(p));
        }
        rb
    }

    async fn send(&self, rb: RequestBuilder, path: &str) -> Result<Response> {
        let r = rb.send().await.map_err(|e| map_reqwest(e, &self.slot))?;
        if r.status().is_success() {
            Ok(r)
        } else {
            Err(status_err(r.status(), path))
        }
    }

    async fn propfind(&self, path: &str, depth: &str) -> Result<Vec<(String, RawEntry)>> {
        let body = r#"<?xml version="1.0" encoding="utf-8"?><propfind xmlns="DAV:"><prop><resourcetype/><getcontentlength/><getlastmodified/></prop></propfind>"#;
        let url = if path == "/" || !self.url(path).ends_with('/') { format!("{}/", self.url(path).trim_end_matches('/')) } else { self.url(path) };
        let r = self
            .send(
                self.req("PROPFIND", &url).header("Depth", depth).header("Content-Type", "application/xml").body(body),
                path,
            )
            .await?;
        if let Some(l) = r.content_length() {
            if l as usize > MAX_XML {
                return Err(NdError::new(Code::LimitExceeded).with("path", path));
            }
        }
        let mut buf: Vec<u8> = Vec::new();
        let mut st = r.bytes_stream();
        while let Some(chunk) = st.next().await {
            let c = chunk.map_err(|e| map_reqwest(e, &self.slot))?;
            buf.extend_from_slice(&c);
            if buf.len() > MAX_XML {
                return Err(NdError::new(Code::LimitExceeded).with("path", path));
            }
        }
        parse_multistatus(&buf, &self.base_path)
    }
}

/// Returns (decoded path relative to base, entry)
pub fn parse_multistatus(xml: &[u8], base_path: &str) -> Result<Vec<(String, RawEntry)>> {
    let mut rd = quick_xml::Reader::from_reader(xml);
    rd.config_mut().trim_text(true);
    let mut out = Vec::new();
    let mut stack: Vec<String> = Vec::new();
    let (mut href, mut is_dir, mut len, mut lm) = (String::new(), false, None::<u64>, None::<String>);
    let mut text = String::new();
    loop {
        match rd.read_event() {
            Err(e) => return Err(NdError::new(Code::ServerAnomaly).detail(e)),
            Ok(Event::Eof) => break,
            Ok(Event::DocType(_)) => return Err(NdError::new(Code::ServerAnomaly).detail("DOCTYPE not allowed")),
            Ok(Event::Start(e)) => {
                let n = AsRef::<str>::as_ref(&e.local_name()).to_ascii_lowercase();
                if n == "response" {
                    href.clear();
                    is_dir = false;
                    len = None;
                    lm = None;
                }
                text.clear();
                stack.push(n);
            }
            Ok(Event::Empty(e)) => {
                let n = AsRef::<str>::as_ref(&e.local_name()).to_ascii_lowercase();
                if n == "collection" {
                    is_dir = true;
                }
            }
            Ok(Event::Text(t)) => {
                text.push_str(&t.xml10_content());
            }
            Ok(Event::GeneralRef(r)) => {
                let name: &str = &r;
                text.push(crate::xmlutil::resolve_ref(name)?);
            }
            Ok(Event::End(e)) => {
                let n = AsRef::<str>::as_ref(&e.local_name()).to_ascii_lowercase();
                match n.as_str() {
                    "href" => href = text.clone(),
                    "getcontentlength" => len = text.trim().parse().ok(),
                    "getlastmodified" => lm = Some(text.clone()),
                    "collection" => is_dir = true,
                    "response" => {
                        if let Some(path) = href_to_path(&href, base_path) {
                            let name = basename(&path).to_string();
                            out.push((
                                path,
                                RawEntry {
                                    name,
                                    kind: if is_dir { EntryKind::Dir } else { EntryKind::File },
                                    size: if is_dir { None } else { len },
                                    mtime_ms: lm.as_deref().and_then(|s| parse_http_date(s).or_else(|| parse_rfc3339(s))),
                                },
                            ));
                        }
                    }
                    _ => {}
                }
                text.clear();
                stack.pop();
            }
            _ => {}
        }
    }
    Ok(out)
}

/// First `getetag` of a multistatus (weak-validator prefix `W/` and quotes removed; never a DOCTYPE).
pub fn parse_etag(xml: &[u8]) -> Result<Option<String>> {
    let mut rd = quick_xml::Reader::from_reader(xml);
    rd.config_mut().trim_text(true);
    let (mut in_etag, mut text) = (false, String::new());
    loop {
        match rd.read_event() {
            Err(e) => return Err(NdError::new(Code::ServerAnomaly).detail(e)),
            Ok(Event::Eof) => return Ok(None),
            Ok(Event::DocType(_)) => return Err(NdError::new(Code::ServerAnomaly).detail("DOCTYPE not allowed")),
            Ok(Event::Start(e)) => {
                in_etag = AsRef::<str>::as_ref(&e.local_name()).eq_ignore_ascii_case("getetag");
                text.clear();
            }
            Ok(Event::Text(t)) if in_etag => text.push_str(&t.xml10_content()),
            Ok(Event::GeneralRef(r)) if in_etag => {
                let name: &str = &r;
                text.push(crate::xmlutil::resolve_ref(name)?);
            }
            Ok(Event::End(e)) => {
                if in_etag && AsRef::<str>::as_ref(&e.local_name()).eq_ignore_ascii_case("getetag") {
                    let t = text.trim().trim_start_matches("W/").trim_matches('"').to_string();
                    return Ok(if t.is_empty() { None } else { Some(t) });
                }
                in_etag = false;
            }
            _ => {}
        }
    }
}

/// `(quota-available-bytes, quota-used-bytes)` of a multistatus. A negative or unparsable value (Nextcloud answers -3 for
/// "unlimited", -2 for "unknown") means "not known", never a size.
pub fn parse_quota(xml: &[u8]) -> Result<(Option<u64>, Option<u64>)> {
    let mut rd = quick_xml::Reader::from_reader(xml);
    rd.config_mut().trim_text(true);
    let (mut avail, mut used) = (None, None);
    let mut text = String::new();
    loop {
        match rd.read_event() {
            Err(e) => return Err(NdError::new(Code::ServerAnomaly).detail(e)),
            Ok(Event::Eof) => break,
            Ok(Event::DocType(_)) => return Err(NdError::new(Code::ServerAnomaly).detail("DOCTYPE not allowed")),
            Ok(Event::Start(_)) => text.clear(),
            Ok(Event::Text(t)) => text.push_str(&t.xml10_content()),
            Ok(Event::GeneralRef(r)) => {
                let name: &str = &r;
                text.push(crate::xmlutil::resolve_ref(name)?);
            }
            Ok(Event::End(e)) => {
                let n = AsRef::<str>::as_ref(&e.local_name()).to_ascii_lowercase();
                let v = text.trim().parse::<i64>().ok().filter(|v| *v >= 0).map(|v| v as u64);
                match n.as_str() {
                    "quota-available-bytes" if avail.is_none() => avail = v,
                    "quota-used-bytes" if used.is_none() => used = v,
                    _ => {}
                }
                text.clear();
            }
            _ => {}
        }
    }
    Ok((avail, used))
}

fn href_to_path(href: &str, base: &str) -> Option<String> {
    let mut h = href.trim();
    if let Some(i) = h.find("://") {
        let rest = &h[i + 3..];
        h = &rest[rest.find('/')?..];
    }
    let dec = percent_decode_str(h).decode_utf8().ok()?.to_string();
    let rel = if base.is_empty() { dec.as_str() } else { dec.strip_prefix(base)? };
    norm_ref(rel).ok()
}

#[async_trait]
impl Provider for WebDavProvider {
    async fn list(&self, path: &str) -> Result<Vec<RawEntry>> {
        let all = self.propfind(path, "1").await?;
        let me = norm_ref(path)?;
        Ok(all
            .into_iter()
            // keep only direct children of the requested directory (anything else = server anomaly, dropped)
            .filter(|(p, _)| p != &me && parent_of(p).as_deref() == Some(me.as_str()))
            .map(|(_, e)| e)
            .collect())
    }

    async fn stat(&self, path: &str) -> Result<RawEntry> {
        let all = self.propfind(path, "0").await?;
        let me = norm_ref(path)?;
        all.into_iter().find(|(p, _)| p == &me).map(|(_, e)| e).ok_or_else(|| NdError::new(Code::NotFound).with("path", path))
    }

    async fn mkdir(&self, path: &str) -> Result<()> {
        let url = format!("{}/", self.url(path).trim_end_matches('/'));
        self.send(self.req("MKCOL", &url), path).await.map(|_| ())
    }

    async fn rename(&self, from: &str, to: &str) -> Result<()> {
        let dest = self.url(to);
        self.send(self.req("MOVE", &self.url(from)).header("Destination", dest).header("Overwrite", "F"), to).await.map(|_| ())
    }

    async fn delete_file(&self, path: &str) -> Result<()> {
        self.send(self.req("DELETE", &self.url(path)), path).await.map(|_| ())
    }

    async fn delete_dir(&self, path: &str) -> Result<()> {
        let url = format!("{}/", self.url(path).trim_end_matches('/'));
        self.send(self.req("DELETE", &url), path).await.map(|_| ())
    }

    async fn open_read(&self, path: &str, offset: u64) -> Result<Reader> {
        let mut rb = self.req("GET", &self.url(path));
        if offset > 0 {
            rb = rb.header("Range", format!("bytes={offset}-"));
        }
        let r = self.send(rb, path).await?;
        let status = r.status();
        let slot = self.slot.clone();
        let stream = r.bytes_stream().map_err(move |e| std::io::Error::other(map_reqwest(e, &slot).to_string()));
        let mut reader = StreamReader::new(stream);
        if offset > 0 && status != StatusCode::PARTIAL_CONTENT {
            // server ignored Range: discard the prefix
            tokio::io::copy(&mut tokio::io::AsyncReadExt::take(&mut reader, offset), &mut tokio::io::sink()).await?;
        }
        Ok(Box::new(reader))
    }

    async fn version(&self, path: &str) -> Result<RemoteVersion> {
        let st = self.stat(path).await?;
        if st.kind != EntryKind::File {
            return Err(NdError::new(Code::InvalidArgument).with("path", path).detail("not a file"));
        }
        let mut v = RemoteVersion::from_stat(&st, 1000);
        // getetag is optional in RFC 4918: ask separately so a server without it still lists normally
        let body = r#"<?xml version="1.0" encoding="utf-8"?><propfind xmlns="DAV:"><prop><getetag/></prop></propfind>"#;
        let url = self.url(path);
        if let Ok(r) = self.send(self.req("PROPFIND", &url).header("Depth", "0").header("Content-Type", "application/xml").body(body), path).await {
            let mut buf: Vec<u8> = Vec::new();
            let mut s = r.bytes_stream();
            while let Some(Ok(c)) = s.next().await {
                buf.extend_from_slice(&c);
                if buf.len() > MAX_XML {
                    return Err(NdError::new(Code::LimitExceeded).with("path", path));
                }
            }
            if let Some(e) = parse_etag(&buf)? {
                v.etag = Some(e);
                v.strength = VersionStrength::Strong;
            }
        }
        Ok(v)
    }

    async fn read_at(&self, path: &str, offset: u64, len: usize, pin: &RemoteVersion) -> Result<Vec<u8>> {
        if len == 0 || pin.size.is_some_and(|s| offset >= s) {
            return Ok(vec![]);
        }
        let last = offset + len as u64 - 1;
        let mut rb = self.req("GET", &self.url(path)).header("Range", format!("bytes={offset}-{last}"));
        if let Some(e) = &pin.etag {
            rb = rb.header("If-Match", format!("\"{e}\""));
        }
        let r = rb.send().await.map_err(|e| map_reqwest(e, &self.slot))?;
        if r.status() == StatusCode::PRECONDITION_FAILED {
            return Err(NdError::new(Code::VersionChanged).with("path", path));
        }
        if r.status() == StatusCode::RANGE_NOT_SATISFIABLE {
            return Ok(vec![]);
        }
        if !r.status().is_success() {
            return Err(status_err(r.status(), path));
        }
        if r.status() != StatusCode::PARTIAL_CONTENT {
            return Err(NdError::new(Code::RangeUnsupported).with("path", path));
        }
        let mut buf = Vec::with_capacity(len);
        let mut s = r.bytes_stream();
        while let Some(c) = s.next().await {
            buf.extend_from_slice(&c.map_err(|e| map_reqwest(e, &self.slot))?);
            if buf.len() > len {
                return Err(NdError::new(Code::ServerAnomaly).with("path", path).detail("server sent more data than requested"));
            }
        }
        Ok(buf)
    }

    async fn replace(&self, ctx: &crate::replace::ReplaceCtx, src: Reader, log: &mut dyn crate::journal::ReplaceLog, pr: &Progress) -> Result<crate::replace::Replaced> {
        super::replace_via_put(self, ctx, src, false, log, pr).await
    }

    async fn space(&self, path: &str) -> Result<Option<SpaceInfo>> {
        // RFC 4331 quota properties, optional: a server that does not know them answers 404 inside the multistatus.
        let body = r#"<?xml version="1.0" encoding="utf-8"?><propfind xmlns="DAV:"><prop><quota-available-bytes/><quota-used-bytes/></prop></propfind>"#;
        let url = format!("{}/", self.url(path).trim_end_matches('/'));
        let r = match self.send(self.req("PROPFIND", &url).header("Depth", "0").header("Content-Type", "application/xml").body(body), path).await {
            Ok(r) => r,
            Err(e) if matches!(e.code, Code::PermissionDenied | Code::NotFound | Code::AlreadyExists) => return Ok(None),
            Err(e) => return Err(e),
        };
        let mut buf: Vec<u8> = Vec::new();
        let mut s = r.bytes_stream();
        while let Some(c) = s.next().await {
            buf.extend_from_slice(&c.map_err(|e| map_reqwest(e, &self.slot))?);
            if buf.len() > MAX_XML {
                return Err(NdError::new(Code::LimitExceeded).with("path", path));
            }
        }
        let (avail, used) = parse_quota(&buf)?;
        Ok(SpaceInfo { total: None, used, free: avail }.normalized())
    }

    async fn copy_file(&self, from: &str, to: &str, _size: u64, _pr: &Progress) -> Result<()> {
        // server-side COPY: the data never leaves the server; Overwrite: F never replaces an existing target
        let dest = self.url(to);
        self.send(self.req("COPY", &self.url(from)).header("Destination", dest).header("Overwrite", "F"), to).await.map(|_| ())
    }

    async fn put(&self, path: &str, src: Reader, size: u64, overwrite: bool, pr: &Progress) -> Result<()> {
        if !overwrite && super::exists(self, path).await? {
            return Err(NdError::new(Code::AlreadyExists).with("path", path));
        }
        let part = format!("{path}.nd-part");
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
        let rb = self
            .req("PUT", &self.url(&part))
            .header("Content-Length", size)
            .header("Content-Type", "application/octet-stream")
            .body(reqwest::Body::wrap_stream(stream));
        let res = pr.run(async { self.send(rb, path).await }).await;
        if let Err(e) = res {
            let _ = self.send(self.req("DELETE", &self.url(&part)), &part).await;
            return Err(if pr.cancel.is_cancelled() { NdError::new(Code::Cancelled) } else { e });
        }
        let dest = self.url(path);
        let mv = self.req("MOVE", &self.url(&part)).header("Destination", dest).header("Overwrite", if overwrite { "T" } else { "F" });
        if let Err(e) = self.send(mv, path).await {
            let _ = self.send(self.req("DELETE", &self.url(&part)), &part).await;
            return Err(e);
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn parse() {
        let xml = br#"<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">
<D:response><D:href>/dav/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
<D:response><D:href>/dav/a%20b.txt</D:href><D:propstat><D:prop><D:resourcetype/><D:getcontentlength>12</D:getcontentlength><D:getlastmodified>Tue, 14 Nov 2023 22:13:20 GMT</D:getlastmodified></D:prop></D:propstat></D:response>
<D:response><D:href>http://h:80/dav/sub/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
<D:response><D:href>/dav/../etc/passwd</D:href></D:response>
</D:multistatus>"#;
        let v = parse_multistatus(xml, "/dav").unwrap();
        let names: Vec<_> = v.iter().map(|(p, e)| (p.clone(), e.kind, e.size)).collect();
        assert!(names.contains(&("/a b.txt".to_string(), EntryKind::File, Some(12))));
        assert!(names.contains(&("/sub".to_string(), EntryKind::Dir, None)));
        assert!(!names.iter().any(|(p, _, _)| p.contains("passwd")));
    }
    #[test]
    fn doctype_rejected() {
        let xml = br#"<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a "b">]><multistatus/>"#;
        assert!(parse_multistatus(xml, "").is_err());
    }

    #[test]
    fn quota_parsing() {
        let ok = br#"<D:multistatus xmlns:D="DAV:"><D:response><D:propstat><D:prop><D:quota-available-bytes>1200</D:quota-available-bytes><D:quota-used-bytes>2500</D:quota-used-bytes></D:prop></D:propstat></D:response></D:multistatus>"#;
        assert_eq!(parse_quota(ok).unwrap(), (Some(1200), Some(2500)));
        // Nextcloud: -3 = unlimited, -2 = unknown -> not a size
        let neg = br#"<d:multistatus xmlns:d="DAV:"><d:response><d:propstat><d:prop><d:quota-available-bytes>-3</d:quota-available-bytes><d:quota-used-bytes>77</d:quota-used-bytes></d:prop></d:propstat></d:response></d:multistatus>"#;
        assert_eq!(parse_quota(neg).unwrap(), (None, Some(77)));
        // property reported as 404: empty prop element
        let missing = br#"<d:multistatus xmlns:d="DAV:"><d:response><d:propstat><d:prop><d:quota-available-bytes/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response></d:multistatus>"#;
        assert_eq!(parse_quota(missing).unwrap(), (None, None));
        assert!(parse_quota(br#"<!DOCTYPE x [<!ENTITY a "b">]><a/>"#).is_err());
    }

    #[test]
    fn etag_parsing() {
        let x = br#"<D:multistatus xmlns:D="DAV:"><D:response><D:propstat><D:prop><D:getetag>W/"abc-1"</D:getetag></D:prop></D:propstat></D:response></D:multistatus>"#;
        assert_eq!(parse_etag(x).unwrap().as_deref(), Some("abc-1"));
        let none = br#"<D:multistatus xmlns:D="DAV:"><D:response><D:propstat><D:prop/></D:propstat></D:response></D:multistatus>"#;
        assert_eq!(parse_etag(none).unwrap(), None);
        assert!(parse_etag(br#"<!DOCTYPE x [<!ENTITY a "b">]><a/>"#).is_err());
    }
}

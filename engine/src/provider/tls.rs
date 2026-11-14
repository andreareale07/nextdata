//! TLS for HTTPS providers: normal WebPKI validation first; if it fails, accept only an explicitly
//! pinned SPKI (TOFU after user confirmation). The failing certificate is reported via `slot`.
use super::{CertInfo, Trust};
use crate::crypto::sha256_hex;
use crate::error::{Code, NdError, Result};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::client::WebPkiServerVerifier;
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, RootCertStore, SignatureScheme};
use std::sync::{Arc, Mutex};

pub type Slot = Arc<Mutex<Option<CertInfo>>>;

#[derive(Debug)]
struct PinVerifier {
    inner: Arc<WebPkiServerVerifier>,
    pins: Vec<String>,
    slot: Slot,
}

pub fn spki_hash_and_subject(der: &[u8]) -> (String, String, bool) {
    match x509_parser::parse_x509_certificate(der) {
        Ok((_, c)) => {
            let spki = sha256_hex(c.tbs_certificate.subject_pki.raw);
            let self_signed = c.tbs_certificate.subject.as_raw() == c.tbs_certificate.issuer.as_raw();
            (spki, c.tbs_certificate.subject.to_string(), self_signed)
        }
        Err(_) => (sha256_hex(der), "?".into(), false),
    }
}

impl ServerCertVerifier for PinVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        intermediates: &[CertificateDer<'_>],
        server_name: &ServerName<'_>,
        ocsp: &[u8],
        now: UnixTime,
    ) -> std::result::Result<ServerCertVerified, rustls::Error> {
        match self.inner.verify_server_cert(end_entity, intermediates, server_name, ocsp, now) {
            Ok(v) => Ok(v),
            Err(e) => {
                let (spki, subject, self_signed) = spki_hash_and_subject(end_entity.as_ref());
                if self.pins.iter().any(|p| p == &spki) {
                    return Ok(ServerCertVerified::assertion());
                }
                *self.slot.lock().unwrap() = Some(CertInfo { spki_sha256: spki, subject, self_signed, reason: e.to_string() });
                Err(e)
            }
        }
    }
    fn verify_tls12_signature(&self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        self.inner.verify_tls12_signature(m, c, d)
    }
    fn verify_tls13_signature(&self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        self.inner.verify_tls13_signature(m, c, d)
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.inner.supported_verify_schemes()
    }
}

pub fn client_config(pins: Vec<String>) -> Result<(rustls::ClientConfig, Slot)> {
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let inner = WebPkiServerVerifier::builder_with_provider(Arc::new(roots), provider.clone())
        .build()
        .map_err(|e| NdError::internal(e))?;
    let slot: Slot = Arc::new(Mutex::new(None));
    let v = PinVerifier { inner, pins, slot: slot.clone() };
    let cfg = rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|e| NdError::internal(e))?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(v))
        .with_no_client_auth();
    Ok((cfg, slot))
}

/// Build an HTTP client (redirects: only same-origin, max 3).
pub fn http_client(pins: Vec<String>) -> Result<(reqwest::Client, Slot)> {
    let (cfg, slot) = client_config(pins)?;
    let policy = reqwest::redirect::Policy::custom(|a| {
        if a.previous().len() >= 3 {
            return a.error("too many redirects");
        }
        let first = &a.previous()[0];
        let next = a.url();
        if first.scheme() == next.scheme() && first.host_str() == next.host_str() && first.port_or_known_default() == next.port_or_known_default() {
            a.follow()
        } else {
            a.error("cross-origin redirect refused")
        }
    });
    let c = reqwest::Client::builder()
        .tls_backend_preconfigured(cfg)
        .redirect(policy)
        .connect_timeout(std::time::Duration::from_secs(15))
        .read_timeout(std::time::Duration::from_secs(60))
        .pool_max_idle_per_host(4)
        .build()
        .map_err(|e| NdError::internal(e))?;
    Ok((c, slot))
}

/// Map a reqwest error to our codes, consulting the TLS slot.
pub fn map_reqwest(e: reqwest::Error, slot: &Slot) -> NdError {
    if let Some(ci) = slot.lock().unwrap().clone() {
        return NdError::new(Code::TlsUntrusted)
            .with("spki_sha256", ci.spki_sha256)
            .with("subject", ci.subject)
            .with("self_signed", ci.self_signed)
            .with("reason", ci.reason);
    }
    if e.is_timeout() {
        return NdError::new(Code::Timeout).detail(e);
    }
    if e.is_connect() {
        return NdError::new(Code::Network).detail(e);
    }
    let s = format!("{e:?}");
    if s.contains("certificate") || s.contains("Tls") || s.contains("tls") {
        return NdError::new(Code::TlsError).detail(e);
    }
    if e.is_redirect() {
        return NdError::new(Code::ServerAnomaly).detail(e);
    }
    NdError::new(Code::Network).detail(e)
}

/// Probe-and-pin loop shared by WebDAV/S3: `probe` performs a cheap request with the given client.
pub async fn connect_with_pinning<F, Fut>(trust: &Arc<dyn Trust>, host: &str, port: u32, mut probe: F) -> Result<(reqwest::Client, Slot)>
where
    F: FnMut(reqwest::Client, Slot) -> Fut,
    Fut: std::future::Future<Output = Result<()>>,
{
    for attempt in 0..2 {
        let (client, slot) = http_client(trust.tls_pins(host, port))?;
        match probe(client.clone(), slot.clone()).await {
            Ok(()) => return Ok((client, slot)),
            Err(e) if e.code == Code::TlsUntrusted && attempt == 0 => {
                let ci = slot.lock().unwrap().clone().unwrap_or(CertInfo {
                    spki_sha256: e.params.get("spki_sha256").and_then(|v| v.as_str()).unwrap_or("").into(),
                    subject: "?".into(),
                    self_signed: false,
                    reason: String::new(),
                });
                trust.tls_cert(host, port, &ci).await?;
            }
            Err(e) => return Err(e),
        }
    }
    Err(NdError::new(Code::TlsUntrusted))
}

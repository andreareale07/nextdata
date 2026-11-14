//! Provider abstraction: protocol engines implement this; everything above is protocol-agnostic.
use crate::error::{Code, NdError, Result};
use crate::types::RawEntry;
use async_trait::async_trait;
use std::future::Future;
use std::sync::Arc;
use tokio::io::AsyncRead;
use tokio_util::sync::CancellationToken;

pub mod fake;
pub mod s3;
pub mod sftp;
pub mod tls;
pub mod webdav;

pub type Reader = Box<dyn AsyncRead + Send + Unpin>;

/// Progress sink + cooperative cancellation handed to long operations.
#[derive(Clone)]
pub struct Progress {
    pub cb: Arc<dyn Fn(u64) + Send + Sync>,
    pub cancel: CancellationToken,
}

impl Progress {
    pub fn none() -> Progress {
        Progress { cb: Arc::new(|_| {}), cancel: CancellationToken::new() }
    }
    pub fn report(&self, delta: u64) {
        (self.cb)(delta)
    }
    pub fn check(&self) -> Result<()> {
        if self.cancel.is_cancelled() {
            Err(NdError::new(Code::Cancelled))
        } else {
            Ok(())
        }
    }
    /// Run a future, aborting it (dropping it) as soon as cancellation fires.
    pub async fn run<T>(&self, fut: impl Future<Output = Result<T>>) -> Result<T> {
        tokio::select! {
            biased;
            _ = self.cancel.cancelled() => Err(NdError::new(Code::Cancelled)),
            r = fut => r,
        }
    }
}

pub const CHUNK: usize = 256 * 1024;

#[derive(Debug, Clone)]
pub struct CertInfo {
    pub spki_sha256: String,
    pub subject: String,
    pub self_signed: bool,
    pub reason: String,
}

/// Trust/auth decisions, implemented by the engine (persisted pins + prompts to the UI).
#[async_trait]
pub trait Trust: Send + Sync {
    /// SSH host key: Ok(()) = trusted (and persisted); Err = HostKeyRejected / HostKeyChanged.
    async fn ssh_host_key(&self, host: &str, port: u32, algo: &str, fingerprint: &str) -> Result<()>;
    /// TLS certificate that failed normal validation: Ok(()) = pinned.
    async fn tls_cert(&self, host: &str, port: u32, cert: &CertInfo) -> Result<()>;
    /// Ask the user for a secret (password/passphrase). None = cancelled.
    async fn ask_secret(&self, kind: &str) -> Result<Option<String>>;
    /// Currently pinned TLS SPKI hashes for host:port.
    fn tls_pins(&self, host: &str, port: u32) -> Vec<String>;
}

#[async_trait]
pub trait Provider: Send + Sync {
    /// Initial directory ref ("/" unless a base path is configured inside the provider).
    fn home(&self) -> String {
        "/".into()
    }
    async fn list(&self, path: &str) -> Result<Vec<RawEntry>>;
    async fn stat(&self, path: &str) -> Result<RawEntry>;
    async fn mkdir(&self, path: &str) -> Result<()>;
    /// Must NOT replace an existing target (ALREADY_EXISTS).
    async fn rename(&self, from: &str, to: &str) -> Result<()>;
    async fn delete_file(&self, path: &str) -> Result<()>;
    /// Empty directory only; recursion is done by the engine (explicit stack, never through links).
    async fn delete_dir(&self, path: &str) -> Result<()>;
    async fn open_read(&self, path: &str, offset: u64) -> Result<Reader>;
    /// Create `path` from `src`. With `overwrite == false` an existing target yields ALREADY_EXISTS.
    async fn put(&self, path: &str, src: Reader, size: u64, overwrite: bool, pr: &Progress) -> Result<()>;
    async fn close(&self) {}
}

pub async fn exists(p: &dyn Provider, path: &str) -> Result<bool> {
    match p.stat(path).await {
        Ok(_) => Ok(true),
        Err(e) if e.code == Code::NotFound => Ok(false),
        Err(e) => Err(e),
    }
}

//! Provider abstraction: protocol engines implement this; everything above is protocol-agnostic.
use crate::error::{Code, NdError, Result};
use crate::types::{EntryKind, RawEntry, RemoteVersion, SpaceInfo};
use async_trait::async_trait;
use std::future::Future;
use std::sync::Arc;
use tokio::io::AsyncRead;
use tokio_util::sync::CancellationToken;

pub mod fake;
pub mod s3;
#[cfg(feature = "smb")]
pub mod smb;
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
    /// Identity of the object (size/mtime/etag...). Default: weak identity from `stat`.
    async fn version(&self, path: &str) -> Result<RemoteVersion> {
        let e = self.stat(path).await?;
        if e.kind != EntryKind::File {
            return Err(NdError::new(Code::InvalidArgument).with("path", path).detail("not a file"));
        }
        Ok(RemoteVersion::from_stat(&e, 1000))
    }
    /// Random-access read of at most `len` bytes at `offset` (fewer only at EOF; empty past EOF).
    /// `pin` is the version obtained at open time: providers with a conditional GET refuse with
    /// VERSION_CHANGED when the object changed. Default: sequential reader + `take` (correct everywhere).
    async fn read_at(&self, path: &str, offset: u64, len: usize, _pin: &RemoteVersion) -> Result<Vec<u8>> {
        read_via_open_read(self, path, offset, len).await
    }
    /// Create `path` from `src`. With `overwrite == false` an existing target yields ALREADY_EXISTS.
    async fn put(&self, path: &str, src: Reader, size: u64, overwrite: bool, pr: &Progress) -> Result<()>;
    /// Replace the existing file `ctx.path` with `src` (a snapshot of `ctx.size` bytes, SHA-256 `ctx.sha256`).
    /// `ctx.attempt` makes every temporary remote name unique and recognisable as ours.
    /// Default: the NON-atomic backup/rollback sequence (`replace::replace_with_backup`), journaled.
    async fn replace(
        &self,
        ctx: &crate::replace::ReplaceCtx,
        src: Reader,
        log: &mut dyn crate::journal::ReplaceLog,
        pr: &Progress,
    ) -> Result<crate::replace::Replaced> {
        crate::replace::replace_with_backup(self, ctx, src, log, pr).await
    }
    /// Capacity of the volume that holds `path`, as the server declares it. `Ok(None)` = the protocol or the
    /// server does not tell (S3, SFTP without `statvfs@openssh.com`, WebDAV without quota properties).
    async fn space(&self, _path: &str) -> Result<Option<SpaceInfo>> {
        Ok(None)
    }
    /// Create the file `to` as a copy of the file `from` (`size` bytes) inside the same session. Never replaces:
    /// an existing `to` is ALREADY_EXISTS. Default: read it back and write it again through this device (correct
    /// everywhere); providers with a server-side copy (S3, WebDAV) override it so the data never leaves the server.
    async fn copy_file(&self, from: &str, to: &str, size: u64, pr: &Progress) -> Result<()> {
        copy_via_stream(self, from, to, size, pr).await
    }
    async fn close(&self) {}
}

/// Copy through this device: `open_read` -> `put` (no overwrite).
pub async fn copy_via_stream<P: Provider + ?Sized>(p: &P, from: &str, to: &str, size: u64, pr: &Progress) -> Result<()> {
    let r = pr.run(async { p.open_read(from, 0).await }).await?;
    p.put(to, r, size, false, pr).await
}

/// Replacement through the provider's own overwrite (`put(overwrite=true)`): used where the protocol has a
/// replacing rename/PUT. A lost answer is never reported as success or failure without looking.
pub async fn replace_via_put<P: Provider + ?Sized>(
    p: &P,
    c: &crate::replace::ReplaceCtx,
    src: Reader,
    atomic: bool,
    log: &mut dyn crate::journal::ReplaceLog,
    pr: &Progress,
) -> Result<crate::replace::Replaced> {
    use crate::journal::Step;
    use crate::replace::{matches, Replaced};
    let (path, size, sha) = (c.path.as_str(), c.size, c.sha256.as_str());
    log.record(c.begin_step())?;
    log.record(Step::PutIntent)?;
    let res = match p.put(path, src, size, true, pr).await {
        Ok(()) => match matches(p, path, size, sha).await {
            Ok(Some(v)) => Ok(Replaced { atomic, verified: v, residue: vec![] }),
            _ => Err(NdError::new(Code::SaveUncertain).with("path", path).with("cause", "verification_failed")),
        },
        Err(e) if matches!(e.code, Code::Network | Code::Timeout) => match matches(p, path, size, sha).await {
            Ok(Some(v)) => Ok(Replaced { atomic, verified: v, residue: vec![] }),
            _ => Err(NdError::new(Code::SaveUncertain).with("path", path).with("cause", format!("{:?}", e.code))),
        },
        Err(e) => Err(e),
    };
    let _ = log.record(match &res {
        Ok(r) => Step::Completed { atomic: r.atomic },
        Err(e) if e.code == Code::SaveUncertain => Step::Uncertain,
        Err(e) => Step::Failed { code: format!("{:?}", e.code) },
    });
    res
}

/// Reads up to `len` bytes starting at `offset` through `open_read` (the reader is dropped afterwards).
pub async fn read_via_open_read<P: Provider + ?Sized>(p: &P, path: &str, offset: u64, len: usize) -> Result<Vec<u8>> {
    use tokio::io::AsyncReadExt;
    let mut r = p.open_read(path, offset).await?;
    let mut buf = vec![0u8; len];
    let mut filled = 0;
    while filled < len {
        let n = r.read(&mut buf[filled..]).await.map_err(NdError::from)?;
        if n == 0 {
            break;
        }
        filled += n;
    }
    buf.truncate(filled);
    Ok(buf)
}

pub async fn exists<P: Provider + ?Sized>(p: &P, path: &str) -> Result<bool> {
    match p.stat(path).await {
        Ok(_) => Ok(true),
        Err(e) if e.code == Code::NotFound => Ok(false),
        Err(e) => Err(e),
    }
}

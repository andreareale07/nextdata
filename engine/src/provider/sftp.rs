//! SFTP provider on russh + russh-sftp. Host key is verified BEFORE any credential is sent
//! (russh checks the server key during key exchange, authentication happens afterwards).
use super::{Progress, Provider, Reader, Trust, CHUNK};
use crate::error::{Code, NdError, Result};
use crate::types::{EntryKind, Profile, RawEntry, Secrets, SpaceInfo};
use async_trait::async_trait;
use russh::client::{self, AuthResult};
use russh::keys::{Algorithm, EcdsaCurve, HashAlg, PrivateKeyWithHashAlg, PublicKeyOrCertificate};
use russh::Preferred;
use russh_sftp::client::SftpSession;
use russh_sftp::protocol::{FileType, OpenFlags};
use std::borrow::Cow;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};

#[derive(Debug)]
pub enum HErr {
    Ssh(russh::Error),
    Denied,
}
impl From<russh::Error> for HErr {
    fn from(e: russh::Error) -> Self {
        HErr::Ssh(e)
    }
}

struct Handler {
    trust: Arc<dyn Trust>,
    host: String,
    port: u32,
    verdict: Arc<Mutex<Option<NdError>>>,
    wait: Arc<Mutex<UserWait>>,
}

/// Time the user spends deciding on a prompt does not count against the connect timeout.
#[derive(Default)]
struct UserWait {
    total: Duration,
    since: Option<Instant>,
}

impl client::Handler for Handler {
    type Error = HErr;

    async fn check_server_key(&mut self, key: &PublicKeyOrCertificate) -> std::result::Result<bool, HErr> {
        let pk = match key {
            PublicKeyOrCertificate::PublicKey { key, .. } => key,
            // We never advertise certificate host-key algorithms; refuse if a server sends one anyway.
            PublicKeyOrCertificate::Certificate(_) => {
                *self.verdict.lock().unwrap() = Some(NdError::new(Code::Unsupported).detail("host certificate"));
                return Err(HErr::Denied);
            }
        };
        let fp = pk.fingerprint(HashAlg::Sha256).to_string();
        let algo = pk.algorithm().to_string();
        self.wait.lock().unwrap().since = Some(Instant::now());
        let verdict = self.trust.ssh_host_key(&self.host, self.port, &algo, &fp).await;
        {
            let mut w = self.wait.lock().unwrap();
            if let Some(t) = w.since.take() {
                w.total += t.elapsed();
            }
        }
        match verdict {
            Ok(()) => Ok(true),
            Err(e) => {
                *self.verdict.lock().unwrap() = Some(e);
                Err(HErr::Denied)
            }
        }
    }
}

pub struct SftpProvider {
    sftp: SftpSession,
    _handle: client::Handle<Handler>,
    home: String,
}

fn map_ssh(e: russh::Error) -> NdError {
    use russh::Error as E;
    match e {
        E::ConnectionTimeout | E::KeepaliveTimeout | E::InactivityTimeout => NdError::new(Code::Timeout).detail(e),
        E::IO(ref io) => NdError::from(std::io::Error::new(io.kind(), io.to_string())),
        E::NoCommonAlgo { .. } => {
            NdError::new(Code::PolicyDenied).detail(e)
        }
        other => NdError::new(Code::Network).detail(other),
    }
}

fn map_sftp(e: russh_sftp::client::error::Error, path: &str) -> NdError {
    use russh_sftp::client::error::Error as E;
    use russh_sftp::protocol::StatusCode as S;
    match e {
        E::Status(st) => match st.status_code {
            S::NoSuchFile => NdError::new(Code::NotFound).with("path", path),
            S::PermissionDenied => NdError::new(Code::PermissionDenied).with("path", path),
            S::ConnectionLost | S::NoConnection => NdError::new(Code::Network).detail(st.error_message),
            S::OpUnsupported => NdError::new(Code::Unsupported).with("path", path),
            _ => NdError::new(Code::Io).with("path", path).detail(st.error_message),
        },
        E::Timeout => NdError::new(Code::Timeout).with("path", path),
        E::IO(m) => NdError::new(Code::Io).with("path", path).detail(m),
        other => NdError::new(Code::Io).with("path", path).detail(other),
    }
}

fn kind_of(t: FileType) -> EntryKind {
    if t.is_dir() {
        EntryKind::Dir
    } else if t.is_symlink() {
        EntryKind::Symlink
    } else if t.is_file() {
        EntryKind::File
    } else {
        EntryKind::Other
    }
}

pub fn ssh_config() -> client::Config {
    let mut cfg = client::Config::default();
    cfg.preferred = Preferred {
        // no SHA-1 "ssh-rsa" signatures; no CBC (not offered by russh defaults); no compression
        key: Cow::Owned(vec![
            Algorithm::Ed25519,
            Algorithm::Ecdsa { curve: EcdsaCurve::NistP256 },
            Algorithm::Ecdsa { curve: EcdsaCurve::NistP384 },
            Algorithm::Ecdsa { curve: EcdsaCurve::NistP521 },
            Algorithm::Rsa { hash: Some(HashAlg::Sha512) },
            Algorithm::Rsa { hash: Some(HashAlg::Sha256) },
        ]),
        compression: Cow::Owned(vec![russh::compression::NONE]),
        ..Preferred::DEFAULT
    };
    cfg.nodelay = true;
    cfg.keepalive_interval = Some(Duration::from_secs(30));
    cfg.keepalive_max = 4;
    cfg
}

impl SftpProvider {
    pub async fn connect(p: &Profile, secrets: &Secrets, trust: Arc<dyn Trust>) -> Result<Arc<SftpProvider>> {
        let port = if p.port == 0 { 22 } else { p.port };
        let verdict = Arc::new(Mutex::new(None));
        let wait = Arc::new(Mutex::new(UserWait::default()));
        let handler = Handler { trust: trust.clone(), host: p.host.clone(), port, verdict: verdict.clone(), wait: wait.clone() };
        let connect = client::connect(Arc::new(ssh_config()), (p.host.as_str(), port as u16), handler);
        tokio::pin!(connect);
        let started = Instant::now();
        let outcome = loop {
            tokio::select! {
                r = &mut connect => break Some(r),
                _ = tokio::time::sleep(Duration::from_millis(250)) => {
                    let w = wait.lock().unwrap();
                    // the clock stands still while a prompt is open
                    if w.since.is_none() && started.elapsed().saturating_sub(w.total) > Duration::from_secs(20) {
                        break None;
                    }
                }
            }
        };
        let mut session = match outcome {
            None => return Err(NdError::new(Code::Timeout).with("host", p.host.clone())),
            Some(Ok(s)) => s,
            Some(Err(e)) => {
                if let Some(v) = verdict.lock().unwrap().take() {
                    return Err(v);
                }
                return Err(match e {
                    HErr::Ssh(e) => map_ssh(e),
                    HErr::Denied => NdError::new(Code::HostKeyRejected),
                });
            }
        };

        // ---- authentication (host key already verified) ----
        let ok = match p.auth.as_str() {
            "key" => {
                let pem = secrets.private_key.clone().ok_or_else(|| NdError::new(Code::AuthFailed).detail("no key"))?;
                let mut pass = secrets.passphrase.clone();
                let key = match russh::keys::decode_secret_key(&pem, pass.as_deref()) {
                    Ok(k) => k,
                    Err(_) if pass.is_none() => {
                        pass = trust.ask_secret("passphrase").await?;
                        let ph = pass.clone().ok_or_else(|| NdError::new(Code::AuthFailed).detail("cancelled"))?;
                        russh::keys::decode_secret_key(&pem, Some(&ph)).map_err(|_| NdError::new(Code::PassphraseInvalid))?
                    }
                    Err(_) => return Err(NdError::new(Code::PassphraseInvalid)),
                };
                let hash = session.best_supported_rsa_hash().await.map_err(map_ssh)?.flatten();
                session
                    .authenticate_publickey(p.username.clone(), PrivateKeyWithHashAlg::new(Arc::new(key), hash))
                    .await
                    .map_err(map_ssh)?
            }
            _ => {
                let pw = match secrets.password.clone() {
                    Some(x) => x,
                    None => trust.ask_secret("password").await?.ok_or_else(|| NdError::new(Code::AuthFailed).detail("cancelled"))?,
                };
                session.authenticate_password(p.username.clone(), pw).await.map_err(map_ssh)?
            }
        };
        let success = matches!(ok, AuthResult::Success);
        if !success {
            return Err(NdError::new(Code::AuthFailed));
        }

        let ch = session.channel_open_session().await.map_err(map_ssh)?;
        ch.request_subsystem(true, "sftp").await.map_err(map_ssh)?;
        let sftp = SftpSession::new(ch.into_stream()).await.map_err(|e| map_sftp(e, "/"))?;
        sftp.set_timeout(60);
        let home = if !p.base_path.trim().is_empty() {
            crate::pathguard::norm_ref(&p.base_path)?
        } else {
            sftp.canonicalize(".").await.unwrap_or_else(|_| "/".into())
        };
        Ok(Arc::new(SftpProvider { sftp, _handle: session, home }))
    }
}

#[async_trait]
impl Provider for SftpProvider {
    fn home(&self) -> String {
        self.home.clone()
    }

    async fn list(&self, path: &str) -> Result<Vec<RawEntry>> {
        let rd = self.sftp.read_dir(path).await.map_err(|e| map_sftp(e, path))?;
        Ok(rd
            .map(|e| {
                let m = e.metadata();
                let kind = kind_of(e.file_type());
                RawEntry {
                    name: e.file_name(),
                    kind,
                    size: if kind == EntryKind::File { m.size } else { None },
                    mtime_ms: m.mtime.map(|s| s as i64 * 1000),
                }
            })
            .collect())
    }

    async fn stat(&self, path: &str) -> Result<RawEntry> {
        let m = self.sftp.symlink_metadata(path).await.map_err(|e| map_sftp(e, path))?;
        let kind = kind_of(m.file_type());
        Ok(RawEntry {
            name: crate::pathguard::basename(path).to_string(),
            kind,
            size: if kind == EntryKind::File { m.size } else { None },
            mtime_ms: m.mtime.map(|s| s as i64 * 1000),
        })
    }

    async fn mkdir(&self, path: &str) -> Result<()> {
        if super::exists(self, path).await? {
            return Err(NdError::new(Code::AlreadyExists).with("path", path));
        }
        self.sftp.create_dir(path).await.map_err(|e| map_sftp(e, path))
    }

    async fn rename(&self, from: &str, to: &str) -> Result<()> {
        if super::exists(self, to).await? {
            return Err(NdError::new(Code::AlreadyExists).with("path", to));
        }
        self.sftp.rename(from, to).await.map_err(|e| map_sftp(e, from))
    }

    async fn delete_file(&self, path: &str) -> Result<()> {
        self.sftp.remove_file(path).await.map_err(|e| map_sftp(e, path))
    }

    async fn delete_dir(&self, path: &str) -> Result<()> {
        self.sftp.remove_dir(path).await.map_err(|e| map_sftp(e, path))
    }

    async fn open_read(&self, path: &str, offset: u64) -> Result<Reader> {
        let mut f = self.sftp.open(path).await.map_err(|e| map_sftp(e, path))?;
        if offset > 0 {
            f.seek(std::io::SeekFrom::Start(offset)).await?;
        }
        Ok(Box::new(f))
    }

    async fn put(&self, path: &str, mut src: Reader, _size: u64, overwrite: bool, pr: &Progress) -> Result<()> {
        if overwrite {
            // never remove the original first: replacement goes through `Provider::replace` (backup + rollback)
            return Err(NdError::new(Code::Unsupported).with("path", path).detail("overwrite: use replace"));
        }
        if super::exists(self, path).await? {
            return Err(NdError::new(Code::AlreadyExists).with("path", path));
        }
        let part = format!("{path}.nd-part");
        let _ = self.sftp.remove_file(part.clone()).await; // stale partial from an earlier attempt
        let res: Result<()> = async {
            let mut f = self
                .sftp
                .open_with_flags(part.clone(), OpenFlags::CREATE | OpenFlags::EXCLUDE | OpenFlags::WRITE)
                .await
                .map_err(|e| map_sftp(e, &part))?;
            let mut buf = vec![0u8; CHUNK];
            loop {
                pr.check()?;
                let n = src.read(&mut buf).await?;
                if n == 0 {
                    break;
                }
                pr.run(async { f.write_all(&buf[..n]).await.map_err(NdError::from) }).await?;
                pr.report(n as u64);
            }
            pr.run(async { f.shutdown().await.map_err(NdError::from) }).await?;
            Ok(())
        }
        .await;
        if let Err(e) = res {
            let _ = self.sftp.remove_file(part.clone()).await;
            return Err(e);
        }
        if super::exists(self, path).await? {
            let _ = self.sftp.remove_file(part.clone()).await;
            return Err(NdError::new(Code::AlreadyExists).with("path", path));
        }
        if let Err(e) = self.sftp.rename(part.clone(), path).await {
            let _ = self.sftp.remove_file(part).await;
            return Err(map_sftp(e, path));
        }
        Ok(())
    }

    async fn space(&self, path: &str) -> Result<Option<SpaceInfo>> {
        // statvfs@openssh.com: what `df` reports. Servers without the extension (or that refuse it) simply have no answer.
        let v = match self.sftp.fs_info(path).await {
            Ok(Some(v)) => v,
            Ok(None) => return Ok(None),
            Err(e) => {
                let err = map_sftp(e, path);
                return if err.code == Code::Unsupported || err.code == Code::PermissionDenied { Ok(None) } else { Err(err) };
            }
        };
        let unit = if v.fragment_size > 0 { v.fragment_size } else { v.block_size };
        if unit == 0 || v.blocks == 0 {
            return Ok(None);
        }
        Ok(Some(SpaceInfo {
            total: Some(v.blocks.saturating_mul(unit)),
            // "free" for unprivileged users, as `df` shows it; "used" counts the root-reserved blocks too
            free: Some(v.blocks_avail.saturating_mul(unit)),
            used: Some(v.blocks.saturating_sub(v.blocks_free).saturating_mul(unit)),
        }))
    }

    async fn close(&self) {
        let _ = self.sftp.close().await;
    }
}

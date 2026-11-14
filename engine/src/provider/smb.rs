//! SMB provider on the pure-Rust `smb` crate. Security baseline (docs/smb-gate.md, docs/adr/0001):
//! SMB 3.1.1 minimum (no downgrade), signing always enforced by the crate for authenticated sessions
//! (guest/anonymous refused), encryption required in "strict" mode (default), NTLM only,
//! DFS referrals off (a server must not redirect us elsewhere), compression off,
//! destination resolved ONCE and checked: every address must be private/loopback/link-local.
use super::{Progress, Provider, Reader, Trust};
use crate::error::{Code, NdError, Result};
use crate::policy;
use crate::types::{EntryKind, Profile, RawEntry, Secrets};
use async_trait::async_trait;
use futures::StreamExt;
use smb::connection::EncryptionMode;
use smb::{
    Client, ClientConfig, ConnectionConfig, CreateOptions, Dialect, Directory, DirAccessMask, FileAccessMask,
    FileAllInformation, FileAttributes, FileCreateArgs, FileDispositionInformation, FileFullDirectoryInformation, FileRenameInformation,
    ReadAt, Resource, UncPath, WriteAt,
};
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

/// 64 KiB: the smallest maximum read/write every SMB 2.1+ server must accept.
const IO_CHUNK: usize = 64 * 1024;
const FILETIME_UNIX_DIFF_MS: i64 = 11_644_473_600_000;

pub struct SmbProvider {
    client: Client,
    root: UncPath,
    home: String,
}

// ---------------------------------------------------------------- error mapping
const ST_ACCESS_DENIED: u32 = 0xC000_0022;
const ST_NAME_NOT_FOUND: u32 = 0xC000_0034;
const ST_PATH_NOT_FOUND: u32 = 0xC000_003A;
const ST_NAME_COLLISION: u32 = 0xC000_0035;
const ST_NOT_EMPTY: u32 = 0xC000_0101;
const ST_BAD_NETWORK_NAME: u32 = 0xC000_00CC;
const ST_SHARING_VIOLATION: u32 = 0xC000_0043;
const ST_NOT_SUPPORTED: u32 = 0xC000_00BB;
const ST_DISK_FULL: u32 = 0xC000_007F;
const ST_LOGON_FAILURE: u32 = 0xC000_006D;
const ST_WRONG_PASSWORD: u32 = 0xC000_006A;
const ST_NO_SUCH_USER: u32 = 0xC000_0064;
const ST_ACCOUNT_DISABLED: u32 = 0xC000_0072;
const ST_ACCOUNT_RESTRICTION: u32 = 0xC000_006E;
const ST_ACCOUNT_LOCKED: u32 = 0xC000_0234;

fn status_of(e: &smb::Error) -> Option<u32> {
    match e {
        smb::Error::ReceivedErrorMessage(s, _) | smb::Error::UnexpectedMessageStatus(s) => Some(*s),
        _ => None,
    }
}

fn map_smb(e: smb::Error, path: &str) -> NdError {
    if let Some(st) = status_of(&e) {
        return match st {
            ST_NAME_NOT_FOUND | ST_PATH_NOT_FOUND => NdError::new(Code::NotFound).with("path", path).detail(format!("0x{st:08x}")),
            ST_NAME_COLLISION => NdError::new(Code::AlreadyExists).with("path", path),
            ST_ACCESS_DENIED | ST_SHARING_VIOLATION | ST_NOT_EMPTY => {
                NdError::new(Code::PermissionDenied).with("path", path).detail(format!("0x{st:08x}"))
            }
            ST_DISK_FULL => NdError::new(Code::DiskFull).with("path", path),
            ST_NOT_SUPPORTED => NdError::new(Code::Unsupported).with("path", path),
            _ => NdError::new(Code::Io).with("path", path).detail(format!("0x{st:08x}")),
        };
    }
    match e {
        smb::Error::SignatureVerificationFailed => NdError::new(Code::ServerAnomaly).detail("signature"),
        smb::Error::OperationTimeout(..) => NdError::new(Code::Timeout).with("path", path),
        smb::Error::IoError(io) => NdError::from(io),
        smb::Error::ConnectionStopped => NdError::new(Code::Network).detail("connection stopped"),
        smb::Error::MissingPermissions(_) => NdError::new(Code::PermissionDenied).with("path", path),
        other => NdError::new(Code::Io).with("path", path).detail(other),
    }
}

/// Errors while negotiating/authenticating: weaker-than-required servers are a POLICY refusal, not a network fault.
fn map_connect(e: smb::Error, strict: bool) -> NdError {
    if let Some(st) = status_of(&e) {
        return match st {
            ST_LOGON_FAILURE | ST_WRONG_PASSWORD | ST_NO_SUCH_USER | ST_ACCOUNT_DISABLED | ST_ACCOUNT_RESTRICTION | ST_ACCOUNT_LOCKED => {
                NdError::new(Code::AuthFailed)
            }
            // the server refused our (minimum 3.1.1) dialect
            ST_NOT_SUPPORTED => NdError::new(Code::PolicyDenied).with("rule", "smb_dialect").detail(format!("0x{st:08x}")),
            ST_BAD_NETWORK_NAME => NdError::new(Code::NotFound).with("path", "share"),
            ST_ACCESS_DENIED => {
                let mut err = NdError::new(Code::PermissionDenied).detail(format!("0x{st:08x}"));
                if !strict {
                    err = err.with("hint", "server_requires_encryption");
                }
                err
            }
            _ => NdError::new(Code::Network).detail(format!("0x{st:08x}")),
        };
    }
    match e {
        smb::Error::NegotiationError(m) => NdError::new(Code::PolicyDenied).with("rule", "smb_negotiation").detail(m),
        smb::Error::SignatureVerificationFailed => NdError::new(Code::ServerAnomaly).detail("signature"),
        smb::Error::SspiError(_) => NdError::new(Code::AuthFailed),
        smb::Error::OperationTimeout(..) => NdError::new(Code::Timeout),
        smb::Error::IoError(io) => NdError::from(io),
        other => NdError::new(Code::Network).detail(other),
    }
}

// ---------------------------------------------------------------- paths
const RESERVED: [&str; 22] = [
    "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2",
    "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
];

/// A path component we are willing to send: nothing a Windows server would interpret specially.
fn component_ok(c: &str) -> bool {
    if c.is_empty() || c == "." || c == ".." || c.len() > 255 {
        return false;
    }
    if c.chars().any(|ch| matches!(ch, '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*') || (ch as u32) < 32 || ch == '\u{7f}') {
        return false;
    }
    if c.ends_with('.') || c.ends_with(' ') {
        return false;
    }
    let stem = c.split('.').next().unwrap_or("").to_ascii_uppercase();
    !RESERVED.contains(&stem.as_str())
}

/// Engine ref ("/a/b") -> SMB path relative to the share ("a\b"). Root -> "".
pub fn to_smb_path(r: &str) -> Result<String> {
    let mut out: Vec<&str> = Vec::new();
    for c in r.split('/').filter(|c| !c.is_empty()) {
        if !component_ok(c) {
            return Err(NdError::new(Code::PolicyDenied).with("rule", "smb_name").with("path", r));
        }
        out.push(c);
    }
    Ok(out.join("\\"))
}

fn split_parent(r: &str) -> (String, String) {
    let t = r.trim_end_matches('/');
    match t.rfind('/') {
        Some(i) => (if i == 0 { "/".into() } else { t[..i].to_string() }, t[i + 1..].to_string()),
        None => ("/".into(), t.to_string()),
    }
}

fn filetime_ms(is_zero: bool, since_1601: Duration) -> Option<i64> {
    if is_zero {
        return None;
    }
    Some(since_1601.as_millis() as i64 - FILETIME_UNIX_DIFF_MS)
}

// ---------------------------------------------------------------- provider
impl SmbProvider {
    pub async fn connect(p: &Profile, secrets: &Secrets, trust: Arc<dyn Trust>) -> Result<Arc<SmbProvider>> {
        policy::check_smb_profile(p)?;
        let strict = p.smb_mode != "relaxed";
        let port = if p.port == 0 { 445 } else { p.port };
        let host = p.host.trim().trim_matches(['[', ']']).to_string();

        // Resolve once; every address must be private. We then connect to the literal address, so the
        // destination cannot change between this check and the connection (no DNS rebinding).
        let addrs: Vec<SocketAddr> = tokio::time::timeout(Duration::from_secs(10), tokio::net::lookup_host((host.as_str(), port as u16)))
            .await
            .map_err(|_| NdError::new(Code::Timeout).with("host", p.host.clone()))?
            .map_err(|e| NdError::new(Code::Network).with("host", p.host.clone()).detail(e))?
            .collect();
        let first = addrs.first().ok_or_else(|| NdError::new(Code::Network).with("host", p.host.clone()))?;
        if addrs.iter().any(|a| !policy::is_private_ip(a.ip())) {
            return Err(NdError::new(Code::PolicyDenied).with("rule", "smb_public_host").with("host", p.host.clone()));
        }
        let ip: IpAddr = first.ip();

        let password = match secrets.password.clone() {
            Some(x) => x,
            None => trust.ask_secret("password").await?.ok_or_else(|| NdError::new(Code::AuthFailed).detail("cancelled"))?,
        };

        let cfg = ClientConfig {
            dfs: false,
            connection: ConnectionConfig {
                port: Some(port as u16),
                timeout: Some(Duration::from_secs(20)),
                min_dialect: Some(Dialect::Smb0311),
                encryption_mode: if strict { EncryptionMode::Required } else { EncryptionMode::Allowed },
                allow_unsigned_guest_access: false,
                compression_enabled: false,
                ..Default::default()
            },
            ..Default::default()
        };
        let client = Client::new(cfg);
        let server = ip.to_string();
        let root = UncPath::new(&server)
            .and_then(|u| u.with_share(p.share.trim()))
            .map_err(|e| NdError::invalid(e))?;
        client.share_connect(&root, p.username.trim(), password).await.map_err(|e| map_connect(e, strict))?;
        let home = if p.base_path.trim().is_empty() { "/".to_string() } else { crate::pathguard::norm_ref(&p.base_path)? };
        Ok(Arc::new(SmbProvider { client, root, home }))
    }

    fn unc(&self, smb_path: &str) -> UncPath {
        self.root.clone().with_path(smb_path)
    }

    async fn open(&self, smb_path: &str, args: &FileCreateArgs, path: &str) -> Result<Resource> {
        self.client.create_file(&self.unc(smb_path), args).await.map_err(|e| map_smb(e, path))
    }

    async fn close_res(r: Resource) {
        match r {
            Resource::File(f) => {
                let _ = f.close().await;
            }
            Resource::Directory(d) => {
                let _ = d.close().await;
            }
            _ => {}
        }
    }

    fn entry_from(info: &FileFullDirectoryInformation) -> Option<RawEntry> {
        let name = info.file_name.to_string();
        if name == "." || name == ".." || !crate::pathguard::remote_name_ok(&name) || name.contains('\\') {
            return None;
        }
        let a = &info.file_attributes;
        // reparse points (symlinks, junctions, mount points) are never followed: reported as links
        let kind = if a.reparse_point() {
            EntryKind::Symlink
        } else if a.directory() {
            EntryKind::Dir
        } else {
            EntryKind::File
        };
        Some(RawEntry {
            name,
            kind,
            size: if kind == EntryKind::File { Some(info.end_of_file) } else { None },
            mtime_ms: filetime_ms(info.last_write_time.is_zero(), info.last_write_time.since_epoch()),
        })
    }

    async fn query_dir(&self, dir_ref: &str, pattern: &str) -> Result<Vec<RawEntry>> {
        let sp = to_smb_path(dir_ref)?;
        let args = FileCreateArgs::make_open_existing(DirAccessMask::new().with_list_directory(true).with_synchronize(true).into());
        let res = self.open(&sp, &args, dir_ref).await?;
        let dir: Arc<Directory> = match res {
            Resource::Directory(d) => Arc::new(d),
            other => {
                Self::close_res(other).await;
                return Err(NdError::new(Code::InvalidArgument).with("path", dir_ref).detail("not a directory"));
            }
        };
        let mut out = Vec::new();
        let result: Result<()> = async {
            let mut stream = Directory::query::<FileFullDirectoryInformation>(&dir, pattern).await.map_err(|e| map_smb(e, dir_ref))?;
            while let Some(item) = stream.next().await {
                let info = item.map_err(|e| map_smb(e, dir_ref))?;
                if let Some(e) = Self::entry_from(&info) {
                    out.push(e);
                }
            }
            Ok(())
        }
        .await;
        let _ = dir.close().await;
        result.map(|_| out)
    }

    async fn rename_open(&self, from: &str, to: &str, replace: bool) -> Result<()> {
        let from_sp = to_smb_path(from)?;
        let to_sp = to_smb_path(to)?;
        let args = FileCreateArgs::make_open_existing(FileAccessMask::new().with_generic_read(true).with_delete(true));
        let mut args = args;
        args.options = CreateOptions::new().with_open_reparse_point(true);
        let res = self.open(&from_sp, &args, from).await?;
        let r = match &res {
            Resource::File(f) => f.set_info(FileRenameInformation { replace_if_exists: replace.into(), root_directory: 0, file_name: to_sp.as_str().into() }).await,
            Resource::Directory(d) => d.set_info(FileRenameInformation { replace_if_exists: replace.into(), root_directory: 0, file_name: to_sp.as_str().into() }).await,
            _ => Ok(()),
        };
        Self::close_res(res).await;
        r.map_err(|e| map_smb(e, to))
    }

    async fn delete_any(&self, path: &str, directory: bool) -> Result<()> {
        let sp = to_smb_path(path)?;
        if sp.is_empty() {
            return Err(NdError::new(Code::PolicyDenied).with("rule", "smb_root").with("path", path));
        }
        let mut args = FileCreateArgs::make_open_existing(FileAccessMask::new().with_generic_read(true).with_delete(true));
        args.options = if directory {
            CreateOptions::new().with_directory_file(true).with_open_reparse_point(true)
        } else {
            CreateOptions::new().with_non_directory_file(true).with_open_reparse_point(true)
        };
        let res = self.open(&sp, &args, path).await?;
        let r = match &res {
            Resource::File(f) => f.set_info(FileDispositionInformation { delete_pending: true.into() }).await,
            Resource::Directory(d) => d.set_info(FileDispositionInformation { delete_pending: true.into() }).await,
            _ => Ok(()),
        };
        Self::close_res(res).await;
        r.map_err(|e| map_smb(e, path))
    }
}

#[async_trait]
impl Provider for SmbProvider {
    fn home(&self) -> String {
        self.home.clone()
    }

    async fn list(&self, path: &str) -> Result<Vec<RawEntry>> {
        self.query_dir(path, "*").await
    }

    async fn stat(&self, path: &str) -> Result<RawEntry> {
        let (parent, name) = split_parent(path);
        if name.is_empty() {
            return Ok(RawEntry { name: String::new(), kind: EntryKind::Dir, size: None, mtime_ms: None });
        }
        if !component_ok(&name) {
            return Err(NdError::new(Code::PolicyDenied).with("rule", "smb_name").with("path", path));
        }
        let _ = parent;
        let sp = to_smb_path(path)?;
        let mut args = FileCreateArgs::make_open_existing(FileAccessMask::new().with_generic_read(true));
        args.options = CreateOptions::new().with_open_reparse_point(true);
        let res = self.open(&sp, &args, path).await.map_err(|e| e.with("op", "stat-open"))?;
        let info = match &res {
            Resource::File(f) => f.query_info::<FileAllInformation>().await,
            Resource::Directory(d) => d.query_info::<FileAllInformation>().await,
            _ => Err(smb::Error::InvalidState("unexpected resource".into())),
        };
        Self::close_res(res).await;
        let info = info.map_err(|e| map_smb(e, path).with("op", "stat-info"))?;
        let a = &info.basic.file_attributes;
        let kind = if a.reparse_point() {
            EntryKind::Symlink
        } else if a.directory() || bool::from(info.standard.directory) {
            EntryKind::Dir
        } else {
            EntryKind::File
        };
        Ok(RawEntry {
            name,
            kind,
            size: if kind == EntryKind::File { Some(info.standard.end_of_file) } else { None },
            mtime_ms: filetime_ms(info.basic.last_write_time.is_zero(), info.basic.last_write_time.since_epoch()),
        })
    }

    async fn mkdir(&self, path: &str) -> Result<()> {
        let sp = to_smb_path(path)?;
        let args = FileCreateArgs::make_create_new(FileAttributes::new().with_directory(true), CreateOptions::new().with_directory_file(true));
        let res = self.open(&sp, &args, path).await.map_err(|e| e.with("op", "mkdir-open"))?;
        Self::close_res(res).await;
        Ok(())
    }

    async fn rename(&self, from: &str, to: &str) -> Result<()> {
        if super::exists(self, to).await? {
            return Err(NdError::new(Code::AlreadyExists).with("path", to));
        }
        self.rename_open(from, to, false).await
    }

    async fn delete_file(&self, path: &str) -> Result<()> {
        self.delete_any(path, false).await
    }

    async fn delete_dir(&self, path: &str) -> Result<()> {
        self.delete_any(path, true).await
    }

    async fn open_read(&self, path: &str, offset: u64) -> Result<Reader> {
        let sp = to_smb_path(path)?;
        let mut args = FileCreateArgs::make_open_existing(FileAccessMask::new().with_generic_read(true));
        args.options = CreateOptions::new().with_non_directory_file(true).with_open_reparse_point(true);
        let file = match self.open(&sp, &args, path).await? {
            Resource::File(f) => Arc::new(f),
            other => {
                Self::close_res(other).await;
                return Err(NdError::new(Code::InvalidArgument).with("path", path).detail("not a file"));
            }
        };
        // Pump file -> pipe in a task; dropping the reader (cancel/finish) ends the task and closes the file.
        let (mut tx, rx) = tokio::io::duplex(IO_CHUNK * 4);
        let f2 = file.clone();
        tokio::spawn(async move {
            let mut pos = offset;
            let mut buf = vec![0u8; IO_CHUNK];
            loop {
                match f2.read_at(&mut buf, pos).await {
                    Ok(0) => break,
                    Ok(n) => {
                        pos += n as u64;
                        if tx.write_all(&buf[..n]).await.is_err() {
                            break; // reader dropped
                        }
                    }
                    Err(e) => {
                        let _ = tx.shutdown().await;
                        drop(tx);
                        let _ = f2.close().await;
                        let _ = e; // surfaces as a short stream; the transfer layer verifies sizes
                        return;
                    }
                }
            }
            let _ = tx.shutdown().await;
            let _ = f2.close().await;
        });
        Ok(Box::new(rx))
    }

    async fn put(&self, path: &str, mut src: Reader, _size: u64, overwrite: bool, pr: &Progress) -> Result<()> {
        if !overwrite && super::exists(self, path).await? {
            return Err(NdError::new(Code::AlreadyExists).with("path", path));
        }
        let part = format!("{path}.nd-part");
        let part_sp = to_smb_path(&part)?;
        let _ = self.delete_any(&part, false).await; // stale partial from an earlier attempt
        let args = FileCreateArgs::make_create_new(FileAttributes::new(), CreateOptions::new().with_non_directory_file(true));
        let file = match self.open(&part_sp, &args, &part).await? {
            Resource::File(f) => f,
            other => {
                Self::close_res(other).await;
                return Err(NdError::new(Code::Io).with("path", path));
            }
        };
        let res: Result<()> = async {
            let mut buf = vec![0u8; IO_CHUNK];
            let mut pos: u64 = 0;
            loop {
                pr.check()?;
                let n = src.read(&mut buf).await?;
                if n == 0 {
                    break;
                }
                let mut done = 0;
                while done < n {
                    let w = pr.run(async { file.write_at(&buf[done..n], pos + done as u64).await.map_err(|e| map_smb(e, path)) }).await?;
                    if w == 0 {
                        return Err(NdError::new(Code::Io).with("path", path).detail("short write"));
                    }
                    done += w;
                }
                pos += n as u64;
                pr.report(n as u64);
            }
            let dest = to_smb_path(path)?;
            // atomic publish: rename the finished partial file over/into place (never replaces unless asked)
            file.set_info(FileRenameInformation { replace_if_exists: overwrite.into(), root_directory: 0, file_name: dest.as_str().into() })
                .await
                .map_err(|e| map_smb(e, path))?;
            Ok(())
        }
        .await;
        if res.is_err() {
            let _ = file.set_info(FileDispositionInformation { delete_pending: true.into() }).await;
        }
        let _ = file.close().await;
        res
    }

    async fn close(&self) {
        let _ = self.client.close().await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn paths() {
        assert_eq!(to_smb_path("/").unwrap(), "");
        assert_eq!(to_smb_path("/a/b.txt").unwrap(), "a\\b.txt");
        for bad in ["/a/con", "/a/NUL.txt", "/a/x:y", "/a/x\\y", "/a/x?", "/a/ends.", "/a/ends ", "/../x", "/a/./b"] {
            assert!(to_smb_path(bad).is_err(), "{bad}");
        }
    }
    #[test]
    fn parent() {
        assert_eq!(split_parent("/a/b"), ("/a".into(), "b".into()));
        assert_eq!(split_parent("/a"), ("/".into(), "a".into()));
    }
}

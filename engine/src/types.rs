use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum EntryKind {
    File,
    Dir,
    Symlink,
    Other,
}

/// Raw entry as returned by a provider (names are untrusted).
#[derive(Debug, Clone)]
pub struct RawEntry {
    pub name: String,
    pub kind: EntryKind,
    pub size: Option<u64>,
    pub mtime_ms: Option<i64>,
}

/// How strongly a version identifier proves "the remote object is unchanged".
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum VersionStrength {
    /// Server-issued content identifier (ETag / version id).
    Strong,
    /// Only size + mtime (coarse resolution possible): not proof of identity.
    Weak,
    /// Nothing usable.
    None,
}

/// Remote object identity used for conflict detection and pinned range reads.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RemoteVersion {
    pub size: Option<u64>,
    pub mtime_ms: Option<i64>,
    /// Granularity of `mtime_ms` as the protocol delivers it (SFTP/WebDAV: 1000, SMB: 1, S3: 1000).
    pub mtime_resolution_ms: u32,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub etag: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub version_id: Option<String>,
    pub strength: VersionStrength,
}

impl RemoteVersion {
    /// Identity from a plain stat: weak by definition.
    pub fn from_stat(e: &RawEntry, resolution_ms: u32) -> RemoteVersion {
        let strength = if e.size.is_some() && e.mtime_ms.is_some() { VersionStrength::Weak } else { VersionStrength::None };
        RemoteVersion { size: e.size, mtime_ms: e.mtime_ms, mtime_resolution_ms: resolution_ms, etag: None, version_id: None, strength }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct Entry {
    pub name: String,
    #[serde(rename = "ref")]
    pub ref_: String,
    pub kind: EntryKind,
    pub size: Option<u64>,
    pub mtime_ms: Option<i64>,
    pub safe_local_name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct S3Cfg {
    #[serde(default)]
    pub bucket: String,
    #[serde(default)]
    pub region: String,
    #[serde(default)]
    pub access_key: String,
    #[serde(default)]
    pub path_style: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Profile {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub protocol: String,
    #[serde(default)]
    pub host: String,
    #[serde(default)]
    pub port: u32,
    #[serde(default)]
    pub username: String,
    #[serde(default)]
    pub auth: String,
    #[serde(default)]
    pub base_path: String,
    #[serde(default)]
    pub tls: bool,
    #[serde(default)]
    pub s3: Option<S3Cfg>,
    /// SMB: share name (without slashes).
    #[serde(default)]
    pub share: String,
    /// SMB: "strict" (default: encryption required) or "relaxed" (explicit per-profile opt-in:
    /// encryption not imposed; SMB 3.1.1 and signing stay mandatory). See docs/adr/0001.
    #[serde(default)]
    pub smb_mode: String,
    #[serde(default)]
    pub has_secret: bool,
    #[serde(default)]
    pub color: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Secrets {
    #[serde(default)]
    pub password: Option<String>,
    #[serde(default)]
    pub private_key: Option<String>,
    #[serde(default)]
    pub passphrase: Option<String>,
    #[serde(default)]
    pub secret_key: Option<String>,
}

impl Secrets {
    pub fn is_empty(&self) -> bool {
        self.password.is_none() && self.private_key.is_none() && self.passphrase.is_none() && self.secret_key.is_none()
    }
    /// Merge: provided fields replace stored ones.
    pub fn merge(&mut self, mut o: Secrets) {
        if o.password.is_some() {
            self.password = o.password.take();
        }
        if o.private_key.is_some() {
            self.private_key = o.private_key.take();
        }
        if o.passphrase.is_some() {
            self.passphrase = o.passphrase.take();
        }
        if o.secret_key.is_some() {
            self.secret_key = o.secret_key.take();
        }
    }
}

impl Drop for Secrets {
    fn drop(&mut self) {
        use zeroize::Zeroize;
        if let Some(s) = self.password.as_mut() {
            s.zeroize();
        }
        if let Some(s) = self.private_key.as_mut() {
            s.zeroize();
        }
        if let Some(s) = self.passphrase.as_mut() {
            s.zeroize();
        }
        if let Some(s) = self.secret_key.as_mut() {
            s.zeroize();
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Pin {
    pub kind: String, // "ssh" | "tls"
    pub host: String,
    pub port: u32,
    pub value: String,
    #[serde(default = "yes")]
    pub verified: bool,
}
fn yes() -> bool {
    true
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Favorite {
    #[serde(rename = "ref")]
    pub ref_: String,
    pub name: String,
}

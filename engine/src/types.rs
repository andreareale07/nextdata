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

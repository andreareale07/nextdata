//! In-memory provider ("fake-fs"): used by the `fake` profile (UI demo/e2e) and by engine tests.
use super::{Progress, Provider, Reader, CHUNK};
use crate::error::{Code, NdError, Result};
use crate::pathguard::{basename, join, norm_ref, parent_of};
use crate::types::{EntryKind, RawEntry};
use async_trait::async_trait;
use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};
use tokio::io::AsyncReadExt;

#[derive(Clone)]
struct Node {
    kind: EntryKind,
    data: Vec<u8>,
    mtime_ms: i64,
}

pub struct FakeProvider {
    nodes: Arc<Mutex<BTreeMap<String, Node>>>,
    /// raw hostile names to inject in listings (tests)
    pub injected: Mutex<Vec<String>>,
}

fn now_ms() -> i64 {
    crate::dates::now_secs() * 1000
}

impl FakeProvider {
    pub fn new() -> Arc<FakeProvider> {
        let p = FakeProvider { nodes: Arc::new(Mutex::new(BTreeMap::new())), injected: Mutex::new(vec![]) };
        {
            let mut n = p.nodes.lock().unwrap();
            n.insert("/".into(), Node { kind: EntryKind::Dir, data: vec![], mtime_ms: now_ms() });
        }
        Arc::new(p)
    }

    /// Demo content for the `fake` profile.
    pub fn seeded() -> Arc<FakeProvider> {
        let p = Self::new();
        for d in ["/Documenti", "/Foto", "/Foto/2026", "/Backup", "/Vuota"] {
            p.put_dir(d);
        }
        p.put_file("/LEGGIMI.txt", b"NextData demo storage\n");
        p.put_file("/Documenti/relazione.txt", "Relazione di prova.\n".repeat(100).as_bytes());
        p.put_file("/Documenti/budget.csv", b"voce,importo\naffitto,900\n");
        p.put_file("/Foto/2026/gatto.bin", &vec![7u8; 300_000]);
        p.put_file("/Backup/archivio.tar", &vec![1u8; 2_500_000]);
        for i in 0..30 {
            p.put_file(&format!("/Foto/img_{i:03}.jpg"), &vec![i as u8; 1000 + i * 100]);
        }
        p
    }

    pub fn put_dir(&self, path: &str) {
        let path = norm_ref(path).unwrap();
        self.nodes.lock().unwrap().insert(path, Node { kind: EntryKind::Dir, data: vec![], mtime_ms: now_ms() });
    }
    pub fn put_file(&self, path: &str, data: &[u8]) {
        let path = norm_ref(path).unwrap();
        self.nodes.lock().unwrap().insert(path, Node { kind: EntryKind::File, data: data.to_vec(), mtime_ms: now_ms() });
    }
    pub fn put_link(&self, path: &str) {
        let path = norm_ref(path).unwrap();
        self.nodes.lock().unwrap().insert(path, Node { kind: EntryKind::Symlink, data: vec![], mtime_ms: now_ms() });
    }
    pub fn get_file(&self, path: &str) -> Option<Vec<u8>> {
        self.nodes.lock().unwrap().get(path).map(|n| n.data.clone())
    }
    pub fn exists_sync(&self, path: &str) -> bool {
        self.nodes.lock().unwrap().contains_key(path)
    }
}

#[async_trait]
impl Provider for FakeProvider {
    async fn list(&self, path: &str) -> Result<Vec<RawEntry>> {
        let n = self.nodes.lock().unwrap();
        match n.get(path) {
            None => return Err(NdError::new(Code::NotFound).with("path", path)),
            Some(x) if x.kind != EntryKind::Dir => return Err(NdError::invalid("not a directory")),
            _ => {}
        }
        let mut out: Vec<RawEntry> = n
            .iter()
            .filter(|(k, _)| k.as_str() != "/" && parent_of(k).as_deref() == Some(path))
            .map(|(k, v)| RawEntry {
                name: basename(k).to_string(),
                kind: v.kind,
                size: if v.kind == EntryKind::File { Some(v.data.len() as u64) } else { None },
                mtime_ms: Some(v.mtime_ms),
            })
            .collect();
        for bad in self.injected.lock().unwrap().iter() {
            out.push(RawEntry { name: bad.clone(), kind: EntryKind::File, size: Some(1), mtime_ms: None });
        }
        Ok(out)
    }

    async fn stat(&self, path: &str) -> Result<RawEntry> {
        let n = self.nodes.lock().unwrap();
        let v = n.get(path).ok_or_else(|| NdError::new(Code::NotFound).with("path", path))?;
        Ok(RawEntry {
            name: basename(path).to_string(),
            kind: v.kind,
            size: if v.kind == EntryKind::File { Some(v.data.len() as u64) } else { None },
            mtime_ms: Some(v.mtime_ms),
        })
    }

    async fn mkdir(&self, path: &str) -> Result<()> {
        let mut n = self.nodes.lock().unwrap();
        if n.contains_key(path) {
            return Err(NdError::new(Code::AlreadyExists).with("path", path));
        }
        let parent = parent_of(path).ok_or_else(|| NdError::invalid("root"))?;
        if n.get(&parent).map(|x| x.kind) != Some(EntryKind::Dir) {
            return Err(NdError::new(Code::NotFound).with("path", parent));
        }
        n.insert(path.to_string(), Node { kind: EntryKind::Dir, data: vec![], mtime_ms: now_ms() });
        Ok(())
    }

    async fn rename(&self, from: &str, to: &str) -> Result<()> {
        let mut n = self.nodes.lock().unwrap();
        if n.contains_key(to) {
            return Err(NdError::new(Code::AlreadyExists).with("path", to));
        }
        if !n.contains_key(from) {
            return Err(NdError::new(Code::NotFound).with("path", from));
        }
        let prefix = format!("{from}/");
        let keys: Vec<String> = n.keys().filter(|k| k.as_str() == from || k.starts_with(&prefix)).cloned().collect();
        for k in keys {
            let v = n.remove(&k).unwrap();
            let nk = format!("{to}{}", &k[from.len()..]);
            n.insert(nk, v);
        }
        Ok(())
    }

    async fn delete_file(&self, path: &str) -> Result<()> {
        let mut n = self.nodes.lock().unwrap();
        match n.get(path) {
            None => Err(NdError::new(Code::NotFound).with("path", path)),
            Some(v) if v.kind == EntryKind::Dir => Err(NdError::invalid("is a directory")),
            _ => {
                n.remove(path);
                Ok(())
            }
        }
    }

    async fn delete_dir(&self, path: &str) -> Result<()> {
        let mut n = self.nodes.lock().unwrap();
        let prefix = if path == "/" { "/".to_string() } else { format!("{path}/") };
        if n.keys().any(|k| k != path && k.starts_with(&prefix)) {
            return Err(NdError::invalid("directory not empty"));
        }
        if n.remove(path).is_none() {
            return Err(NdError::new(Code::NotFound).with("path", path));
        }
        Ok(())
    }

    async fn open_read(&self, path: &str, offset: u64) -> Result<Reader> {
        let n = self.nodes.lock().unwrap();
        let v = n.get(path).ok_or_else(|| NdError::new(Code::NotFound).with("path", path))?;
        if v.kind != EntryKind::File {
            return Err(NdError::invalid("not a file"));
        }
        let off = (offset as usize).min(v.data.len());
        Ok(Box::new(std::io::Cursor::new(v.data[off..].to_vec())))
    }

    async fn put(&self, path: &str, mut src: Reader, _size: u64, overwrite: bool, pr: &Progress) -> Result<()> {
        if !overwrite && self.nodes.lock().unwrap().contains_key(path) {
            return Err(NdError::new(Code::AlreadyExists).with("path", path));
        }
        let mut buf = Vec::new();
        let mut chunk = vec![0u8; CHUNK];
        loop {
            pr.check()?;
            let k = src.read(&mut chunk).await?;
            if k == 0 {
                break;
            }
            buf.extend_from_slice(&chunk[..k]);
            pr.report(k as u64);
        }
        let parent = parent_of(path).ok_or_else(|| NdError::invalid("root"))?;
        let mut n = self.nodes.lock().unwrap();
        if n.get(&parent).map(|x| x.kind) != Some(EntryKind::Dir) {
            return Err(NdError::new(Code::NotFound).with("path", parent));
        }
        if !overwrite && n.contains_key(path) {
            return Err(NdError::new(Code::AlreadyExists).with("path", path));
        }
        n.insert(path.to_string(), Node { kind: EntryKind::File, data: buf, mtime_ms: now_ms() });
        let _ = join; // keep helper import used in all cfgs
        Ok(())
    }
}

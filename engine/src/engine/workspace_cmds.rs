//! Local workspace for opened remote files: cache, change detection by content hash, save, recovery.
//! See docs/open-edit.md. Principle: a local version is never deleted until the remote copy is verified.
use super::*;
use crate::crypto::Sha256Stream;
use serde::Serialize;
use std::path::{Path, PathBuf};
use tokio::io::AsyncReadExt;

/// Copies of the downloaded version are kept (for diff/recovery) only up to this size.
const BASE_COPY_MAX: u64 = 64 * 1024 * 1024;
const DEFAULT_CACHE_LIMIT: u64 = 500 * 1024 * 1024;
const DEFAULT_IDLE_SECS: i64 = 24 * 3600;
const DEFAULT_OPEN_IDLE_SECS: i64 = 7 * 24 * 3600;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DocState {
    Downloading,
    /// Local == the version downloaded or last saved (verified).
    Clean,
    /// Local differs from the base; a snapshot exists.
    Modified,
    Saving,
    /// Upload failed with the original preserved: retry.
    SaveFailed,
    /// The remote result of a save is unknown (crash/lost answer): reconcile first.
    Uncertain,
    /// The remote changed meanwhile (or its identity cannot be established).
    Conflict,
    /// Profile deleted/unreachable while work was pending.
    Orphaned,
    /// A version supplied by the user (import) waits to be saved.
    Imported,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Doc {
    pub id: String,
    pub profile_id: String,
    pub remote_ref: String,
    /// Local file name inside `current/`.
    pub name: String,
    pub state: DocState,
    pub base_version: RemoteVersion,
    pub base_sha: String,
    pub size: u64,
    pub has_base: bool,
    pub modified_sha: Option<String>,
    /// Snapshot file names (`snap-<n>`), oldest first; the last one is what gets saved.
    pub snaps: Vec<String>,
    pub last_access: i64,
    /// An external app may still hold/modify the file.
    pub ext_open: bool,
    pub last_error: Option<String>,
    pub created: i64,
}

pub struct WorkspaceDir {
    root: PathBuf,
    key: [u8; 32],
    lock: Mutex<()>,
}

fn aad(id: &str) -> Vec<u8> {
    format!("nextdata-workspace-v1|{id}").into_bytes()
}

fn now() -> i64 {
    crate::dates::now_secs()
}

fn dir_size(p: &Path) -> u64 {
    let mut t = 0;
    if let Ok(rd) = std::fs::read_dir(p) {
        for e in rd.flatten() {
            match e.metadata() {
                Ok(m) if m.is_dir() => t += dir_size(&e.path()),
                Ok(m) => t += m.len(),
                _ => {}
            }
        }
    }
    t
}

impl WorkspaceDir {
    pub fn open(data_dir: &str, pmk: &[u8]) -> Result<WorkspaceDir> {
        let root = PathBuf::from(data_dir).join("workspace");
        std::fs::create_dir_all(&root)?;
        Ok(WorkspaceDir { root, key: crypto::derive(pmk, "workspace"), lock: Mutex::new(()) })
    }

    fn valid_id(id: &str) -> Result<()> {
        if id.is_empty() || !id.chars().all(|c| c.is_ascii_alphanumeric()) {
            return Err(NdError::invalid("doc_id"));
        }
        Ok(())
    }
    pub fn dir(&self, id: &str) -> Result<PathBuf> {
        Self::valid_id(id)?;
        Ok(self.root.join(id))
    }
    pub fn load(&self, id: &str) -> Result<Doc> {
        let f = self.dir(id)?.join("doc.nd");
        let blob = std::fs::read(&f).map_err(|_| NdError::new(Code::NotFound).with("doc", id))?;
        let plain = crypto::open(&self.key, &aad(id), &blob).map_err(|_| NdError::new(Code::ImportInvalid).with("doc", id).detail("workspace record unreadable"))?;
        serde_json::from_slice(&plain).map_err(NdError::from)
    }
    pub fn store(&self, d: &Doc) -> Result<()> {
        let dir = self.dir(&d.id)?;
        std::fs::create_dir_all(&dir)?;
        let blob = crypto::seal(&self.key, &aad(&d.id), &serde_json::to_vec(d).map_err(NdError::internal)?);
        let tmp = dir.join("doc.nd.tmp");
        std::fs::write(&tmp, blob)?;
        std::fs::rename(&tmp, dir.join("doc.nd"))?;
        Ok(())
    }
    /// Every readable doc; ids whose record cannot be read are returned separately (never deleted automatically).
    pub fn all(&self) -> (Vec<Doc>, Vec<String>) {
        let (mut docs, mut bad) = (vec![], vec![]);
        if let Ok(rd) = std::fs::read_dir(&self.root) {
            for e in rd.flatten() {
                let id = e.file_name().to_string_lossy().to_string();
                if !e.path().is_dir() {
                    continue;
                }
                match self.load(&id) {
                    Ok(d) => docs.push(d),
                    Err(_) => bad.push(id),
                }
            }
        }
        docs.sort_by(|a, b| a.created.cmp(&b.created).then(a.id.cmp(&b.id)));
        (docs, bad)
    }
    fn find(&self, profile: &str, r: &str) -> Option<Doc> {
        self.all().0.into_iter().find(|d| d.profile_id == profile && d.remote_ref == r && d.state != DocState::Orphaned)
    }
    fn current_path(&self, d: &Doc) -> Result<PathBuf> {
        Ok(self.dir(&d.id)?.join("current").join(&d.name))
    }

    /// Startup recovery: nothing precious is deleted. An interrupted download holds no user data; an
    /// interrupted save becomes `Uncertain` (its replace journal decides at reconnect).
    pub fn startup_recovery(&self) -> Vec<String> {
        let _g = self.lock.lock().unwrap();
        let mut notes = vec![];
        for mut d in self.all().0 {
            match d.state {
                DocState::Downloading => {
                    if let Ok(dir) = self.dir(&d.id) {
                        let _ = std::fs::remove_dir_all(dir);
                    }
                    notes.push(format!("{}: interrupted download discarded", d.id));
                }
                DocState::Saving => {
                    d.state = DocState::Uncertain;
                    d.last_error = Some("interrupted".into());
                    let _ = self.store(&d);
                    notes.push(format!("{}: interrupted save -> uncertain", d.id));
                }
                _ => {}
            }
        }
        notes
    }

    /// Profile removed: pending work becomes Orphaned (kept, exportable); clean copies are dropped.
    pub fn orphan_profile(&self, profile: &str) {
        let _g = self.lock.lock().unwrap();
        for mut d in self.all().0.into_iter().filter(|d| d.profile_id == profile) {
            if d.state == DocState::Clean && !d.ext_open {
                if let Ok(dir) = self.dir(&d.id) {
                    let _ = std::fs::remove_dir_all(dir);
                }
            } else {
                d.state = DocState::Orphaned;
                let _ = self.store(&d);
            }
        }
    }

    /// Only unmodified, verified-clean copies nobody is using may be dropped automatically.
    fn evictable(d: &Doc, idle: i64, open_idle: i64) -> bool {
        let age = now() - d.last_access;
        d.state == DocState::Clean && ((!d.ext_open && age >= idle) || (d.ext_open && age >= open_idle))
    }

    fn usage(&self) -> (u64, u64, u64, usize) {
        let (docs, _) = self.all();
        let (mut used, mut evict, mut prot) = (0u64, 0u64, 0u64);
        for d in &docs {
            let s = self.dir(&d.id).map(|p| dir_size(&p)).unwrap_or(0);
            used += s;
            if Self::evictable(d, 0, 0) && !d.ext_open {
                evict += s;
            } else {
                prot += s;
            }
        }
        (used, evict, prot, docs.len())
    }

    fn purge(&self, idle: i64, open_idle: i64, need: u64) -> u64 {
        let _g = self.lock.lock().unwrap();
        let (mut docs, _) = self.all();
        docs.retain(|d| Self::evictable(d, idle, open_idle));
        docs.sort_by_key(|d| d.last_access); // least recently used first
        let mut freed = 0u64;
        for d in docs {
            if need > 0 && freed >= need {
                break;
            }
            if let Ok(dir) = self.dir(&d.id) {
                freed += dir_size(&dir);
                let _ = std::fs::remove_dir_all(dir);
            }
        }
        freed
    }
}

async fn file_sha(p: &Path) -> Result<(String, u64)> {
    let mut f = tokio::fs::File::open(p).await?;
    let mut h = Sha256Stream::new();
    let mut buf = vec![0u8; 256 * 1024];
    let mut n_total = 0u64;
    loop {
        let n = f.read(&mut buf).await?;
        if n == 0 {
            break;
        }
        n_total += n as u64;
        h.update(&buf[..n]);
    }
    Ok((h.finish_hex(), n_total))
}

fn same_identity(a: &RemoteVersion, b: &RemoteVersion) -> bool {
    if a.etag.is_some() || b.etag.is_some() || a.version_id.is_some() || b.version_id.is_some() {
        return a.etag == b.etag && a.version_id == b.version_id;
    }
    a.size == b.size && a.mtime_ms == b.mtime_ms && a.size.is_some() && a.mtime_ms.is_some()
}

fn doc_json(d: &Doc, ws: &WorkspaceDir) -> Value {
    let local = ws.current_path(d).map(|p| p.to_string_lossy().to_string()).unwrap_or_default();
    let mut v = serde_json::to_value(d).unwrap_or(Value::Null);
    v["local_path"] = json!(local);
    v["needs_attention"] = json!(matches!(d.state, DocState::Modified | DocState::SaveFailed | DocState::Uncertain | DocState::Conflict | DocState::Orphaned | DocState::Imported | DocState::Saving));
    v
}

impl Inner {
    fn ws(&self) -> Result<Arc<WorkspaceDir>> {
        self.workspace.lock().unwrap().clone().ok_or_else(|| NdError::new(Code::NotInitialized))
    }

    fn doc_session_ok(&self, s: &Session, d: &Doc) -> Result<()> {
        if s.profile_id != d.profile_id {
            return Err(NdError::invalid("session does not belong to the document's profile"));
        }
        Ok(())
    }

    /// Downloads `r` into `<doc>/dl/` and returns the file path inside it.
    async fn fetch_into(self: &Arc<Self>, op: u64, token: CancellationToken, s: &Session, r: &str, dl: &Path) -> Result<PathBuf> {
        let _ = std::fs::remove_dir_all(dl);
        std::fs::create_dir_all(dl)?;
        let rep = self.reporter(op, token, true);
        let out = transfer::download(s.provider.as_ref(), std::slice::from_ref(&r.to_string()), &dl.to_string_lossy(), Collision::Fail, &rep).await;
        rep.flush();
        let paths = out?;
        paths.first().map(PathBuf::from).ok_or_else(|| NdError::internal("download produced no file"))
    }

    pub(super) async fn cmd_open_prepare(self: &Arc<Self>, op: u64, token: CancellationToken, a: &Value) -> Result<Value> {
        let ws = self.ws()?;
        let s = self.session(&req_str(a, "session")?)?;
        let r = norm_ref(&req_str(a, "ref")?)?;
        let limit = a.get("cache_limit").and_then(|v| v.as_u64()).unwrap_or(DEFAULT_CACHE_LIMIT);
        let pr = Progress { cb: Arc::new(|_| {}), cancel: token.clone() };
        let ver = pr.run(async { s.provider.version(&r).await }).await?;

        let mut existing = ws.find(&s.profile_id, &r);
        if let Some(d) = existing.as_mut() {
            // first look at what is on disk: a "clean" doc may have been edited without us noticing yet
            if matches!(d.state, DocState::Clean) {
                let cur = ws.current_path(d)?;
                if cur.is_file() {
                    let (sha, _) = file_sha(&cur).await?;
                    if sha != d.base_sha {
                        let _g = ws.lock.lock().unwrap();
                        d.state = DocState::Modified;
                        ws.store(d)?;
                    }
                }
            }
            let remote_same = same_identity(&ver, &d.base_version);
            if d.state != DocState::Clean || (remote_same && ws.current_path(d)?.is_file()) {
                let _g = ws.lock.lock().unwrap();
                d.last_access = now();
                ws.store(d)?;
                return Ok(json!({"doc": doc_json(d, &ws), "from_cache": true, "remote_changed": !remote_same, "version": ver}));
            }
            // clean and the remote moved on (or the local file vanished): refresh below, same doc id
        }

        // make room (evictable only); protected data is never removed to fit
        let want = ver.size.unwrap_or(0);
        let (used, _, _, _) = ws.usage();
        let mut over = used + want > limit;
        if over {
            ws.purge(0, DEFAULT_OPEN_IDLE_SECS, used + want - limit);
            over = ws.usage().0 + want > limit;
        }

        let id = existing.as_ref().map(|d| d.id.clone()).unwrap_or_else(|| crypto::random_hex(8));
        let base_dir = ws.dir(&id)?;
        let name = existing.as_ref().map(|d| d.name.clone()).unwrap_or_else(|| pathguard::safe_local_name(pathguard::basename(&r)));
        let mut doc = existing.clone().unwrap_or(Doc {
            id: id.clone(),
            profile_id: s.profile_id.clone(),
            remote_ref: r.clone(),
            name: name.clone(),
            state: DocState::Downloading,
            base_version: ver.clone(),
            base_sha: String::new(),
            size: 0,
            has_base: false,
            modified_sha: None,
            snaps: vec![],
            last_access: now(),
            ext_open: false,
            last_error: None,
            created: now(),
        });
        if existing.is_none() {
            let _g = ws.lock.lock().unwrap();
            ws.store(&doc)?;
        }
        let dl = base_dir.join("dl");
        let fetched = match self.fetch_into(op, token, &s, &r, &dl).await {
            Ok(p) => p,
            Err(e) => {
                let _ = std::fs::remove_dir_all(&dl);
                if existing.is_none() {
                    let _ = std::fs::remove_dir_all(&base_dir);
                }
                return Err(e);
            }
        };
        let (sha, size) = file_sha(&fetched).await?;
        let cur_dir = base_dir.join("current");
        std::fs::create_dir_all(&cur_dir)?;
        let target = cur_dir.join(&name);
        if target.exists() {
            let _ = std::fs::remove_file(&target); // clean doc: replaced by the fresh download
        }
        std::fs::rename(&fetched, &target)?;
        let _ = std::fs::remove_dir_all(&dl);
        let mut has_base = false;
        if size <= BASE_COPY_MAX {
            has_base = std::fs::copy(&target, base_dir.join("base")).is_ok();
        }
        {
            let _g = ws.lock.lock().unwrap();
            doc.state = DocState::Clean;
            doc.base_version = ver.clone();
            doc.base_sha = sha;
            doc.size = size;
            doc.has_base = has_base;
            doc.modified_sha = None;
            doc.last_access = now();
            ws.store(&doc)?;
        }
        Ok(json!({"doc": doc_json(&doc, &ws), "from_cache": false, "remote_changed": false, "over_quota": over, "version": ver}))
    }

    pub(super) async fn cmd_open_check(self: &Arc<Self>, a: &Value) -> Result<Value> {
        let ws = self.ws()?;
        let mut d = ws.load(&req_str(a, "doc_id")?)?;
        let stable_ms = a.get("stable_ms").and_then(|v| v.as_u64()).unwrap_or(1000);
        let accept_empty = a.get("accept_empty").and_then(|v| v.as_bool()).unwrap_or(false);
        let cur = ws.current_path(&d)?;
        // other files next to the document: copies made by an app ("name (1).ext"): never uploaded on their own
        let mut copies = vec![];
        if let Ok(rd) = std::fs::read_dir(cur.parent().unwrap_or(Path::new("."))) {
            for e in rd.flatten() {
                let n = e.file_name().to_string_lossy().to_string();
                if n != d.name && e.path().is_file() {
                    copies.push(n);
                }
            }
        }
        if !matches!(d.state, DocState::Clean | DocState::Modified | DocState::SaveFailed | DocState::Conflict) {
            return Ok(json!({"doc": doc_json(&d, &ws), "changed": d.modified_sha.is_some(), "copies": copies, "skipped": true}));
        }
        let m1 = match std::fs::symlink_metadata(&cur) {
            Ok(m) if m.is_file() => m,
            _ => return Ok(json!({"doc": doc_json(&d, &ws), "missing": true, "copies": copies, "has_base": d.has_base})),
        };
        if stable_ms > 0 {
            tokio::time::sleep(Duration::from_millis(stable_ms)).await;
            let m2 = std::fs::symlink_metadata(&cur)?;
            if m1.len() != m2.len() || m1.modified().ok() != m2.modified().ok() {
                return Ok(json!({"doc": doc_json(&d, &ws), "unstable": true, "copies": copies}));
            }
        }
        // read through a copy so a writer replacing the file meanwhile cannot tear what we hash and keep
        let dir = ws.dir(&d.id)?;
        let tmp = dir.join("snap.tmp");
        if let Err(e) = std::fs::copy(&cur, &tmp) {
            // locked by an app that holds it exclusively: wait and retry later
            return Ok(json!({"doc": doc_json(&d, &ws), "locked": true, "detail": e.to_string(), "copies": copies}));
        }
        let (sha, size) = file_sha(&tmp).await?;
        let _g = ws.lock.lock().unwrap();
        if sha == d.base_sha {
            let _ = std::fs::remove_file(&tmp);
            if d.state == DocState::Modified {
                d.state = DocState::Clean; // reverted to the saved content
                d.modified_sha = None;
                for sn in d.snaps.drain(..) {
                    let _ = std::fs::remove_file(dir.join(sn));
                }
                ws.store(&d)?;
            }
            return Ok(json!({"doc": doc_json(&d, &ws), "changed": false, "copies": copies}));
        }
        if size == 0 && d.size > 0 && !accept_empty {
            let _ = std::fs::remove_file(&tmp);
            return Ok(json!({"doc": doc_json(&d, &ws), "changed": true, "suspicious": "empty", "copies": copies}));
        }
        if d.modified_sha.as_deref() != Some(sha.as_str()) {
            let mut n = d.snaps.len() + 1;
            let mut name2 = format!("snap-{n}");
            while dir.join(&name2).exists() {
                n += 1;
                name2 = format!("snap-{n}");
            }
            std::fs::rename(&tmp, dir.join(&name2))?;
            d.snaps.push(name2);
            d.modified_sha = Some(sha);
        } else {
            let _ = std::fs::remove_file(&tmp);
        }
        if d.state == DocState::Clean {
            d.state = DocState::Modified;
        }
        ws.store(&d)?;
        Ok(json!({"doc": doc_json(&d, &ws), "changed": true, "copies": copies}))
    }

    pub(super) async fn cmd_open_import(self: &Arc<Self>, a: &Value) -> Result<Value> {
        let ws = self.ws()?;
        let mut d = ws.load(&req_str(a, "doc_id")?)?;
        let src = PathBuf::from(req_str(a, "local_path")?);
        let md = std::fs::symlink_metadata(&src)?;
        if !md.is_file() {
            return Err(NdError::invalid("local_path"));
        }
        let dir = ws.dir(&d.id)?;
        let tmp = dir.join("snap.tmp");
        std::fs::copy(&src, &tmp)?;
        let (sha, _) = file_sha(&tmp).await?;
        if sha == d.base_sha {
            let _ = std::fs::remove_file(&tmp);
            return Err(NdError::new(Code::InvalidArgument).with("reason", "identical_to_base"));
        }
        let _g = ws.lock.lock().unwrap();
        let mut n = d.snaps.len() + 1;
        let mut name = format!("snap-{n}");
        while dir.join(&name).exists() {
            n += 1;
            name = format!("snap-{n}");
        }
        std::fs::rename(&tmp, dir.join(&name))?;
        d.snaps.push(name);
        d.modified_sha = Some(sha);
        if !matches!(d.state, DocState::Conflict | DocState::Saving | DocState::Uncertain) {
            d.state = DocState::Imported;
        }
        ws.store(&d)?;
        Ok(json!({"doc": doc_json(&d, &ws)}))
    }

    pub(super) async fn cmd_open_save(self: &Arc<Self>, token: CancellationToken, a: &Value) -> Result<Value> {
        let ws = self.ws()?;
        let s = self.session(&req_str(a, "session")?)?;
        let id = req_str(a, "doc_id")?;
        let mut d = ws.load(&id)?;
        self.doc_session_ok(&s, &d)?;
        let resolution = opt_str(a, "resolution").unwrap_or_else(|| "normal".into());
        match d.state {
            DocState::Uncertain => return Err(NdError::new(Code::Conflict).with("reason", "pending_recovery")),
            DocState::Saving => return Err(NdError::new(Code::Conflict).with("reason", "replace_in_progress")),
            DocState::Downloading => return Err(NdError::invalid("downloading")),
            _ => {}
        }
        // pick up the newest local content unless a user-supplied version is waiting
        if d.state != DocState::Imported {
            let chk = self.cmd_open_check(&json!({"doc_id": id, "stable_ms": 0})).await?;
            if chk.get("suspicious").is_some() || chk.get("locked").is_some() || chk.get("missing").is_some() {
                return Err(NdError::new(Code::Conflict).with("reason", "local_file_not_ready").with("detail", chk.get("suspicious").cloned().unwrap_or(Value::Null)));
            }
            d = ws.load(&id)?;
            if d.state == DocState::Clean {
                return Err(NdError::new(Code::InvalidArgument).with("reason", "nothing_to_save"));
            }
        }
        let dir = ws.dir(&id)?;
        let snap_name = d.snaps.last().cloned().ok_or_else(|| NdError::internal("no snapshot"))?;
        let snap = dir.join(&snap_name);
        let saved_sha = d.modified_sha.clone().unwrap_or_default();
        {
            let _g = ws.lock.lock().unwrap();
            d.state = DocState::Saving;
            ws.store(&d)?;
        }
        let mut args = json!({
            "session": s_id(a)?, "ref": d.remote_ref, "local_path": snap.to_string_lossy(),
            "expect_version": d.base_version, "base_sha256": d.base_sha, "resolution": resolution,
        });
        for k in ["confirm_unverified", "recovery_dir"] {
            if let Some(v) = a.get(k) {
                args[k] = v.clone();
            }
        }
        let res = self.cmd_replace(token, &args).await;
        // what the app may have written while we uploaded (hashed before taking the lock)
        let cur_now = match ws.current_path(&d) {
            Ok(p) if p.is_file() => file_sha(&p).await.ok().map(|x| x.0),
            _ => None,
        };
        let _g = ws.lock.lock().unwrap();
        d = ws.load(&id)?;
        match res {
            Ok(v) => {
                let ver: Option<RemoteVersion> = v.get("version").and_then(|x| serde_json::from_value(x.clone()).ok());
                if resolution == "copy" {
                    // the edit lives next to the original now: this document follows the copy
                    if let Some(r) = v["entry"]["ref"].as_str() {
                        d.remote_ref = r.to_string();
                    }
                }
                if let Some(ver) = ver {
                    d.base_version = ver;
                }
                let old_base = d.base_sha.clone();
                d.base_sha = saved_sha.clone();
                d.size = std::fs::metadata(&snap).map(|m| m.len()).unwrap_or(d.size);
                if d.has_base {
                    d.has_base = std::fs::copy(&snap, dir.join("base")).is_ok();
                }
                for sn in d.snaps.drain(..) {
                    let _ = std::fs::remove_file(dir.join(sn)); // verified on the remote: safe to drop
                }
                d.modified_sha = None;
                d.last_error = None;
                d.state = DocState::Clean;
                // the app may have saved again while we uploaded: do not call that clean
                if cur_now.as_deref().is_some_and(|c| c == old_base) && cur_now.as_deref() != Some(d.base_sha.as_str()) {
                    // the local file still holds the old base (an imported version was saved): it follows the saved content
                    if let Ok(cp) = ws.current_path(&d) {
                        let _ = std::fs::copy(&snap, &cp);
                    }
                } else if cur_now.as_deref().is_some_and(|c| c != d.base_sha) {
                    d.state = DocState::Modified;
                }
                ws.store(&d)?;
                Ok(json!({"doc": doc_json(&d, &ws), "saved": v}))
            }
            Err(e) => {
                d.state = match e.code {
                    Code::Conflict => DocState::Conflict,
                    Code::SaveUncertain => DocState::Uncertain,
                    _ => DocState::SaveFailed,
                };
                d.last_error = Some(format!("{:?}", e.code));
                ws.store(&d)?;
                Err(e)
            }
        }
    }

    pub(super) fn cmd_open_list(&self, a: &Value) -> Result<Value> {
        let ws = self.ws()?;
        let only = opt_str(a, "profile_id");
        let (docs, bad) = ws.all();
        let list: Vec<Value> = docs.iter().filter(|d| only.as_deref().is_none_or(|p| p == d.profile_id)).map(|d| doc_json(d, &ws)).collect();
        Ok(json!({"docs": list, "unreadable": bad}))
    }

    pub(super) fn cmd_open_mark(&self, a: &Value, release: bool) -> Result<Value> {
        let ws = self.ws()?;
        let _g = ws.lock.lock().unwrap();
        let mut d = ws.load(&req_str(a, "doc_id")?)?;
        d.ext_open = !release;
        d.last_access = now();
        ws.store(&d)?;
        Ok(json!({"doc": doc_json(&d, &ws)}))
    }

    /// Explicit user decision only.
    pub(super) fn cmd_open_discard(&self, a: &Value) -> Result<Value> {
        if a.get("confirm").and_then(|v| v.as_bool()) != Some(true) {
            return Err(NdError::invalid("confirm"));
        }
        let ws = self.ws()?;
        let _g = ws.lock.lock().unwrap();
        let d = ws.load(&req_str(a, "doc_id")?)?;
        std::fs::remove_dir_all(ws.dir(&d.id)?)?;
        Ok(json!({}))
    }

    /// Copies the local work of a document (current file + snapshots) to a folder the user chose.
    pub(super) fn cmd_open_export(&self, a: &Value) -> Result<Value> {
        let ws = self.ws()?;
        let d = ws.load(&req_str(a, "doc_id")?)?;
        let dest = PathBuf::from(req_str(a, "dest_dir")?);
        if !dest.is_dir() {
            return Err(NdError::new(Code::NotFound).with("path", dest.to_string_lossy().to_string()));
        }
        let dir = ws.dir(&d.id)?;
        let mut out = vec![];
        let mut sources = vec![(ws.current_path(&d)?, d.name.clone())];
        let (stem, ext) = match d.name.rfind('.') {
            Some(k) if k > 0 => (d.name[..k].to_string(), d.name[k..].to_string()),
            _ => (d.name.clone(), String::new()),
        };
        for (i, sn) in d.snaps.iter().enumerate() {
            sources.push((dir.join(sn), format!("{stem} (versione NextData {}){ext}", i + 1)));
        }
        for (src, name) in sources {
            if !src.is_file() {
                continue;
            }
            let mut target = dest.join(&name);
            let mut n = 1;
            while target.exists() {
                target = dest.join(pathguard::numbered(&name, n));
                n += 1;
            }
            std::fs::copy(&src, &target)?;
            out.push(target.to_string_lossy().to_string());
        }
        Ok(json!({"exported": out}))
    }

    pub(super) fn cmd_cache_stats(&self) -> Result<Value> {
        let ws = self.ws()?;
        let (used, evict, prot, n) = ws.usage();
        Ok(json!({"used": used, "evictable": evict, "protected": prot, "docs": n, "limit": DEFAULT_CACHE_LIMIT}))
    }

    pub(super) fn cmd_cache_purge(&self, a: &Value) -> Result<Value> {
        let ws = self.ws()?;
        let idle = a.get("max_idle_secs").and_then(|v| v.as_i64()).unwrap_or(DEFAULT_IDLE_SECS);
        let open_idle = a.get("open_max_idle_secs").and_then(|v| v.as_i64()).unwrap_or(DEFAULT_OPEN_IDLE_SECS);
        Ok(json!({"freed": ws.purge(idle, open_idle, 0)}))
    }
}

fn s_id(a: &Value) -> Result<String> {
    req_str(a, "session")
}

impl Inner {
    /// After a restart/reconnect: settle documents left `Uncertain` (crash or lost answer during a save).
    /// First reconciles the replace journals of the profile (remote state decides), then looks at what the
    /// remote holds now: the saved snapshot -> Clean; something else -> SaveFailed (a later save re-checks
    /// conflicts); nothing / unreachable -> stays Uncertain.
    pub(super) async fn cmd_open_recover(self: &Arc<Self>, a: &Value) -> Result<Value> {
        let ws = self.ws()?;
        let s = self.session(&req_str(a, "session")?)?;
        let journals = self.cmd_replace_recover(a).await?;
        let mut out = vec![];
        for d in ws.all().0.into_iter().filter(|d| d.profile_id == s.profile_id && d.state == DocState::Uncertain) {
            let dir = ws.dir(&d.id)?;
            let (Some(snap), Some(sha)) = (d.snaps.last().map(|n| dir.join(n)), d.modified_sha.clone()) else { continue };
            let size = std::fs::metadata(&snap).map(|m| m.len()).unwrap_or(0);
            let verdict = match crate::replace::matches(s.provider.as_ref(), &d.remote_ref, size, &sha).await {
                Ok(Some(_)) => "saved",
                Ok(None) => match s.provider.stat(&d.remote_ref).await {
                    Ok(_) => "not_saved",
                    Err(e) if e.code == Code::NotFound => "remote_missing",
                    Err(_) => "unreachable",
                },
                Err(_) => "unreachable",
            };
            let ver = if verdict == "saved" { s.provider.version(&d.remote_ref).await.ok() } else { None };
            let _g = ws.lock.lock().unwrap();
            let mut d = ws.load(&d.id)?;
            match verdict {
                "saved" => {
                    if let Some(v) = ver {
                        d.base_version = v;
                    }
                    d.base_sha = sha;
                    d.size = size;
                    if d.has_base {
                        d.has_base = std::fs::copy(&snap, dir.join("base")).is_ok();
                    }
                    for sn in d.snaps.drain(..) {
                        let _ = std::fs::remove_file(dir.join(sn));
                    }
                    d.modified_sha = None;
                    d.state = DocState::Clean;
                    d.last_error = None;
                }
                "not_saved" => {
                    d.state = DocState::SaveFailed;
                    d.last_error = Some("save_not_applied".into());
                }
                "remote_missing" => {
                    d.state = DocState::Conflict;
                    d.last_error = Some("remote_missing".into());
                }
                _ => {}
            }
            ws.store(&d)?;
            out.push(json!({"doc_id": d.id, "verdict": verdict, "state": d.state}));
        }
        Ok(json!({"journals": journals["recovered"], "docs": out}))
    }
}

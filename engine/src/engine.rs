//! Engine core: command dispatcher, sessions, prompts, profiles. Owns every decision;
//! the bridge only moves JSON in and out.
use crate::crypto;
use crate::error::{Code, NdError, Result};
use crate::pathguard::{self, breadcrumbs, join, norm_ref, parent_of, validate_user_name};
use crate::policy;
use crate::provider::fake::FakeProvider;
use crate::provider::s3::S3Provider;
use crate::provider::sftp::SftpProvider;
use crate::provider::webdav::WebDavProvider;
use crate::provider::{CertInfo, Progress, Provider, Trust};
use crate::store::Store;
use crate::transfer::{self, Collision, Reporter};
use crate::types::*;
use async_trait::async_trait;
use base64::Engine as _;
use serde::Deserialize;
use serde_json::{json, Map, Value};
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::sync::oneshot;
use tokio_util::sync::CancellationToken;

pub const API_VERSION: u32 = 1;
const B64: base64::engine::GeneralPurpose = base64::engine::general_purpose::STANDARD;
const EXPORT_MAGIC: &[u8; 5] = b"NDPF1";
const IMPORT_MAX: usize = 4 * 1024 * 1024;
const PAGE_DEFAULT: usize = 200;
const PAGE_MAX: usize = 500;

pub type Sink = Arc<dyn Fn(String) + Send + Sync>;

struct PromptReply {
    accept: bool,
    value: Option<String>,
    replace: bool,
}

struct ListCache {
    ref_: String,
    sort: String,
    desc: bool,
    entries: Arc<Vec<Entry>>,
    anomalies: usize,
}

struct Session {
    profile_id: String,
    provider: Arc<dyn Provider>,
    cache: Mutex<Option<ListCache>>,
}

pub struct Inner {
    sink: Mutex<Option<Sink>>,
    store: Mutex<Option<Store>>,
    ops: Mutex<HashMap<u64, CancellationToken>>,
    prompts: Mutex<HashMap<String, (u64, oneshot::Sender<PromptReply>)>>,
    sessions: Mutex<HashMap<String, Arc<Session>>>,
    counter: AtomicU64,
}

pub struct Engine {
    rt: tokio::runtime::Runtime,
    pub inner: Arc<Inner>,
}

fn opt_str(a: &Value, k: &str) -> Option<String> {
    a.get(k).and_then(|v| v.as_str()).map(|s| s.to_string())
}
fn req_str(a: &Value, k: &str) -> Result<String> {
    opt_str(a, k).ok_or_else(|| NdError::invalid(format!("missing {k}")))
}
fn str_list(a: &Value, k: &str) -> Result<Vec<String>> {
    let arr = a.get(k).and_then(|v| v.as_array()).ok_or_else(|| NdError::invalid(format!("missing {k}")))?;
    arr.iter().map(|v| v.as_str().map(|s| s.to_string()).ok_or_else(|| NdError::invalid(k))).collect()
}

impl Engine {
    pub fn new(sink: Sink) -> Arc<Engine> {
        let workers = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(2).clamp(2, 4);
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(workers)
            .enable_all()
            .thread_name("nd-engine")
            .build()
            .expect("tokio runtime");
        Arc::new(Engine {
            rt,
            inner: Arc::new(Inner {
                sink: Mutex::new(Some(sink)),
                store: Mutex::new(None),
                ops: Mutex::new(HashMap::new()),
                prompts: Mutex::new(HashMap::new()),
                sessions: Mutex::new(HashMap::new()),
                counter: AtomicU64::new(1),
            }),
        })
    }

    pub fn set_sink(&self, sink: Option<Sink>) {
        *self.inner.sink.lock().unwrap() = sink;
    }

    /// Submit a command (JSON). Returns the op id (0 if the envelope was unparsable).
    pub fn submit(&self, json: &str) -> u64 {
        let v: Value = match serde_json::from_str(json) {
            Ok(v) => v,
            Err(e) => {
                self.inner.emit(json!({"op":0,"ev":"err","err":NdError::invalid(e).to_json()}));
                return 0;
            }
        };
        let op = v.get("op").and_then(|x| x.as_u64()).unwrap_or(0);
        let cmd = v.get("cmd").and_then(|x| x.as_str()).unwrap_or("").to_string();
        let args = v.get("args").cloned().unwrap_or(json!({}));
        if op == 0 || cmd.is_empty() {
            self.inner.emit(json!({"op":op,"ev":"err","err":NdError::invalid("op/cmd").to_json()}));
            return op;
        }
        let token = CancellationToken::new();
        {
            let mut ops = self.inner.ops.lock().unwrap();
            if ops.contains_key(&op) {
                drop(ops);
                self.inner.emit(json!({"op":op,"ev":"err","err":NdError::invalid("duplicate op").to_json()}));
                return op;
            }
            ops.insert(op, token.clone());
        }
        let inner = self.inner.clone();
        self.rt.spawn(async move {
            let res = std::panic::AssertUnwindSafe(inner.clone().handle(op, token.clone(), &cmd, args));
            let res = futures::FutureExt::catch_unwind(res).await;
            let ev = match res {
                Err(_) => json!({"op":op,"ev":"err","err":NdError::internal("panic").to_json()}),
                Ok(Ok(data)) => json!({"op":op,"ev":"ok","data":data}),
                Ok(Err(e)) if e.code == Code::Cancelled => json!({"op":op,"ev":"cancelled"}),
                Ok(Err(e)) => json!({"op":op,"ev":"err","err":e.to_json()}),
            };
            inner.ops.lock().unwrap().remove(&op);
            inner.emit(ev);
        });
        op
    }

    pub fn cancel(&self, op: u64) {
        if let Some(t) = self.inner.ops.lock().unwrap().get(&op) {
            t.cancel();
        }
    }
}

impl Inner {
    fn emit(&self, v: Value) {
        let s = self.sink.lock().unwrap().clone();
        if let Some(s) = s {
            s(v.to_string());
        }
    }

    fn with_store<T>(&self, f: impl FnOnce(&mut Store) -> Result<T>) -> Result<T> {
        let mut g = self.store.lock().unwrap();
        let st = g.as_mut().ok_or_else(|| NdError::new(Code::NotInitialized))?;
        f(st)
    }

    fn session(&self, id: &str) -> Result<Arc<Session>> {
        self.sessions.lock().unwrap().get(id).cloned().ok_or_else(|| NdError::new(Code::SessionClosed).with("session", id))
    }

    async fn handle(self: Arc<Self>, op: u64, token: CancellationToken, cmd: &str, a: Value) -> Result<Value> {
        // Commands that do not need the store
        match cmd {
            "init" => return self.cmd_init(&a),
            "prompt.reply" => return self.cmd_prompt_reply(&a),
            "version" => return Ok(json!({"version": env!("CARGO_PKG_VERSION"), "api_version": API_VERSION})),
            _ => {}
        }
        match cmd {
            "profiles.list" => self.with_store(|s| Ok(json!({"profiles": s.public_profiles()}))),
            "profiles.save" => self.cmd_profiles_save(&a),
            "profiles.delete" => {
                let id = req_str(&a, "id")?;
                // close sessions of that profile
                let to_close: Vec<(String, Arc<Session>)> =
                    self.sessions.lock().unwrap().iter().filter(|(_, s)| s.profile_id == id).map(|(k, v)| (k.clone(), v.clone())).collect();
                for (k, s) in to_close {
                    self.sessions.lock().unwrap().remove(&k);
                    s.provider.close().await;
                }
                self.with_store(|s| s.delete_profile(&id))?;
                Ok(json!({}))
            }
            "profiles.export" => self.cmd_export(&a),
            "profiles.import" => self.cmd_import(&a),
            "settings.get" => self.with_store(|s| Ok(json!({"settings": s.data.settings}))),
            "settings.set" => {
                let m = a.get("settings").and_then(|v| v.as_object()).ok_or_else(|| NdError::invalid("settings"))?.clone();
                self.with_store(|s| {
                    for (k, v) in m {
                        s.data.settings.insert(k, v);
                    }
                    s.save()?;
                    Ok(json!({"settings": s.data.settings}))
                })
            }
            "favorites.list" => {
                let pid = req_str(&a, "profile_id")?;
                self.with_store(|s| Ok(json!({"favorites": s.data.favorites.get(&pid).cloned().unwrap_or_default()})))
            }
            "favorites.add" => {
                let pid = req_str(&a, "profile_id")?;
                let r = norm_ref(&req_str(&a, "ref")?)?;
                let name = req_str(&a, "name")?;
                self.with_store(|s| {
                    if s.profile(&pid).is_none() {
                        return Err(NdError::new(Code::NotFound).with("profile", pid.clone()));
                    }
                    let v = s.data.favorites.entry(pid.clone()).or_default();
                    if !v.iter().any(|f| f.ref_ == r) {
                        v.push(Favorite { ref_: r, name });
                    }
                    let out = v.clone();
                    s.save()?;
                    Ok(json!({"favorites": out}))
                })
            }
            "favorites.remove" => {
                let pid = req_str(&a, "profile_id")?;
                let r = norm_ref(&req_str(&a, "ref")?)?;
                self.with_store(|s| {
                    let v = s.data.favorites.entry(pid).or_default();
                    v.retain(|f| f.ref_ != r);
                    let out = v.clone();
                    s.save()?;
                    Ok(json!({"favorites": out}))
                })
            }
            "session.connect" => self.cmd_connect(op, token, &a).await,
            "session.close" => {
                let id = req_str(&a, "session")?;
                let s = self.sessions.lock().unwrap().remove(&id);
                if let Some(s) = s {
                    s.provider.close().await;
                }
                Ok(json!({}))
            }
            "session.list" => self.cmd_list(token, &a).await,
            "session.mkdir" => {
                let s = self.session(&req_str(&a, "session")?)?;
                let parent = norm_ref(&req_str(&a, "parent_ref")?)?;
                let name = validate_user_name(&req_str(&a, "name")?)?;
                let path = join(&parent, &name);
                let pr = Progress { cb: Arc::new(|_| {}), cancel: token };
                pr.run(async { s.provider.mkdir(&path).await }).await?;
                *s.cache.lock().unwrap() = None;
                let st = s.provider.stat(&path).await?;
                Ok(json!({"entry": transfer::entry_of(&parent, st)}))
            }
            "session.rename" => {
                let s = self.session(&req_str(&a, "session")?)?;
                let r = norm_ref(&req_str(&a, "ref")?)?;
                let name = validate_user_name(&req_str(&a, "new_name")?)?;
                let parent = parent_of(&r).ok_or_else(|| NdError::invalid("root"))?;
                let to = join(&parent, &name);
                if to == r {
                    let st = s.provider.stat(&r).await?;
                    return Ok(json!({"entry": transfer::entry_of(&parent, st)}));
                }
                let pr = Progress { cb: Arc::new(|_| {}), cancel: token };
                pr.run(async { s.provider.rename(&r, &to).await }).await?;
                *s.cache.lock().unwrap() = None;
                let st = s.provider.stat(&to).await?;
                Ok(json!({"entry": transfer::entry_of(&parent, st)}))
            }
            "session.delete" => {
                let s = self.session(&req_str(&a, "session")?)?;
                let refs: Vec<String> = str_list(&a, "refs")?.iter().map(|r| norm_ref(r)).collect::<Result<_>>()?;
                if refs.iter().any(|r| r == "/") {
                    return Err(NdError::new(Code::PolicyDenied).with("rule", "delete_root"));
                }
                let recursive = a.get("recursive").and_then(|v| v.as_bool()).unwrap_or(false);
                let rep = self.reporter(op, token, false);
                let n = transfer::delete(s.provider.as_ref(), &refs, recursive, &rep).await;
                *s.cache.lock().unwrap() = None;
                Ok(json!({"deleted": n?}))
            }
            "transfer.download" => {
                let s = self.session(&req_str(&a, "session")?)?;
                let refs: Vec<String> = str_list(&a, "refs")?.iter().map(|r| norm_ref(r)).collect::<Result<_>>()?;
                let dest = req_str(&a, "dest_dir")?;
                let col = Collision::parse(&opt_str(&a, "collision").unwrap_or_default())?;
                let rep = self.reporter(op, token, true);
                let r = transfer::download(s.provider.as_ref(), &refs, &dest, col, &rep).await?;
                rep.flush();
                Ok(json!({"local_paths": r}))
            }
            "transfer.upload" => {
                let s = self.session(&req_str(&a, "session")?)?;
                let locals = str_list(&a, "local_paths")?;
                let parent = norm_ref(&req_str(&a, "parent_ref")?)?;
                let col = Collision::parse(&opt_str(&a, "collision").unwrap_or_default())?;
                let rep = self.reporter(op, token, true);
                let created = transfer::upload(s.provider.as_ref(), &locals, &parent, col, &rep).await;
                *s.cache.lock().unwrap() = None;
                let created = created?;
                let mut entries = vec![];
                for p in created {
                    if let Ok(st) = s.provider.stat(&p).await {
                        entries.push(transfer::entry_of(&parent_of(&p).unwrap_or("/".into()), st));
                    }
                }
                rep.flush();
                Ok(json!({"entries": entries}))
            }
            other => Err(NdError::new(Code::Unsupported).with("cmd", other)),
        }
    }

    // ---------------------------------------------------------------- reporter
    fn reporter(self: &Arc<Self>, op: u64, token: CancellationToken, bytes: bool) -> ReporterHandle {
        let state = Arc::new(Mutex::new(ProgState { item: String::new(), done: 0, total_items: 0, last: Instant::now() - Duration::from_secs(1) }));
        let bytes_done = Arc::new(AtomicU64::new(0));
        let total = Arc::new(AtomicU64::new(0));
        let me = self.clone();
        let (st2, bd2, tot2) = (state.clone(), bytes_done.clone(), total.clone());
        let emit: Arc<dyn Fn(bool) + Send + Sync> = Arc::new(move |force: bool| {
            let mut s = st2.lock().unwrap();
            if !force && s.last.elapsed() < Duration::from_millis(100) {
                return;
            }
            s.last = Instant::now();
            let t = tot2.load(Ordering::Relaxed);
            me.emit(json!({"op":op,"ev":"progress","data":{
                "bytes": bd2.load(Ordering::Relaxed),
                "total": if bytes && t > 0 { Value::from(t) } else { Value::Null },
                "item": s.item, "items_done": s.done, "items_total": s.total_items }}));
        });
        let (e1, bd) = (emit.clone(), bytes_done.clone());
        let cb: Arc<dyn Fn(u64) + Send + Sync> = Arc::new(move |d| {
            bd.fetch_add(d, Ordering::Relaxed);
            e1(false);
        });
        let (st3, e2) = (state.clone(), emit.clone());
        let on_item: Arc<dyn Fn(&str, u64, u64) + Send + Sync> = Arc::new(move |name, done, total_items| {
            {
                let mut s = st3.lock().unwrap();
                if !name.is_empty() {
                    s.item = name.to_string();
                }
                s.done = done;
                if total_items > 0 {
                    s.total_items = total_items;
                }
            }
            e2(true);
        });
        ReporterHandle { rep: Reporter { pr: Progress { cb, cancel: token }, total, on_item }, emit }
    }

    // ---------------------------------------------------------------- init & prompts
    fn cmd_init(&self, a: &Value) -> Result<Value> {
        let dir = req_str(a, "data_dir")?;
        let pmk = B64.decode(req_str(a, "pmk_b64")?).map_err(|_| NdError::invalid("pmk_b64"))?;
        let st = Store::open(&dir, &pmk)?;
        *self.store.lock().unwrap() = Some(st);
        Ok(json!({
            "api_version": API_VERSION,
            "version": env!("CARGO_PKG_VERSION"),
            "protocols": [
                {"id":"sftp","enabled":true},
                {"id":"webdav","enabled":true},
                {"id":"s3","enabled":true},
                smb_protocol_entry(),
                {"id":"fake","enabled":true}
            ]
        }))
    }

    fn cmd_prompt_reply(&self, a: &Value) -> Result<Value> {
        let id = req_str(a, "prompt_id")?;
        let accept = a.get("accept").and_then(|v| v.as_bool()).unwrap_or(false);
        let value = opt_str(a, "value");
        let replace = a.get("replace").and_then(|v| v.as_bool()).unwrap_or(false);
        let tx = self.prompts.lock().unwrap().remove(&id);
        match tx {
            Some((_, tx)) => {
                let _ = tx.send(PromptReply { accept, value, replace });
                Ok(json!({}))
            }
            None => Err(NdError::invalid("unknown prompt").with("prompt_id", id)),
        }
    }

    async fn prompt(self: &Arc<Self>, op: u64, token: &CancellationToken, mut data: Value) -> Result<PromptReply> {
        let id = format!("p{}", self.counter.fetch_add(1, Ordering::Relaxed));
        let (tx, rx) = oneshot::channel();
        self.prompts.lock().unwrap().insert(id.clone(), (op, tx));
        data["prompt_id"] = json!(id);
        self.emit(json!({"op":op,"ev":"prompt","data":data}));
        let r = tokio::select! {
            r = rx => r.map_err(|_| NdError::new(Code::Cancelled)),
            _ = token.cancelled() => Err(NdError::new(Code::Cancelled)),
        };
        self.prompts.lock().unwrap().remove(&id);
        r
    }

    // ---------------------------------------------------------------- profiles
    fn cmd_profiles_save(&self, a: &Value) -> Result<Value> {
        let mut p: Profile = serde_json::from_value(a.get("profile").cloned().ok_or_else(|| NdError::invalid("profile"))?)?;
        let secrets: Option<Secrets> = match a.get("secrets") {
            Some(v) if !v.is_null() => Some(serde_json::from_value(v.clone())?),
            _ => None,
        };
        p.name = p.name.trim().to_string();
        p.host = p.host.trim().to_string();
        if p.name.is_empty() || p.name.len() > 200 {
            return Err(NdError::invalid("name"));
        }
        if !["sftp", "webdav", "s3", "smb", "fake"].contains(&p.protocol.as_str()) {
            return Err(NdError::invalid("protocol"));
        }
        if p.auth.is_empty() {
            p.auth = "password".into();
        }
        if !["password", "key", "none"].contains(&p.auth.as_str()) {
            return Err(NdError::invalid("auth"));
        }
        if p.port > 65535 {
            return Err(NdError::invalid("port"));
        }
        if p.host.is_empty() && !(p.protocol == "fake" || p.protocol == "s3") {
            return Err(NdError::invalid("host"));
        }
        if p.host.chars().any(|c| c.is_whitespace() || c == '/' || c == '\\') {
            return Err(NdError::invalid("host"));
        }
        if p.protocol == "s3" && p.s3.is_none() {
            return Err(NdError::invalid("s3"));
        }
        if p.protocol != "s3" {
            p.s3 = None;
        }
        p.base_path = if p.base_path.trim().is_empty() { String::new() } else { norm_ref(&p.base_path)? };
        p.has_secret = false;
        self.with_store(|s| {
            if p.id.is_empty() {
                p.id = format!("p_{}", crypto::random_hex(8));
            } else if s.profile(&p.id).is_none() && !p.id.starts_with("p_") {
                return Err(NdError::invalid("id"));
            }
            match s.data.profiles.iter_mut().find(|x| x.id == p.id) {
                Some(slot) => *slot = p.clone(),
                None => s.data.profiles.push(p.clone()),
            }
            if let Some(sec) = secrets {
                s.data.secrets.entry(p.id.clone()).or_default().merge(sec);
            }
            s.save()?;
            let has = s.data.secrets.get(&p.id).map(|x| !x.is_empty()).unwrap_or(false);
            let mut out = p.clone();
            out.has_secret = has;
            Ok(json!({"profile": out}))
        })
    }

    // ---------------------------------------------------------------- export / import
    fn cmd_export(&self, a: &Value) -> Result<Value> {
        let pass = req_str(a, "passphrase")?;
        if pass.chars().count() < 8 {
            return Err(NdError::new(Code::PassphraseInvalid).detail("too short (min 8)"));
        }
        let o = a.get("options").cloned().unwrap_or(json!({}));
        let flag = |k: &str| o.get(k).and_then(|v| v.as_bool()).unwrap_or(false);
        let payload = self.with_store(|s| {
            let mut doc = Map::new();
            doc.insert("v".into(), json!(1));
            doc.insert("profiles".into(), json!(s.data.profiles.iter().map(|p| { let mut q = p.clone(); q.has_secret = false; q }).collect::<Vec<_>>()));
            if flag("favorites") {
                doc.insert("favorites".into(), json!(s.data.favorites));
            }
            if flag("settings") {
                doc.insert("settings".into(), json!(s.data.settings));
            }
            if flag("pins") {
                doc.insert("pins".into(), json!(s.data.pins));
            }
            Ok(Value::Object(doc))
        })?;
        let (m, t, p) = (64 * 1024u32, 3u32, 1u32);
        let salt = crypto::random(16);
        let mut header = Vec::new();
        header.extend_from_slice(EXPORT_MAGIC);
        header.extend_from_slice(&m.to_le_bytes());
        header.extend_from_slice(&t.to_le_bytes());
        header.extend_from_slice(&p.to_le_bytes());
        header.extend_from_slice(&salt);
        let key = crypto::argon_key(&pass, &salt, m, t, p)?;
        let sealed = crypto::seal(&key, &header, payload.to_string().as_bytes());
        header.extend_from_slice(&sealed);
        Ok(json!({"data_b64": B64.encode(header)}))
    }

    fn cmd_import(&self, a: &Value) -> Result<Value> {
        let bad = |d: &str| NdError::new(Code::ImportInvalid).detail(d.to_string());
        let b64 = req_str(a, "data_b64")?;
        if b64.len() > IMPORT_MAX * 4 / 3 + 8 {
            return Err(NdError::new(Code::LimitExceeded).with("limit", "import_size"));
        }
        let raw = B64.decode(b64.trim()).map_err(|_| bad("base64"))?;
        if raw.len() > IMPORT_MAX || raw.len() < 5 + 12 + 16 + 12 + 16 || &raw[..5] != EXPORT_MAGIC {
            return Err(bad("format"));
        }
        let rd = |o: usize| u32::from_le_bytes(raw[o..o + 4].try_into().unwrap());
        let (m, t, p) = (rd(5), rd(9), rd(13));
        if m < crypto::ARGON_MIN_M_KIB || t < crypto::ARGON_MIN_T || m > crypto::ARGON_MAX_M_KIB || t > crypto::ARGON_MAX_T || p == 0 || p > 8 {
            return Err(bad("kdf parameters out of range"));
        }
        let header = &raw[..33];
        let key = crypto::argon_key(&req_str(a, "passphrase")?, &raw[17..33], m, t, p)?;
        let plain = crypto::open(&key, header, &raw[33..])?; // PassphraseInvalid on wrong passphrase / tampering
        #[derive(Deserialize)]
        #[serde(deny_unknown_fields)]
        struct Doc {
            v: u32,
            profiles: Vec<Profile>,
            #[serde(default)]
            favorites: HashMap<String, Vec<Favorite>>,
            #[serde(default)]
            settings: Map<String, Value>,
            #[serde(default)]
            pins: HashMap<String, Vec<Pin>>,
        }
        let doc: Doc = serde_json::from_slice(&plain).map_err(|e| bad(&e.to_string()))?;
        if doc.v != 1 || doc.profiles.len() > 1000 {
            return Err(bad("version/size"));
        }
        let apply = a.get("apply").and_then(|v| v.as_bool()).unwrap_or(false);
        self.with_store(|s| {
            let mut conflicts = vec![];
            let mut imported: Vec<Profile> = vec![];
            let mut idmap: HashMap<String, String> = HashMap::new();
            for mut p in doc.profiles {
                if p.name.is_empty() || !["sftp", "webdav", "s3", "smb", "fake"].contains(&p.protocol.as_str()) {
                    return Err(bad("profile"));
                }
                let old = p.id.clone();
                if s.profile(&old).is_some() {
                    conflicts.push(json!({"id": old, "name": p.name}));
                    p.id = format!("p_{}", crypto::random_hex(8)); // "keep both"
                } else if !old.starts_with("p_") {
                    p.id = format!("p_{}", crypto::random_hex(8));
                }
                p.has_secret = false;
                idmap.insert(old, p.id.clone());
                imported.push(p);
            }
            if apply {
                for p in &imported {
                    s.data.profiles.push(p.clone());
                }
                for (old, favs) in doc.favorites {
                    if let Some(n) = idmap.get(&old) {
                        s.data.favorites.insert(n.clone(), favs);
                    }
                }
                for (old, pins) in doc.pins {
                    if let Some(n) = idmap.get(&old) {
                        // imported trust is never trusted blindly: confirm at first connection
                        s.data.pins.insert(n.clone(), pins.into_iter().map(|mut p| { p.verified = false; p }).collect());
                    }
                }
                for (k, v) in doc.settings {
                    s.data.settings.insert(k, v);
                }
                s.save()?;
            }
            Ok(json!({"profiles": imported, "conflicts": conflicts, "applied": apply}))
        })
    }

    // ---------------------------------------------------------------- connect & list
    async fn cmd_connect(self: &Arc<Self>, op: u64, token: CancellationToken, a: &Value) -> Result<Value> {
        let pid = req_str(a, "profile_id")?;
        let (profile, secrets) = self.with_store(|s| {
            let p = s.profile(&pid).cloned().ok_or_else(|| NdError::new(Code::NotFound).with("profile", pid.clone()))?;
            let sec = s.data.secrets.get(&pid).cloned().unwrap_or_default();
            Ok((p, sec))
        })?;
        policy::check_profile(&profile)?;
        let trust: Arc<dyn Trust> = Arc::new(OpTrust { inner: self.clone(), op, token: token.clone(), profile_id: pid.clone(), profile_name: profile.name.clone() });
        let fut = async {
            let prov: Arc<dyn Provider> = match profile.protocol.as_str() {
                "sftp" => SftpProvider::connect(&profile, &secrets, trust.clone()).await?,
                "webdav" => WebDavProvider::connect(&profile, &secrets, trust.clone()).await?,
                "s3" => S3Provider::connect(&profile, &secrets, trust.clone()).await?,
                #[cfg(feature = "smb")]
                "smb" => crate::provider::smb::SmbProvider::connect(&profile, &secrets, trust.clone()).await?,
                "fake" => FakeProvider::seeded(),
                _ => return Err(NdError::new(Code::ProtocolDisabled).with("protocol", profile.protocol.clone())),
            };
            Ok::<_, NdError>(prov)
        };
        let prov = tokio::select! {
            r = fut => r?,
            _ = token.cancelled() => return Err(NdError::new(Code::Cancelled)),
        };
        let home = prov.home();
        let id = format!("s{}", self.counter.fetch_add(1, Ordering::Relaxed));
        self.sessions.lock().unwrap().insert(id.clone(), Arc::new(Session { profile_id: pid, provider: prov, cache: Mutex::new(None) }));
        Ok(json!({"session": id, "home_ref": home}))
    }

    async fn cmd_list(&self, token: CancellationToken, a: &Value) -> Result<Value> {
        let s = self.session(&req_str(a, "session")?)?;
        let r = norm_ref(&opt_str(a, "ref").unwrap_or_else(|| "/".into()))?;
        let sort = opt_str(a, "sort").unwrap_or_else(|| "name".into());
        if !["name", "size", "mtime"].contains(&sort.as_str()) {
            return Err(NdError::invalid("sort"));
        }
        let desc = a.get("desc").and_then(|v| v.as_bool()).unwrap_or(false);
        let limit = (a.get("limit").and_then(|v| v.as_u64()).unwrap_or(PAGE_DEFAULT as u64) as usize).clamp(1, PAGE_MAX);
        let offset: usize = match opt_str(a, "page_token") {
            Some(t) => t.parse().map_err(|_| NdError::invalid("page_token"))?,
            None => 0,
        };
        // reuse the cache only for continuation pages of the same listing
        let cached = if offset > 0 {
            let g = s.cache.lock().unwrap();
            g.as_ref().filter(|c| c.ref_ == r && c.sort == sort && c.desc == desc).map(|c| (c.entries.clone(), c.anomalies))
        } else {
            None
        };
        let (entries, anomalies) = match cached {
            Some(x) => x,
            None => {
                let pr = Progress { cb: Arc::new(|_| {}), cancel: token };
                let raw = pr.run(async { s.provider.list(&r).await }).await?;
                let (mut v, anomalies) = pathguard::sanitize_listing(&r, raw);
                v.sort_by(|x, y| {
                    let dir_first = (y.kind == EntryKind::Dir).cmp(&(x.kind == EntryKind::Dir));
                    let key = match sort.as_str() {
                        "size" => x.size.unwrap_or(0).cmp(&y.size.unwrap_or(0)),
                        "mtime" => x.mtime_ms.unwrap_or(0).cmp(&y.mtime_ms.unwrap_or(0)),
                        _ => x.name.to_lowercase().cmp(&y.name.to_lowercase()).then_with(|| x.name.cmp(&y.name)),
                    };
                    dir_first.then(if desc { key.reverse() } else { key })
                });
                let arc = Arc::new(v);
                *s.cache.lock().unwrap() = Some(ListCache { ref_: r.clone(), sort: sort.clone(), desc, entries: arc.clone(), anomalies });
                (arc, anomalies)
            }
        };
        let end = (offset + limit).min(entries.len());
        let page: Vec<&Entry> = entries.get(offset.min(entries.len())..end).unwrap_or(&[]).iter().collect();
        Ok(json!({
            "ref": r,
            "parent_ref": parent_of(&r),
            "breadcrumbs": breadcrumbs(&r).into_iter().map(|(n, r)| json!({"name": n, "ref": r})).collect::<Vec<_>>(),
            "entries": page,
            "next_token": if end < entries.len() { Value::from(end.to_string()) } else { Value::Null },
            "total_hint": entries.len(),
            "server_anomalies": anomalies,
        }))
    }
}

struct ProgState {
    item: String,
    done: u64,
    total_items: u64,
    last: Instant,
}

pub struct ReporterHandle {
    rep: Reporter,
    emit: Arc<dyn Fn(bool) + Send + Sync>,
}
impl ReporterHandle {
    pub fn flush(&self) {
        (self.emit)(true);
    }
}
impl std::ops::Deref for ReporterHandle {
    type Target = Reporter;
    fn deref(&self) -> &Reporter {
        &self.rep
    }
}

// -------------------------------------------------------------------- Trust implementation

struct OpTrust {
    inner: Arc<Inner>,
    op: u64,
    token: CancellationToken,
    profile_id: String,
    profile_name: String,
}

#[async_trait]
impl Trust for OpTrust {
    async fn ssh_host_key(&self, host: &str, port: u32, algo: &str, fp: &str) -> Result<()> {
        let existing = self.inner.with_store(|s| {
            Ok(s.data.pins.get(&self.profile_id).and_then(|v| v.iter().find(|p| p.kind == "ssh" && p.host == host && p.port == port).cloned()))
        })?;
        match existing {
            Some(p) if p.value == fp && p.verified => Ok(()),
            Some(p) if p.value == fp => {
                // imported, not yet verified by the user
                let r = self
                    .inner
                    .prompt(self.op, &self.token, json!({"kind":"trust_host","host":host,"port":port,"algo":algo,"fingerprint":fp,"prior":null,"imported":true}))
                    .await?;
                if !r.accept {
                    return Err(NdError::new(Code::HostKeyRejected).with("host", host));
                }
                self.set_pin("ssh", host, port, fp)
            }
            Some(p) => {
                let r = self
                    .inner
                    .prompt(self.op, &self.token, json!({"kind":"trust_host","host":host,"port":port,"algo":algo,"fingerprint":fp,"prior":p.value}))
                    .await?;
                if r.accept && r.replace {
                    self.set_pin("ssh", host, port, fp)
                } else {
                    Err(NdError::new(Code::HostKeyChanged).with("host", host).with("fingerprint", fp).with("prior", p.value))
                }
            }
            None => {
                let r = self
                    .inner
                    .prompt(self.op, &self.token, json!({"kind":"trust_host","host":host,"port":port,"algo":algo,"fingerprint":fp,"prior":null}))
                    .await?;
                if r.accept {
                    self.set_pin("ssh", host, port, fp)
                } else {
                    Err(NdError::new(Code::HostKeyRejected).with("host", host))
                }
            }
        }
    }

    async fn tls_cert(&self, host: &str, port: u32, c: &CertInfo) -> Result<()> {
        let prior = self.inner.with_store(|s| {
            Ok(s.data.pins.get(&self.profile_id).and_then(|v| v.iter().find(|p| p.kind == "tls" && p.host == host && p.port == port).map(|p| p.value.clone())))
        })?;
        let r = self
            .inner
            .prompt(
                self.op,
                &self.token,
                json!({"kind":"trust_cert","host":host,"port":port,"spki_sha256":c.spki_sha256,"subject":c.subject,"self_signed":c.self_signed,"reason":c.reason,"prior":prior}),
            )
            .await?;
        if r.accept {
            self.set_pin("tls", host, port, &c.spki_sha256)
        } else {
            Err(NdError::new(Code::TlsUntrusted).with("host", host).with("spki_sha256", c.spki_sha256.clone()))
        }
    }

    async fn ask_secret(&self, kind: &str) -> Result<Option<String>> {
        let k = if kind == "passphrase" { "passphrase" } else { "password" };
        let r = self.inner.prompt(self.op, &self.token, json!({"kind":k,"profile_name":self.profile_name})).await?;
        Ok(if r.accept { r.value } else { None })
    }

    fn tls_pins(&self, host: &str, port: u32) -> Vec<String> {
        self.inner
            .with_store(|s| {
                Ok(s.data
                    .pins
                    .get(&self.profile_id)
                    .map(|v| v.iter().filter(|p| p.kind == "tls" && p.host == host && p.port == port && p.verified).map(|p| p.value.clone()).collect())
                    .unwrap_or_default())
            })
            .unwrap_or_default()
    }
}

impl OpTrust {
    fn set_pin(&self, kind: &str, host: &str, port: u32, value: &str) -> Result<()> {
        self.inner.with_store(|s| {
            let v = s.data.pins.entry(self.profile_id.clone()).or_default();
            v.retain(|p| !(p.kind == kind && p.host == host && p.port == port));
            v.push(Pin { kind: kind.into(), host: host.into(), port, value: value.into(), verified: true });
            s.save()
        })
    }
}

#[cfg(feature = "smb")]
fn smb_protocol_entry() -> Value {
    json!({"id":"smb","enabled":true})
}
#[cfg(not(feature = "smb"))]
fn smb_protocol_entry() -> Value {
    json!({"id":"smb","enabled":false,"reason_code":"SMB_GATE_NOT_PASSED"})
}

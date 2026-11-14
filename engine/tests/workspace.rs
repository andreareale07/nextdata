//! Workspace (open.*) behaviour on the in-memory provider: cache reuse, change detection by content,
//! save, conflicts, protected-vs-evictable cache entries, orphaned work, import, export.
mod common;
use common::*;
use serde_json::{json, Value};
use std::fs;

struct Env {
    h: H,
    s: String,
    home: String,
    pid: String,
}

fn env() -> Env {
    let h = harness();
    let pid = save_profile(&h, json!({"name":"Demo","protocol":"fake"}), json!(null));
    let c = h.ok("session.connect", json!({"profile_id": pid}));
    let s = c["session"].as_str().unwrap().to_string();
    let home = c["home_ref"].as_str().unwrap().to_string();
    Env { h, s, home, pid }
}

impl Env {
    /// Uploads `content` as `name` into the home folder and returns its ref.
    fn put_remote(&self, name: &str, content: &str) -> String {
        let p = self.h.path(&format!("up/{name}"));
        fs::create_dir_all(p.parent().unwrap()).unwrap();
        fs::write(&p, content).unwrap();
        let r = self.h.ok("transfer.upload", json!({"session": self.s, "local_paths": [p.to_string_lossy()], "parent_ref": self.home, "collision": "fail"}));
        r["entries"][0]["ref"].as_str().unwrap().to_string()
    }
    fn prepare(&self, r: &str) -> Value {
        self.h.ok("open.prepare", json!({"session": self.s, "ref": r}))
    }
    fn check(&self, id: &str) -> Value {
        self.h.ok("open.check", json!({"doc_id": id, "stable_ms": 0}))
    }
    fn remote_text(&self, r: &str) -> String {
        let dl = self.h.path(&format!("dl_{}", rand_id()));
        fs::create_dir_all(&dl).unwrap();
        let out = self.h.ok("transfer.download", json!({"session": self.s, "refs": [r], "dest_dir": dl.to_string_lossy(), "collision": "fail"}));
        fs::read_to_string(out["local_paths"][0].as_str().unwrap()).unwrap()
    }
}

fn rand_id() -> u64 {
    use std::sync::atomic::{AtomicU64, Ordering};
    static N: AtomicU64 = AtomicU64::new(1);
    N.fetch_add(1, Ordering::Relaxed)
}

fn doc_id(v: &Value) -> String {
    v["doc"]["id"].as_str().unwrap().to_string()
}
fn local(v: &Value) -> String {
    v["doc"]["local_path"].as_str().unwrap().to_string()
}
fn state(v: &Value) -> String {
    v["doc"]["state"].as_str().unwrap().to_string()
}

#[test]
fn open_reuses_cache_and_detects_change_by_content() {
    let e = env();
    let r = e.put_remote("note.txt", "version one");
    let p1 = e.prepare(&r);
    assert_eq!(p1["from_cache"], false);
    assert_eq!(state(&p1), "clean");
    assert_eq!(fs::read_to_string(local(&p1)).unwrap(), "version one");
    let id = doc_id(&p1);
    // second open: same document, served from the cache (no new download)
    let p2 = e.prepare(&r);
    assert_eq!(p2["from_cache"], true);
    assert_eq!(doc_id(&p2), id);
    // nothing changed: check says so, and never reports a change
    assert_eq!(e.check(&id)["changed"], false);
    // an editor rewrites the file
    fs::write(local(&p1), "version one, edited").unwrap();
    let c = e.check(&id);
    assert_eq!(c["changed"], true);
    assert_eq!(state(&c), "modified");
    assert_eq!(c["doc"]["snaps"].as_array().unwrap().len(), 1);
    // the same content again does not pile up snapshots
    let c = e.check(&id);
    assert_eq!(c["doc"]["snaps"].as_array().unwrap().len(), 1);
    // same size, different bytes: still detected (content hash, not size/mtime)
    fs::write(local(&p1), "version one, edited").unwrap();
    fs::write(local(&p1), "version one, EDITED").unwrap();
    let c = e.check(&id);
    assert_eq!(c["doc"]["snaps"].as_array().unwrap().len(), 2);
    // reverting to the saved content makes it clean again
    fs::write(local(&p1), "version one").unwrap();
    let c = e.check(&id);
    assert_eq!(c["changed"], false);
    assert_eq!(state(&c), "clean");
    assert_eq!(c["doc"]["snaps"].as_array().unwrap().len(), 0);
}

#[test]
fn zero_byte_file_is_suspicious_and_copies_are_never_uploaded() {
    let e = env();
    let r = e.put_remote("d.txt", "some real content");
    let p = e.prepare(&r);
    let id = doc_id(&p);
    fs::write(local(&p), b"").unwrap(); // an app truncating before it rewrites
    let c = e.check(&id);
    assert_eq!(c["suspicious"], "empty");
    assert_eq!(state(&c), "clean", "a possibly torn file must not become a pending save");
    assert!(e.h.call("open.save", json!({"session": e.s, "doc_id": id})).is_err());
    // an app that saves a copy next to the document
    fs::write(local(&p), "some real content").unwrap();
    let copy = std::path::Path::new(&local(&p)).with_file_name("d (1).txt");
    fs::write(&copy, "copy by app").unwrap();
    let c = e.check(&id);
    assert_eq!(c["copies"], json!(["d (1).txt"]));
    assert_eq!(e.remote_text(&r), "some real content");
    // importing the copy is the explicit way to use it
    let i = e.h.ok("open.import", json!({"doc_id": id, "local_path": copy.to_string_lossy()}));
    assert_eq!(state(&i), "imported");
}

#[test]
fn save_updates_remote_and_base_and_verifies() {
    let e = env();
    let r = e.put_remote("s.txt", "before");
    let p = e.prepare(&r);
    let id = doc_id(&p);
    assert_eq!(e.h.code("open.save", json!({"session": e.s, "doc_id": id})), "INVALID_ARGUMENT"); // nothing to save
    fs::write(local(&p), "after the edit").unwrap();
    let sv = e.h.ok("open.save", json!({"session": e.s, "doc_id": id}));
    assert_eq!(state(&sv), "clean");
    assert!(["content", "size"].contains(&sv["saved"]["replaced"]["verified"].as_str().unwrap()), "{sv}");
    assert_eq!(e.remote_text(&r), "after the edit");
    assert_eq!(sv["doc"]["snaps"].as_array().unwrap().len(), 0);
    // reopening is served from cache and the saved version is the new base
    let again = e.prepare(&r);
    assert_eq!(again["from_cache"], true);
    assert_eq!(e.check(&id)["changed"], false);
    // a second edit saves again
    fs::write(local(&p), "after the second edit").unwrap();
    e.h.ok("open.save", json!({"session": e.s, "doc_id": id}));
    assert_eq!(e.remote_text(&r), "after the second edit");
}

#[test]
fn remote_change_during_edit_is_a_conflict_with_three_resolutions() {
    let e = env();
    let r = e.put_remote("c.txt", "original");
    let p = e.prepare(&r);
    let id = doc_id(&p);
    // somebody else replaces the remote file meanwhile
    let other = e.h.path("other.txt");
    fs::write(&other, "someone elses change that is longer").unwrap();
    let v = e.h.ok("session.stat", json!({"session": e.s, "ref": r}))["version"].clone();
    e.h.ok("session.replace", json!({"session": e.s, "ref": r, "local_path": other.to_string_lossy(), "expect_version": v, "base_sha256": nd_engine::crypto::sha256_hex(b"original")}));
    fs::write(local(&p), "my edit").unwrap();
    // save: refused, nothing overwritten, local work intact
    let err = e.h.call("open.save", json!({"session": e.s, "doc_id": id})).unwrap_err();
    assert_eq!(err["code"], "CONFLICT", "{err}");
    assert_eq!(e.remote_text(&r), "someone elses change that is longer");
    assert_eq!(fs::read_to_string(local(&p)).unwrap(), "my edit");
    let l = e.h.ok("open.list", json!({"profile_id": e.pid}));
    assert_eq!(l["docs"][0]["state"], "conflict");
    // 1) save as copy: the remote stays, the copy appears, the document follows the copy
    let cp = e.h.ok("open.save", json!({"session": e.s, "doc_id": id, "resolution": "copy"}));
    assert_eq!(state(&cp), "clean");
    assert_eq!(e.remote_text(&r), "someone elses change that is longer");
    let copy_ref = cp["doc"]["remote_ref"].as_str().unwrap().to_string();
    assert!(copy_ref.contains("modifica NextData"), "{copy_ref}");
    assert_eq!(e.remote_text(&copy_ref), "my edit");
    // 2) overwrite is deliberate: new conflict, then forced with a recovery copy of what is replaced
    let p2 = e.prepare(&r); // a fresh document for the original remote
    let id2 = doc_id(&p2);
    assert_ne!(id2, id);
    fs::write(&other, "third party again!").unwrap();
    let v = e.h.ok("session.stat", json!({"session": e.s, "ref": r}))["version"].clone();
    e.h.ok("session.replace", json!({"session": e.s, "ref": r, "local_path": other.to_string_lossy(), "expect_version": v, "base_sha256": nd_engine::crypto::sha256_hex(b"someone elses change that is longer")}));
    fs::write(local(&p2), "my forced edit").unwrap();
    assert_eq!(e.h.call("open.save", json!({"session": e.s, "doc_id": id2})).unwrap_err()["code"], "CONFLICT");
    let rec = e.h.path("recovery");
    fs::create_dir_all(&rec).unwrap();
    let ov = e.h.ok("open.save", json!({"session": e.s, "doc_id": id2, "resolution": "overwrite", "recovery_dir": rec.to_string_lossy()}));
    assert_eq!(state(&ov), "clean");
    assert_eq!(e.remote_text(&r), "my forced edit");
    assert_eq!(fs::read_to_string(rec.join("c.txt")).unwrap(), "third party again!");
}

#[test]
fn imported_version_is_saved_only_on_request() {
    let e = env();
    let r = e.put_remote("i.txt", "base text");
    let p = e.prepare(&r);
    let id = doc_id(&p);
    let ext = e.h.path("exported_by_other_app.txt");
    fs::write(&ext, "edited elsewhere").unwrap();
    assert_eq!(e.h.code("open.import", json!({"doc_id": id, "local_path": local(&p)})), "INVALID_ARGUMENT"); // identical to base
    let i = e.h.ok("open.import", json!({"doc_id": id, "local_path": ext.to_string_lossy()}));
    assert_eq!(state(&i), "imported");
    assert_eq!(e.remote_text(&r), "base text", "import must not upload");
    let sv = e.h.ok("open.save", json!({"session": e.s, "doc_id": id}));
    assert_eq!(state(&sv), "clean");
    assert_eq!(e.remote_text(&r), "edited elsewhere");
}

#[test]
fn cache_purge_never_touches_modified_open_or_conflicting_work() {
    let e = env();
    let ra = e.put_remote("a.txt", "aaaa");
    let rb = e.put_remote("b.txt", "bbbb");
    let rc = e.put_remote("c.txt", "cccc");
    let rd = e.put_remote("d.txt", "dddd");
    let (pa, pb, pc, pd) = (e.prepare(&ra), e.prepare(&rb), e.prepare(&rc), e.prepare(&rd));
    fs::write(local(&pb), "bbbb edited").unwrap();
    e.check(&doc_id(&pb)); // modified
    e.h.ok("open.mark_open", json!({"doc_id": doc_id(&pc)})); // an external app may still use it
    let stats = e.h.ok("cache.stats", json!({}));
    assert!(stats["used"].as_u64().unwrap() > 0 && stats["docs"] == 4);
    let freed = e.h.ok("cache.purge", json!({"max_idle_secs": 0, "open_max_idle_secs": 3600}));
    assert!(freed["freed"].as_u64().unwrap() > 0);
    let list = e.h.ok("open.list", json!({}));
    let ids: Vec<String> = list["docs"].as_array().unwrap().iter().map(|d| d["id"].as_str().unwrap().to_string()).collect();
    assert!(!ids.contains(&doc_id(&pa)), "clean idle copy should be evicted");
    assert!(!ids.contains(&doc_id(&pd)), "clean idle copy should be evicted");
    assert!(ids.contains(&doc_id(&pb)), "modified work must survive");
    assert!(ids.contains(&doc_id(&pc)), "externally open copy must survive");
    assert_eq!(fs::read_to_string(local(&pb)).unwrap(), "bbbb edited");
    // once the user closes it, it becomes evictable
    e.h.ok("open.release", json!({"doc_id": doc_id(&pc)}));
    e.h.ok("cache.purge", json!({"max_idle_secs": 0}));
    let ids: Vec<String> = e.h.ok("open.list", json!({}))["docs"].as_array().unwrap().iter().map(|d| d["id"].as_str().unwrap().to_string()).collect();
    assert_eq!(ids, vec![doc_id(&pb)]);
}

#[test]
fn small_quota_evicts_only_what_is_safe_and_reports_the_overrun() {
    let e = env();
    let ra = e.put_remote("q1.txt", &"x".repeat(1000));
    let rb = e.put_remote("q2.txt", &"y".repeat(1000));
    let pa = e.prepare(&ra);
    fs::write(local(&pa), "z".repeat(900)).unwrap();
    e.check(&doc_id(&pa)); // modified: protected
    let pb = e.h.ok("open.prepare", json!({"session": e.s, "ref": rb, "cache_limit": 100}));
    assert_eq!(pb["over_quota"], true, "protected data exceeds the limit: warn, never delete it");
    assert!(std::path::Path::new(&local(&pa)).exists());
    assert_eq!(fs::read_to_string(local(&pa)).unwrap(), "z".repeat(900));
}

#[test]
fn deleting_a_profile_keeps_pending_work_as_orphaned_and_exportable() {
    let e = env();
    let ra = e.put_remote("keep.txt", "keep me");
    let rb = e.put_remote("drop.txt", "drop me");
    let pa = e.prepare(&ra);
    let pb = e.prepare(&rb);
    fs::write(local(&pa), "unsaved work").unwrap();
    e.check(&doc_id(&pa));
    e.h.ok("profiles.delete", json!({"id": e.pid}));
    let docs = e.h.ok("open.list", json!({}));
    let docs = docs["docs"].as_array().unwrap();
    assert_eq!(docs.len(), 1, "only the clean copy is dropped: {docs:?}");
    assert_eq!(docs[0]["state"], "orphaned");
    assert!(!docs.iter().any(|d| d["id"] == pb["doc"]["id"]));
    // saving is impossible without the profile, but the work can be exported
    let dest = e.h.path("exported");
    fs::create_dir_all(&dest).unwrap();
    let ex = e.h.ok("open.export", json!({"doc_id": doc_id(&pa), "dest_dir": dest.to_string_lossy()}));
    let files: Vec<String> = ex["exported"].as_array().unwrap().iter().map(|x| x.as_str().unwrap().to_string()).collect();
    assert_eq!(files.len(), 2, "current file + one snapshot: {files:?}");
    assert!(files.iter().all(|f| fs::read_to_string(f).unwrap() == "unsaved work"));
    // discarding needs an explicit confirmation
    assert_eq!(e.h.code("open.discard", json!({"doc_id": doc_id(&pa)})), "INVALID_ARGUMENT");
    e.h.ok("open.discard", json!({"doc_id": doc_id(&pa), "confirm": true}));
    assert_eq!(e.h.ok("open.list", json!({}))["docs"].as_array().unwrap().len(), 0);
}

#[test]
fn restart_keeps_documents_and_a_modified_one_is_found_again() {
    let e = env();
    let r = e.put_remote("r.txt", "persisted");
    let p = e.prepare(&r);
    fs::write(local(&p), "unsaved across restart").unwrap();
    e.check(&doc_id(&p));
    // a second engine on the same data dir = the app restarted
    let (tx, rx) = std::sync::mpsc::channel::<String>();
    let tx = std::sync::Mutex::new(tx);
    let eng2 = nd_engine::engine::Engine::new(std::sync::Arc::new(move |s| {
        let _ = tx.lock().unwrap().send(s);
    }));
    let pmk = {
        use base64::Engine as _;
        base64::engine::general_purpose::STANDARD.encode([7u8; 32])
    };
    eng2.submit(&json!({"op": 1, "cmd": "init", "args": {"data_dir": e.h.dir.path().join("data").to_string_lossy(), "pmk_b64": pmk}}).to_string());
    let wait = |op: u64| loop {
        let m: Value = serde_json::from_str(&rx.recv_timeout(std::time::Duration::from_secs(10)).unwrap()).unwrap();
        if m["op"] == op && m["ev"] != "progress" {
            return m;
        }
    };
    assert_eq!(wait(1)["ev"], "ok");
    eng2.submit(&json!({"op": 2, "cmd": "open.list", "args": {}}).to_string());
    let l = wait(2);
    assert_eq!(l["data"]["docs"][0]["state"], "modified", "{l}");
    assert_eq!(l["data"]["docs"][0]["needs_attention"], true);
    assert_eq!(fs::read_to_string(local(&p)).unwrap(), "unsaved across restart");
}

/// Rewrites the sealed workspace record (what a crash in the middle of a save leaves behind).
fn force_state(h: &H, id: &str, st: &str) {
    let key = nd_engine::crypto::derive(&[7u8; 32], "workspace");
    let aad = format!("nextdata-workspace-v1|{id}").into_bytes();
    let f = h.dir.path().join("data").join("workspace").join(id).join("doc.nd");
    let plain = nd_engine::crypto::open(&key, &aad, &fs::read(&f).unwrap()).unwrap();
    let mut v: Value = serde_json::from_slice(&plain).unwrap();
    v["state"] = json!(st);
    fs::write(&f, nd_engine::crypto::seal(&key, &aad, &serde_json::to_vec(&v).unwrap())).unwrap();
}

#[test]
fn uncertain_save_is_settled_by_looking_at_the_remote() {
    // case 1: the save never reached the server -> retryable, nothing lost
    let e = env();
    let r = e.put_remote("u1.txt", "base one");
    let p = e.prepare(&r);
    let id = doc_id(&p);
    fs::write(local(&p), "edit that was being saved").unwrap();
    e.check(&id);
    force_state(&e.h, &id, "uncertain");
    assert_eq!(e.h.code("open.save", json!({"session": e.s, "doc_id": id})), "CONFLICT"); // pending_recovery: no blind retry
    let rec = e.h.ok("open.recover", json!({"session": e.s}));
    assert_eq!(rec["docs"][0]["verdict"], "not_saved", "{rec}");
    assert_eq!(rec["docs"][0]["state"], "save_failed");
    assert_eq!(fs::read_to_string(local(&p)).unwrap(), "edit that was being saved");
    let sv = e.h.ok("open.save", json!({"session": e.s, "doc_id": id}));
    assert_eq!(state(&sv), "clean");
    assert_eq!(e.remote_text(&r), "edit that was being saved");

    // case 2: the server did apply it (the answer was lost) -> recognised, no duplicate upload, now clean
    let r2 = e.put_remote("u2.txt", "base two");
    let p2 = e.prepare(&r2);
    let id2 = doc_id(&p2);
    fs::write(local(&p2), "applied but unacknowledged").unwrap();
    e.check(&id2);
    let v = e.h.ok("session.stat", json!({"session": e.s, "ref": r2}))["version"].clone();
    e.h.ok("session.replace", json!({"session": e.s, "ref": r2, "local_path": local(&p2), "expect_version": v, "base_sha256": nd_engine::crypto::sha256_hex(b"base two")}));
    force_state(&e.h, &id2, "uncertain");
    let rec = e.h.ok("open.recover", json!({"session": e.s}));
    assert_eq!(rec["docs"][0]["verdict"], "saved", "{rec}");
    assert_eq!(rec["docs"][0]["state"], "clean");
    assert_eq!(e.check(&id2)["changed"], false);
    assert_eq!(e.remote_text(&r2), "applied but unacknowledged");
}

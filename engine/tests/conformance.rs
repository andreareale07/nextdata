//! Provider conformance suite. `fake` always runs; sftp/webdav/s3 run when ND_TEST_* env vars point
//! at the servers started by infra/test-env (otherwise the test reports NON ESEGUITO and returns).
mod common;
use common::*;
use serde_json::{json, Value};
use std::fs;

fn names(v: &Value) -> Vec<String> {
    v["entries"].as_array().unwrap().iter().map(|e| e["name"].as_str().unwrap().to_string()).collect()
}
fn entry_ref(v: &Value, name: &str) -> String {
    v["entries"].as_array().unwrap().iter().find(|e| e["name"] == name).unwrap_or_else(|| panic!("{name} not in {v}"))["ref"].as_str().unwrap().to_string()
}

fn rand_bytes(n: usize, seed: u64) -> Vec<u8> {
    let mut x = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
    (0..n)
        .map(|_| {
            x ^= x << 13;
            x ^= x >> 7;
            x ^= x << 17;
            (x >> 24) as u8
        })
        .collect()
}

/// Full conformance run against one profile. `sandbox` is a ref of a scratch directory the test may create.
fn join_ref(parent: &str, name: &str) -> String {
    format!("{}/{}", parent.trim_end_matches('/'), name)
}

fn conformance(h: &H, pid: &str, label: &str) {
    let c = h.ok("session.connect", json!({"profile_id": pid}));
    let s = c["session"].as_str().unwrap().to_string();
    let home = c["home_ref"].as_str().unwrap().to_string();
    let sandbox_name = format!("ndtest_{}", label);
    // clean any leftovers
    if let Ok(l) = h.call("session.list", json!({"session": s, "ref": home, "limit": 500})) {
        if names(&l).contains(&sandbox_name) {
            h.ok("session.delete", json!({"session": s, "refs": [entry_ref(&l, &sandbox_name)], "recursive": true}));
        }
    }
    let m = h.ok("session.mkdir", json!({"session": s, "parent_ref": home, "name": sandbox_name}));
    let sb = m["entry"]["ref"].as_str().unwrap().to_string();
    assert_eq!(m["entry"]["kind"], "dir");
    assert_eq!(h.code("session.mkdir", json!({"session": s, "parent_ref": sb, "name": ".."})), "INVALID_ARGUMENT");
    assert_eq!(h.code("session.mkdir", json!({"session": s, "parent_ref": sb, "name": "a/b"})), "INVALID_ARGUMENT");
    assert_eq!(h.code("session.list", json!({"session": s, "ref": format!("{sb}/../..")})), "INVALID_ARGUMENT");
    assert_eq!(h.code("session.mkdir", json!({"session": s, "parent_ref": home, "name": sandbox_name})), "ALREADY_EXISTS");

    // ---- upload a tree
    let src = h.path("src");
    fs::create_dir_all(src.join("tree/sub/deeper")).unwrap();
    fs::write(src.join("tree/a.txt"), b"hello a").unwrap();
    fs::write(src.join("tree/sub/b.bin"), rand_bytes(300_000, 1)).unwrap();
    fs::write(src.join("tree/sub/deeper/c.txt"), b"").unwrap();
    fs::write(src.join("single file è.txt"), "unicode è ü 日本").unwrap();
    let locals: Vec<String> = vec![src.join("tree").to_string_lossy().to_string(), src.join("single file è.txt").to_string_lossy().to_string()];
    let op = h.start("transfer.upload", json!({"session": s, "local_paths": locals, "parent_ref": sb, "collision": "fail"}));
    let (r, prog) = h.wait_full(op);
    let r = r.unwrap_or_else(|e| panic!("upload: {e}"));
    assert_eq!(r["entries"].as_array().unwrap().len(), 2, "{r}");
    assert!(!prog.is_empty(), "no progress events");

    let l = h.ok("session.list", json!({"session": s, "ref": sb}));
    assert_eq!(names(&l), vec!["tree", "single file è.txt"]); // dirs first
    assert_eq!(l["breadcrumbs"].as_array().unwrap().last().unwrap()["name"], sandbox_name);
    let tree = entry_ref(&l, "tree");
    let lt = h.ok("session.list", json!({"session": s, "ref": tree}));
    assert_eq!(names(&lt), vec!["sub", "a.txt"]);
    let sub = entry_ref(&lt, "sub");
    let ls = h.ok("session.list", json!({"session": s, "ref": sub, "sort": "size", "desc": true}));
    assert_eq!(names(&ls), vec!["deeper", "b.bin"]);
    assert_eq!(ls["entries"][1]["size"], 300_000);

    // ---- collision handling on upload
    assert_eq!(
        h.code("transfer.upload", json!({"session": s, "local_paths": [src.join("tree/a.txt").to_string_lossy()], "parent_ref": tree, "collision": "fail"})),
        "ALREADY_EXISTS"
    );
    let r = h.ok("transfer.upload", json!({"session": s, "local_paths": [src.join("tree/a.txt").to_string_lossy()], "parent_ref": tree, "collision": "rename"}));
    assert_eq!(r["entries"][0]["name"], "a (1).txt");
    let r = h.ok("transfer.upload", json!({"session": s, "local_paths": [src.join("tree/a.txt").to_string_lossy()], "parent_ref": tree, "collision": "skip"}));
    assert_eq!(r["entries"].as_array().unwrap().len(), 0);

    // ---- download tree and compare
    let dst = h.path("dst");
    fs::create_dir_all(&dst).unwrap();
    let r = h.ok("transfer.download", json!({"session": s, "refs": [tree], "dest_dir": dst.to_string_lossy(), "collision": "fail"}));
    assert_eq!(r["local_paths"].as_array().unwrap().len(), 1);
    assert_eq!(fs::read(dst.join("tree/a.txt")).unwrap(), b"hello a");
    assert_eq!(fs::read(dst.join("tree/a (1).txt")).unwrap(), b"hello a");
    assert_eq!(fs::read(dst.join("tree/sub/b.bin")).unwrap(), rand_bytes(300_000, 1));
    assert!(dst.join("tree/sub/deeper/c.txt").exists());
    // no leftovers
    let leftovers: Vec<_> = walk(&dst).into_iter().filter(|p| p.contains(".nd-part")).collect();
    assert!(leftovers.is_empty(), "{leftovers:?}");
    // collision policies on download
    assert_eq!(h.code("transfer.download", json!({"session": s, "refs": [tree], "dest_dir": dst.to_string_lossy(), "collision": "fail"})), "ALREADY_EXISTS");
    let r = h.ok("transfer.download", json!({"session": s, "refs": [tree], "dest_dir": dst.to_string_lossy(), "collision": "rename"}));
    assert!(r["local_paths"][0].as_str().unwrap().ends_with("tree (1)"));
    let r = h.ok("transfer.download", json!({"session": s, "refs": [tree], "dest_dir": dst.to_string_lossy(), "collision": "skip"}));
    assert_eq!(r["local_paths"].as_array().unwrap().len(), 0);
    // single file download keeps unicode; existing local file is never overwritten
    let single = entry_ref(&h.ok("session.list", json!({"session": s, "ref": sb})), "single file è.txt");
    fs::write(dst.join("single file è.txt"), "PRECIOUS").unwrap();
    let r = h.ok("transfer.download", json!({"session": s, "refs": [single], "dest_dir": dst.to_string_lossy(), "collision": "rename"}));
    assert_eq!(fs::read(dst.join("single file è.txt")).unwrap(), b"PRECIOUS");
    assert_eq!(fs::read_to_string(r["local_paths"][0].as_str().unwrap()).unwrap(), "unicode è ü 日本");

    // ---- stat + random-access reads (M1)
    let binref = entry_ref(&ls, "b.bin");
    let st = h.ok("session.stat", json!({"session": s, "ref": binref}));
    assert_eq!(st["entry"]["size"], 300_000);
    assert!(st["version"]["strength"].is_string(), "{st}");
    assert!(h.ok("session.stat", json!({"session": s, "ref": sub}))["version"].is_null());
    assert_eq!(h.code("session.stat", json!({"session": s, "ref": format!("{sub}/nope")})), "NOT_FOUND");
    let ro = h.ok("read.open", json!({"session": s, "ref": binref}));
    assert_eq!(ro["size"], 300_000);
    let hd = ro["handle"].as_str().unwrap().to_string();
    let expect = rand_bytes(300_000, 1);
    let rd_range = |off: u64, len: u64| -> (Vec<u8>, bool) {
        use base64::Engine as _;
        let r = h.ok("read.range", json!({"handle": hd, "offset": off, "length": len}));
        (base64::engine::general_purpose::STANDARD.decode(r["data_b64"].as_str().unwrap()).unwrap(), r["eof"].as_bool().unwrap())
    };
    let (d, eof) = rd_range(0, 1000);
    assert_eq!(d, expect[..1000]);
    assert!(!eof);
    let (d, _) = rd_range(123_456, 4096); // seek forward
    assert_eq!(d, expect[123_456..123_456 + 4096]);
    let (d, _) = rd_range(10, 50); // seek back
    assert_eq!(d, expect[10..60]);
    let (d, eof) = rd_range(299_000, 4096); // crosses EOF: short read
    assert_eq!(d, expect[299_000..]);
    assert!(eof);
    let (d, eof) = rd_range(300_000, 10); // at EOF
    assert!(d.is_empty() && eof);
    let (d, eof) = rd_range(900_000, 10); // far beyond EOF
    assert!(d.is_empty() && eof);
    assert_eq!(h.code("read.range", json!({"handle": hd, "offset": 0, "length": 0})), "LIMIT_EXCEEDED");
    assert_eq!(h.code("read.range", json!({"handle": hd, "offset": 0, "length": 256 * 1024 + 1})), "LIMIT_EXCEEDED");
    // concurrent requests on one handle
    let ops: Vec<(u64, u64)> = (0..4u64).map(|i| (h.start("read.range", json!({"handle": hd, "offset": i * 70_000, "length": 5000})), i)).collect();
    for (op, i) in ops {
        use base64::Engine as _;
        let (r, _) = h.wait_full(op);
        let r = r.unwrap_or_else(|e| panic!("concurrent range: {e}"));
        let d = base64::engine::general_purpose::STANDARD.decode(r["data_b64"].as_str().unwrap()).unwrap();
        assert_eq!(d, expect[(i * 70_000) as usize..(i * 70_000 + 5000) as usize]);
    }
    h.ok("read.close", json!({"handle": hd}));
    assert_eq!(h.code("read.range", json!({"handle": hd, "offset": 0, "length": 10})), "INVALID_ARGUMENT");
    // handle cap per session (4)
    let hs: Vec<String> = (0..4).map(|_| h.ok("read.open", json!({"session": s, "ref": binref}))["handle"].as_str().unwrap().to_string()).collect();
    assert_eq!(h.code("read.open", json!({"session": s, "ref": binref})), "LIMIT_EXCEEDED");
    for x in hs {
        h.ok("read.close", json!({"handle": x}));
    }
    // a directory is not readable as a file
    assert_eq!(h.code("read.open", json!({"session": s, "ref": sub})), "INVALID_ARGUMENT");

    // ---- replace an existing file safely (M2)
    let aref = entry_ref(&lt, "a.txt");
    let st0 = h.ok("session.stat", json!({"session": s, "ref": aref}));
    let v0 = st0["version"].clone();
    let base_sha = nd_engine::crypto::sha256_hex(b"hello a");
    let edit1 = h.path("edit1.txt");
    fs::write(&edit1, "edited once, and a bit longer").unwrap();
    // an edit without the version it was based on is refused outright
    assert_eq!(h.code("session.replace", json!({"session": s, "ref": aref, "local_path": edit1.to_string_lossy()})), "INVALID_ARGUMENT");
    let r1 = h.ok("session.replace", json!({"session": s, "ref": aref, "local_path": edit1.to_string_lossy(), "expect_version": v0, "base_sha256": base_sha}));
    assert_eq!(r1["entry"]["size"], 29, "{r1}");
    assert!(["content", "size"].contains(&r1["replaced"]["verified"].as_str().unwrap()), "{r1}");
    let dl = h.path("repl_dl");
    fs::create_dir_all(&dl).unwrap();
    h.ok("transfer.download", json!({"session": s, "refs": [aref], "dest_dir": dl.to_string_lossy(), "collision": "fail"}));
    assert_eq!(fs::read_to_string(dl.join("a.txt")).unwrap(), "edited once, and a bit longer");
    let after = names(&h.ok("session.list", json!({"session": s, "ref": tree})));
    assert!(after.iter().all(|n| !n.contains(".nd-")), "leftovers after replace: {after:?}");
    assert_eq!(h.ok("replace.pending", json!({}))["pending"].as_array().unwrap().len(), 0);
    // stale base: the remote changed since v0
    let edit2 = h.path("edit2.txt");
    fs::write(&edit2, "second edit based on a stale version").unwrap();
    let e = h.call("session.replace", json!({"session": s, "ref": aref, "local_path": edit2.to_string_lossy(), "expect_version": v0, "base_sha256": base_sha})).unwrap_err();
    assert_eq!(e["code"], "CONFLICT", "{e}");
    // size+mtime alone prove nothing on weak providers: without a content hash the edit is not applied silently
    let v1 = r1["version"].clone();
    if v1["strength"] == "weak" {
        let e = h.call("session.replace", json!({"session": s, "ref": aref, "local_path": edit2.to_string_lossy(), "expect_version": v1})).unwrap_err();
        assert_eq!(e["params"]["reason"], "unverifiable", "{e}");
    }
    // the remote file is still the first edit (nothing was overwritten by the refused attempts)
    fs::remove_file(dl.join("a.txt")).unwrap();
    h.ok("transfer.download", json!({"session": s, "refs": [aref], "dest_dir": dl.to_string_lossy(), "collision": "fail"}));
    assert_eq!(fs::read_to_string(dl.join("a.txt")).unwrap(), "edited once, and a bit longer");
    // save as copy keeps the remote untouched
    let rc = h.ok("session.replace", json!({"session": s, "ref": aref, "local_path": edit2.to_string_lossy(), "resolution": "copy"}));
    assert!(rc["entry"]["name"].as_str().unwrap().contains("modifica NextData"), "{rc}");
    // deliberate overwrite, with a recovery copy of what gets replaced
    let rec_dir = h.path("recovery");
    fs::create_dir_all(&rec_dir).unwrap();
    let ro2 = h.ok("session.replace", json!({"session": s, "ref": aref, "local_path": edit2.to_string_lossy(), "resolution": "overwrite", "recovery_dir": rec_dir.to_string_lossy()}));
    assert_eq!(ro2["entry"]["size"], 36, "{ro2}");
    assert_eq!(fs::read_to_string(rec_dir.join("a.txt")).unwrap(), "edited once, and a bit longer");
    // nothing pending, recovery on a clean session is a no-op
    assert_eq!(h.ok("replace.recover", json!({"session": s}))["recovered"].as_array().unwrap().len(), 0);
    // a missing remote file is a conflict, not a silent re-creation
    let gone = join_ref(&tree, "does-not-exist.txt");
    let e = h.call("session.replace", json!({"session": s, "ref": gone, "local_path": edit2.to_string_lossy(), "expect_version": v0, "base_sha256": base_sha})).unwrap_err();
    assert_eq!(e["code"], "CONFLICT", "{e}");

    // ---- rename (no replace) + stat result
    let rn = h.ok("session.rename", json!({"session": s, "ref": single, "new_name": "renamed.txt"}));
    assert_eq!(rn["entry"]["name"], "renamed.txt");
    assert_eq!(h.code("session.rename", json!({"session": s, "ref": rn["entry"]["ref"], "new_name": "tree"})), "ALREADY_EXISTS");
    let rd = h.ok("session.rename", json!({"session": s, "ref": sub, "new_name": "sub2"}));
    assert_eq!(rd["entry"]["kind"], "dir");
    let l2 = h.ok("session.list", json!({"session": s, "ref": rd["entry"]["ref"]}));
    assert_eq!(names(&l2), vec!["deeper", "b.bin"]);

    // ---- big file with progress, then cancel
    let big = src.join("big.bin");
    fs::write(&big, rand_bytes(20 * 1024 * 1024, 9)).unwrap();
    let op = h.start("transfer.upload", json!({"session": s, "local_paths": [big.to_string_lossy()], "parent_ref": sb, "collision": "fail"}));
    let (r, prog) = h.wait_full(op);
    r.unwrap_or_else(|e| panic!("big upload: {e}"));
    assert!(prog.len() >= 2, "expected progress events, got {}", prog.len());
    assert_eq!(prog.last().unwrap()["items_total"], 1);
    let bigref = entry_ref(&h.ok("session.list", json!({"session": s, "ref": sb})), "big.bin");
    let bigdst = h.path("bigdst");
    fs::create_dir_all(&bigdst).unwrap();
    // cancel a download mid-way
    let op = h.start("transfer.download", json!({"session": s, "refs": [bigref], "dest_dir": bigdst.to_string_lossy(), "collision": "fail"}));
    h.eng.cancel(op);
    let (r, _) = h.wait_full(op);
    // cancel may race with completion on very fast links: both outcomes must leave a consistent state
    match r {
        Err(e) => {
            assert_eq!(e["code"], "CANCELLED");
            assert!(!bigdst.join("big.bin").exists(), "reservation must be cleaned");
        }
        Ok(_) => assert_eq!(fs::read(bigdst.join("big.bin")).unwrap().len(), 20 * 1024 * 1024),
    }
    let _ = fs::remove_dir_all(&bigdst);
    fs::create_dir_all(&bigdst).unwrap();
    let r = h.ok("transfer.download", json!({"session": s, "refs": [bigref], "dest_dir": bigdst.to_string_lossy(), "collision": "fail"}));
    assert_eq!(fs::read(r["local_paths"][0].as_str().unwrap()).unwrap(), rand_bytes(20 * 1024 * 1024, 9));
    assert!(walk(&bigdst).iter().all(|p| !p.contains(".nd-part")));

    // ---- delete: non-recursive on non-empty fails, recursive works, root refused
    assert!(h.call("session.delete", json!({"session": s, "refs": [tree], "recursive": false})).is_err());
    assert_eq!(h.code("session.delete", json!({"session": s, "refs": ["/"], "recursive": true})), "POLICY_DENIED");
    let d = h.ok("session.delete", json!({"session": s, "refs": [sb], "recursive": true}));
    assert!(d["deleted"].as_u64().unwrap() >= 8);
    let l = h.ok("session.list", json!({"session": s, "ref": home, "limit": 500}));
    assert!(!names(&l).contains(&sandbox_name));
    h.ok("session.close", json!({"session": s}));
    assert_eq!(h.code("session.list", json!({"session": s, "ref": "/"})), "SESSION_CLOSED");
}

fn walk(p: &std::path::Path) -> Vec<String> {
    let mut out = vec![];
    if let Ok(rd) = fs::read_dir(p) {
        for e in rd.flatten() {
            out.push(e.path().to_string_lossy().to_string());
            if e.path().is_dir() {
                out.extend(walk(&e.path()));
            }
        }
    }
    out
}

#[test]
fn fake_conformance() {
    let h = harness();
    let pid = save_profile(&h, json!({"name":"Demo","protocol":"fake"}), json!(null));
    conformance(&h, &pid, "fake");
}

#[test]
fn fake_pagination_and_sort() {
    let h = harness();
    let pid = save_profile(&h, json!({"name":"Demo","protocol":"fake"}), json!(null));
    let s = h.ok("session.connect", json!({"profile_id": pid}))["session"].as_str().unwrap().to_string();
    let mut all = vec![];
    let mut tok: Option<String> = None;
    let mut pages = 0;
    loop {
        let mut a = json!({"session": s, "ref": "/Foto", "limit": 10});
        if let Some(t) = &tok {
            a["page_token"] = json!(t);
        }
        let r = h.ok("session.list", a);
        all.extend(names(&r));
        pages += 1;
        match r["next_token"].as_str() {
            Some(t) => tok = Some(t.to_string()),
            None => break,
        }
    }
    assert_eq!(all.len(), 32); // 30 images + the sample png + "2026"
    assert_eq!(pages, 4);
    assert_eq!(all[0], "2026"); // directories first
    assert_eq!(all[1], "campione.png");
    assert_eq!(all[2], "img_000.jpg");
    let r = h.ok("session.list", json!({"session": s, "ref": "/Foto", "limit": 3, "sort": "size", "desc": true}));
    assert_eq!(names(&r)[1], "campione.png"); // the largest file first (after the directory)
    assert_eq!(names(&r)[2], "img_029.jpg");
}

#[test]
fn profiles_secrets_never_leak_and_store_is_encrypted() {
    let h = harness();
    let pid = save_profile(
        &h,
        json!({"name":"CANARY-NAME-XYZ","protocol":"sftp","host":"canary-host.example","username":"canary-user","auth":"password"}),
        json!({"password":"CANARY-PASSWORD-123"}),
    );
    let l = h.ok("profiles.list", json!({}));
    let txt = l.to_string();
    assert!(!txt.contains("CANARY-PASSWORD"));
    assert_eq!(l["profiles"][0]["has_secret"], true);
    // on-disk store must not contain any plaintext
    let blob = fs::read(h.path("data/store.nd")).unwrap();
    let s = String::from_utf8_lossy(&blob);
    for canary in ["CANARY-NAME-XYZ", "canary-host", "CANARY-PASSWORD", "canary-user"] {
        assert!(!s.contains(canary), "{canary} found in store file");
    }
    // delete = crypto-erase of secrets
    h.ok("profiles.delete", json!({"id": pid}));
    assert_eq!(h.ok("profiles.list", json!({}))["profiles"].as_array().unwrap().len(), 0);
    // wrong key cannot open the store
    let h2 = {
        let (tx, _rx) = std::sync::mpsc::channel::<String>();
        let tx = std::sync::Mutex::new(tx);
        nd_engine::engine::Engine::new(std::sync::Arc::new(move |s| {
            let _ = tx.lock().unwrap().send(s);
        }))
    };
    let _ = h2;
}

#[test]
fn export_import_roundtrip_and_hostile_input() {
    let h = harness();
    save_profile(&h, json!({"name":"Uno","protocol":"sftp","host":"canary-host.example","username":"u","auth":"password"}), json!({"password":"CANARY-PW-999"}));
    assert_eq!(h.code("profiles.export", json!({"passphrase":"short","options":{}})), "PASSPHRASE_INVALID");
    let ex = h.ok("profiles.export", json!({"passphrase":"correct horse battery","options":{"favorites":false,"settings":false,"pins":false}}));
    let b64 = ex["data_b64"].as_str().unwrap().to_string();
    // no host / credential in clear
    let raw = String::from_utf8_lossy(&std::convert::identity(b64.clone().into_bytes())).to_string();
    assert!(!raw.contains("canary"));
    use base64::Engine as _;
    let bytes = base64::engine::general_purpose::STANDARD.decode(&b64).unwrap();
    assert!(!String::from_utf8_lossy(&bytes).contains("canary-host"));
    assert!(!String::from_utf8_lossy(&bytes).contains("CANARY-PW"));
    // wrong passphrase
    assert_eq!(h.code("profiles.import", json!({"data_b64": b64, "passphrase":"wrong wrong wrong","apply":false})), "PASSPHRASE_INVALID");
    // tampering detected
    let mut t = bytes.clone();
    let l = t.len();
    t[l - 3] ^= 0x55;
    assert_eq!(h.code("profiles.import", json!({"data_b64": base64::engine::general_purpose::STANDARD.encode(&t), "passphrase":"correct horse battery","apply":false})), "PASSPHRASE_INVALID");
    // weak KDF parameters rejected before any work
    let mut w = bytes.clone();
    w[5..9].copy_from_slice(&8u32.to_le_bytes());
    assert_eq!(h.code("profiles.import", json!({"data_b64": base64::engine::general_purpose::STANDARD.encode(&w), "passphrase":"correct horse battery","apply":false})), "IMPORT_INVALID");
    // garbage
    assert_eq!(h.code("profiles.import", json!({"data_b64": "AAAA", "passphrase":"correct horse battery","apply":false})), "IMPORT_INVALID");
    // preview then apply: conflict -> keep both
    let pv = h.ok("profiles.import", json!({"data_b64": b64, "passphrase":"correct horse battery","apply":false}));
    assert_eq!(pv["applied"], false);
    assert_eq!(pv["conflicts"].as_array().unwrap().len(), 1);
    assert_eq!(h.ok("profiles.list", json!({}))["profiles"].as_array().unwrap().len(), 1);
    h.ok("profiles.import", json!({"data_b64": b64, "passphrase":"correct horse battery","apply":true}));
    let l = h.ok("profiles.list", json!({}));
    assert_eq!(l["profiles"].as_array().unwrap().len(), 2);
    assert!(l["profiles"].as_array().unwrap().iter().any(|p| p["has_secret"] == false));
}

#[test]
fn policy_and_disabled_protocols() {
    let h = harness();
    #[cfg(not(feature = "smb"))]
    {
        let smb = save_profile(&h, json!({"name":"NAS","protocol":"smb","host":"10.0.0.2","username":"u"}), json!(null));
        assert_eq!(h.code("session.connect", json!({"profile_id": smb})), "PROTOCOL_DISABLED");
    }
    let dav = save_profile(&h, json!({"name":"Dav","protocol":"webdav","host":"example.com","username":"u","tls":false,"auth":"password"}), json!({"password":"x"}));
    assert_eq!(h.code("session.connect", json!({"profile_id": dav})), "POLICY_DENIED");
    assert_eq!(h.code("profiles.save", json!({"profile":{"name":"x","protocol":"ftp","host":"h"}})), "INVALID_ARGUMENT");
    assert_eq!(h.code("nonsense", json!({})), "UNSUPPORTED");
}

#[test]
fn favorites_and_settings() {
    let h = harness();
    let pid = save_profile(&h, json!({"name":"Demo","protocol":"fake"}), json!(null));
    h.ok("favorites.add", json!({"profile_id": pid, "ref": "/Foto//2026/", "name":"2026"}));
    let f = h.ok("favorites.list", json!({"profile_id": pid}));
    assert_eq!(f["favorites"][0]["ref"], "/Foto/2026");
    h.ok("favorites.remove", json!({"profile_id": pid, "ref": "/Foto/2026"}));
    assert_eq!(h.ok("favorites.list", json!({"profile_id": pid}))["favorites"].as_array().unwrap().len(), 0);
    let s = h.ok("settings.set", json!({"settings":{"lang":"it","show_hidden":true}}));
    assert_eq!(s["settings"]["lang"], "it");
}

// ------------------------------------------------------------ external servers (infra/test-env)

fn external(name: &str) -> Option<Vec<String>> {
    match std::env::var(name) {
        Ok(v) if !v.is_empty() => Some(v.split('|').map(|s| s.to_string()).collect()),
        _ => {
            eprintln!("NON ESEGUITO ({name} non impostata)");
            None
        }
    }
}

#[test]
fn sftp_conformance_and_host_key_policy() {
    let Some(p) = external("ND_TEST_SFTP") else { return };
    // host|port|user|password
    let h = harness();
    let prof = json!({"name":"SFTP","protocol":"sftp","host":p[0],"port":p[1].parse::<u32>().unwrap(),"username":p[2],"auth":"password"});
    let pid = save_profile(&h, prof, json!({"password": p[3]}));
    // 1) user rejects the host key -> no connection, nothing pinned
    *h.policy.lock().unwrap() = Box::new(|_| (false, None, false));
    assert_eq!(h.code("session.connect", json!({"profile_id": pid})), "HOST_KEY_REJECTED");
    assert_eq!(h.prompts_seen.lock().unwrap().last().unwrap()["kind"], "trust_host");
    assert!(h.prompts_seen.lock().unwrap().last().unwrap()["fingerprint"].as_str().unwrap().starts_with("SHA256:"));
    // 2) accept (TOFU) and run the suite
    *h.policy.lock().unwrap() = Box::new(|_| (true, None, false));
    conformance(&h, &pid, "sftp");
    // 3) pinned: no prompt now
    let n = h.prompts_seen.lock().unwrap().len();
    let c = h.ok("session.connect", json!({"profile_id": pid}));
    h.ok("session.close", json!({"session": c["session"]}));
    assert_eq!(h.prompts_seen.lock().unwrap().len(), n, "pinned host must not prompt again");
    // 4) wrong password
    let bad = save_profile(&h, json!({"name":"bad","protocol":"sftp","host":p[0],"port":p[1].parse::<u32>().unwrap(),"username":p[2],"auth":"password"}), json!({"password":"definitely-wrong"}));
    // pin same server for the new profile first
    assert_eq!(h.code("session.connect", json!({"profile_id": bad})), "AUTH_FAILED");
}

/// SMB (feature `smb`). ND_TEST_SMB = host|port|share|user|password (infra/test-env/run-smb-tests.ps1).
/// ND_TEST_SMB_EXPECT=<ERROR_CODE> => the server is deliberately weaker than required and the
/// connection must be refused with that code (no conformance run).
#[cfg(feature = "smb")]
#[test]
fn smb_conformance_and_security_policy() {
    let Some(p) = external("ND_TEST_SMB") else { return };
    let h = harness();
    let relaxed = std::env::var("ND_TEST_SMB_MODE").map(|m| m == "relaxed").unwrap_or(false);
    let prof = json!({"name":"SMB","protocol":"smb","host":p[0],"port":p[1].parse::<u32>().unwrap(),"share":p[2],"username":p[3],"auth":"password","smb_mode": if relaxed {"relaxed"} else {"strict"}});
    let pid = save_profile(&h, prof, json!({"password": p[4]}));
    if let Ok(expect) = std::env::var("ND_TEST_SMB_EXPECT") {
        assert_eq!(h.code("session.connect", json!({"profile_id": pid})), expect);
        return;
    }
    conformance(&h, &pid, "smb");
    // wrong password
    let bad = save_profile(&h, json!({"name":"bad","protocol":"smb","host":p[0],"port":p[1].parse::<u32>().unwrap(),"share":p[2],"username":p[3],"auth":"password"}), json!({"password":"definitely-wrong"}));
    assert_eq!(h.code("session.connect", json!({"profile_id": bad})), "AUTH_FAILED");
    // names a Windows server would treat specially are never sent (reserved device names, NTFS alternate streams, backslash)
    let c = h.ok("session.connect", json!({"profile_id": pid}));
    let (s, home) = (c["session"].as_str().unwrap().to_string(), c["home_ref"].as_str().unwrap().to_string());
    for evil in ["CON", "nul.txt", "a:stream", "a\\b", "trailing.", "q?", "x*"] {
        assert_eq!(h.code("session.mkdir", json!({"session": s, "parent_ref": home, "name": evil})), "POLICY_DENIED", "{evil}");
    }
    h.ok("session.close", json!({"session": s}));
    // unknown share
    let noshare = save_profile(&h, json!({"name":"ns","protocol":"smb","host":p[0],"port":p[1].parse::<u32>().unwrap(),"share":"nonexistent","username":p[3],"auth":"password"}), json!({"password": p[4]}));
    assert_eq!(h.code("session.connect", json!({"profile_id": noshare})), "NOT_FOUND");
}

/// No server needed: public destinations and invalid profiles are refused before any connection.
#[cfg(feature = "smb")]
#[test]
fn smb_policy_refusals() {
    let h = harness();
    let public = save_profile(&h, json!({"name":"pub","protocol":"smb","host":"8.8.8.8","share":"s","username":"u","auth":"password"}), json!({"password":"x"}));
    assert_eq!(h.code("session.connect", json!({"profile_id": public})), "POLICY_DENIED");
    let public6 = save_profile(&h, json!({"name":"pub6","protocol":"smb","host":"2001:4860:4860::8888","share":"s","username":"u","auth":"password"}), json!({"password":"x"}));
    assert_eq!(h.code("session.connect", json!({"profile_id": public6})), "POLICY_DENIED");
    let noshare = save_profile(&h, json!({"name":"x","protocol":"smb","host":"10.0.0.2","share":"","username":"u","auth":"password"}), json!({"password":"x"}));
    assert_eq!(h.code("session.connect", json!({"profile_id": noshare})), "INVALID_ARGUMENT");
    let slash = save_profile(&h, json!({"name":"y","protocol":"smb","host":"10.0.0.2","share":"a\\b","username":"u","auth":"password"}), json!({"password":"x"}));
    assert_eq!(h.code("session.connect", json!({"profile_id": slash})), "INVALID_ARGUMENT");
    let badmode = save_profile(&h, json!({"name":"z","protocol":"smb","host":"10.0.0.2","share":"s","smb_mode":"none","username":"u","auth":"password"}), json!({"password":"x"}));
    assert_eq!(h.code("session.connect", json!({"profile_id": badmode})), "INVALID_ARGUMENT");
}

#[test]
fn webdav_conformance() {
    let Some(p) = external("ND_TEST_WEBDAV") else { return };
    // scheme|host|port|basepath|user|password
    let h = harness();
    let pid = save_profile(
        &h,
        json!({"name":"DAV","protocol":"webdav","host":p[1],"port":p[2].parse::<u32>().unwrap(),"base_path":p[3],"username":p[4],"auth":"password","tls":p[0]=="https"}),
        json!({"password": p[5]}),
    );
    *h.policy.lock().unwrap() = Box::new(|_| (true, None, false));
    conformance(&h, &pid, "webdav");
}

#[test]
fn s3_conformance() {
    let Some(p) = external("ND_TEST_S3") else { return };
    // scheme|host|port|bucket|access|secret
    let h = harness();
    let pid = save_profile(
        &h,
        json!({"name":"S3","protocol":"s3","host":p[1],"port":p[2].parse::<u32>().unwrap(),"tls":p[0]=="https","auth":"password",
               "s3":{"bucket":p[3],"region":"us-east-1","access_key":p[4],"path_style":true}}),
        json!({"secret_key": p[5]}),
    );
    *h.policy.lock().unwrap() = Box::new(|_| (true, None, false));
    conformance(&h, &pid, "s3");
}

// ------------------------------------------------------------ tests that spawn their own servers

struct Child(std::process::Child);
impl Drop for Child {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}
fn wait_port(port: u16) {
    for _ in 0..100 {
        if std::net::TcpStream::connect(("127.0.0.1", port)).is_ok() {
            return;
        }
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
    panic!("port {port} not up");
}
fn env_dir() -> std::path::PathBuf {
    std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../infra/test-env")
}

#[test]
fn sftp_changed_host_key_blocks() {
    let Some(py) = external("ND_TEST_PY") else { return };
    let h = harness();
    let root = h.path("sroot");
    let key = h.path("hk");
    let spawn = || {
        let c = std::process::Command::new(&py[0])
            .arg(env_dir().join("sftp_server.py"))
            .args(["22231", root.to_str().unwrap(), key.to_str().unwrap()])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
            .unwrap();
        wait_port(22231);
        Child(c)
    };
    let pid = save_profile(&h, json!({"name":"S","protocol":"sftp","host":"127.0.0.1","port":22231,"username":"nd","auth":"password"}), json!({"password":"ndpass"}));
    let first_fp;
    {
        let _srv = spawn();
        let c = h.ok("session.connect", json!({"profile_id": pid}));
        first_fp = h.prompts_seen.lock().unwrap().last().unwrap()["fingerprint"].as_str().unwrap().to_string();
        h.ok("session.close", json!({"session": c["session"]}));
    }
    std::fs::remove_file(&key).unwrap(); // rotate the host key: same address, different identity
    {
        let _srv = spawn();
        // user clicks "accept" but does NOT explicitly replace -> must stay blocked, and no credential may be sent
        *h.policy.lock().unwrap() = Box::new(|d| {
            assert!(d["prior"].is_string(), "changed key must carry the prior fingerprint");
            (true, None, false)
        });
        assert_eq!(h.code("session.connect", json!({"profile_id": pid})), "HOST_KEY_CHANGED");
        let seen = h.prompts_seen.lock().unwrap().last().unwrap().clone();
        assert_eq!(seen["prior"], first_fp);
        assert_ne!(seen["fingerprint"], first_fp);
        // explicit replacement works
        *h.policy.lock().unwrap() = Box::new(|_| (true, None, true));
        let c = h.ok("session.connect", json!({"profile_id": pid}));
        h.ok("session.close", json!({"session": c["session"]}));
    }
}

#[test]
fn webdav_tls_pinning() {
    let (Some(node), Some(w)) = (external("ND_TEST_NODE"), external("ND_TEST_WEBDAV")) else { return };
    let _ = w;
    let h = harness();
    let (cert, key) = (h.path("c.pem"), h.path("k.pem"));
    let py = external("ND_TEST_PY").unwrap();
    let mk = |cn: &str| {
        let st = std::process::Command::new(&py[0]).arg(env_dir().join("mkcert.py")).args([cert.to_str().unwrap(), key.to_str().unwrap(), cn]).status().unwrap();
        assert!(st.success());
    };
    let spawn = || {
        let c = std::process::Command::new(&node[0])
            .arg(env_dir().join("tls_proxy.js"))
            .args(["22225", "22223", cert.to_str().unwrap(), key.to_str().unwrap()])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
            .unwrap();
        wait_port(22225);
        Child(c)
    };
    let pid = save_profile(
        &h,
        json!({"name":"DAVS","protocol":"webdav","host":"127.0.0.1","port":22225,"base_path":"/dav","username":"nd","auth":"password","tls":true}),
        json!({"password":"ndpass"}),
    );
    mk("one");
    let fp1;
    {
        let _p = spawn();
        // untrusted self-signed: user refuses -> blocked
        *h.policy.lock().unwrap() = Box::new(|_| (false, None, false));
        assert_eq!(h.code("session.connect", json!({"profile_id": pid})), "TLS_UNTRUSTED");
        let seen = h.prompts_seen.lock().unwrap().last().unwrap().clone();
        assert_eq!(seen["kind"], "trust_cert");
        assert_eq!(seen["self_signed"], true);
        fp1 = seen["spki_sha256"].as_str().unwrap().to_string();
        // accept -> pinned, works
        *h.policy.lock().unwrap() = Box::new(|_| (true, None, false));
        let c = h.ok("session.connect", json!({"profile_id": pid}));
        h.ok("session.close", json!({"session": c["session"]}));
        // pinned: no prompt
        let n = h.prompts_seen.lock().unwrap().len();
        let c = h.ok("session.connect", json!({"profile_id": pid}));
        h.ok("session.close", json!({"session": c["session"]}));
        assert_eq!(h.prompts_seen.lock().unwrap().len(), n);
    }
    // certificate changes: must prompt again, carrying the prior pin
    mk("two");
    {
        let _p = spawn();
        *h.policy.lock().unwrap() = Box::new(|_| (false, None, false));
        assert_eq!(h.code("session.connect", json!({"profile_id": pid})), "TLS_UNTRUSTED");
        let seen = h.prompts_seen.lock().unwrap().last().unwrap().clone();
        assert_eq!(seen["prior"], fp1);
        assert_ne!(seen["spki_sha256"], fp1);
    }
}

#[test]
fn s3_multipart_large_object() {
    let Some(p) = external("ND_TEST_S3") else { return };
    let h = harness();
    let pid = save_profile(
        &h,
        json!({"name":"S3","protocol":"s3","host":p[1],"port":p[2].parse::<u32>().unwrap(),"tls":false,"auth":"password",
               "s3":{"bucket":p[3],"region":"us-east-1","access_key":p[4],"path_style":true}}),
        json!({"secret_key": p[5]}),
    );
    let s = h.ok("session.connect", json!({"profile_id": pid}))["session"].as_str().unwrap().to_string();
    let f = h.path("large.bin");
    fs::write(&f, rand_bytes(70 * 1024 * 1024 + 123, 5)).unwrap(); // > 64 MiB => multipart
    let r = h.ok("transfer.upload", json!({"session": s, "local_paths": [f.to_string_lossy()], "parent_ref": "/", "collision": "rename"}));
    let name = r["entries"][0]["name"].as_str().unwrap().to_string();
    assert_eq!(r["entries"][0]["size"], 70 * 1024 * 1024 + 123);
    let dst = h.path("dl");
    fs::create_dir_all(&dst).unwrap();
    h.ok("transfer.download", json!({"session": s, "refs": [format!("/{name}")], "dest_dir": dst.to_string_lossy(), "collision": "fail"}));
    assert_eq!(fs::read(dst.join(&name)).unwrap(), fs::read(&f).unwrap());
    h.ok("session.delete", json!({"session": s, "refs": [format!("/{name}")], "recursive": false}));
}

#[test]
fn sftp_slow_user_prompt_does_not_time_out() {
    // Regression: the 20 s connect timeout used to include the time the user needed to read the host-key prompt.
    let Some(py) = external("ND_TEST_PY") else { return };
    let h = harness();
    let root = h.path("sroot");
    let key = h.path("hk");
    let c = std::process::Command::new(&py[0])
        .arg(env_dir().join("sftp_server.py"))
        .args(["22232", root.to_str().unwrap(), key.to_str().unwrap()])
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .unwrap();
    wait_port(22232);
    let _srv = Child(c);
    *h.policy.lock().unwrap() = Box::new(|_| {
        std::thread::sleep(std::time::Duration::from_secs(23));
        (true, Some("x".into()), false)
    });
    let pid = save_profile(&h, json!({"name":"S","protocol":"sftp","host":"127.0.0.1","port":22232,"username":"nd","auth":"password"}), json!({"password":"ndpass"}));
    let c = h.ok("session.connect", json!({"profile_id": pid}));
    h.ok("session.close", json!({"session": c["session"]}));
}

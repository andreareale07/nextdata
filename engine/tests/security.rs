//! Security test suite (hostile servers, hostile input, storage tampering, crypto invariants, fuzz-style loops).
//! Tests needing external helper servers are gated by env vars set by infra/test-env/run-security.ps1;
//! when unset they print "NON ESEGUITO (...)" and return.
mod common;
use common::*;
use nd_engine::error::Code;
use nd_engine::pathguard;
use serde_json::{json, Value};
use std::fs;
use std::sync::Arc;

fn external(name: &str) -> Option<Vec<String>> {
    match std::env::var(name) {
        Ok(v) if !v.is_empty() => Some(v.split('|').map(|s| s.to_string()).collect()),
        _ => {
            eprintln!("NON ESEGUITO ({name} non impostata)");
            None
        }
    }
}
fn env_dir() -> std::path::PathBuf {
    std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../infra/test-env")
}
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

struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
    fn below(&mut self, n: usize) -> usize {
        (self.next() % n as u64) as usize
    }
}

fn names(v: &Value) -> Vec<String> {
    v["entries"].as_array().unwrap().iter().map(|e| e["name"].as_str().unwrap().to_string()).collect()
}

// ---------------------------------------------------------------------------------------------
// 1. Hostile WebDAV server
// ---------------------------------------------------------------------------------------------

fn evil_profile(h: &H, base: &str, port: u16) -> String {
    save_profile(
        h,
        json!({"name":"evil","protocol":"webdav","host":"127.0.0.1","port":port,"base_path":base,"username":"u","auth":"password","tls":false}),
        json!({"password":"CANARY-EVIL-PW"}),
    )
}

#[test]
fn hostile_webdav_server() {
    let Some(p) = external("ND_TEST_EVIL") else { return };
    let (port, port2, log) = (p[0].parse::<u16>().unwrap(), p[1].parse::<u16>().unwrap(), p[2].clone());
    let h = harness();

    // XXE / entity bomb: DOCTYPE must be refused, nothing resolved or fetched
    for base in ["/xxe", "/bomb"] {
        let pid = evil_profile(&h, base, port);
        let s = h.ok("session.connect", json!({"profile_id": pid}))["session"].as_str().unwrap().to_string();
        assert_eq!(h.code("session.list", json!({"session": s, "ref": "/"})), "SERVER_ANOMALY", "{base}");
    }
    // endless multistatus: bounded by the response cap
    let pid = evil_profile(&h, "/huge", port);
    let s = h.ok("session.connect", json!({"profile_id": pid}))["session"].as_str().unwrap().to_string();
    assert_eq!(h.code("session.list", json!({"session": s, "ref": "/"})), "LIMIT_EXCEEDED");

    // traversal / spoofing hrefs
    let pid = evil_profile(&h, "/trav", port);
    let s = h.ok("session.connect", json!({"profile_id": pid}))["session"].as_str().unwrap().to_string();
    let l = h.ok("session.list", json!({"session": s, "ref": "/"}));
    let n = names(&l);
    for name in &n {
        assert!(!name.contains('/') && !name.contains("..") && name != ".", "hostile name accepted: {name}");
    }
    assert!(n.contains(&"ok.txt".to_string()));
    assert!(!n.iter().any(|x| x.contains("evil") || x.contains("passwd") || x.contains("leak")), "{n:?}");
    assert_eq!(n.iter().filter(|x| *x == "dup.txt").count(), 1, "duplicate names must collapse");
    let con = l["entries"].as_array().unwrap().iter().find(|e| e["name"] == "CON").unwrap();
    assert_eq!(con["safe_local_name"], "_CON");

    // cross-origin redirect: refused, and the credential never reaches the other origin
    let pid = evil_profile(&h, "/redir", port);
    let r = h.call("session.connect", json!({"profile_id": pid}));
    assert!(r.is_err(), "redirect to another origin must not succeed");
    std::thread::sleep(std::time::Duration::from_millis(300));
    let cap = fs::read_to_string(&log).unwrap_or_default();
    assert!(!cap.to_lowercase().contains("authorization"), "credentials leaked to another origin: {cap}");
    assert!(cap.trim().is_empty(), "no request may reach the redirect target: {cap}");

    // size lies: more data than declared -> SERVER_ANOMALY, nothing published
    let pid = evil_profile(&h, "/lie", port);
    let s = h.ok("session.connect", json!({"profile_id": pid}))["session"].as_str().unwrap().to_string();
    let dst = h.path("lie");
    fs::create_dir_all(&dst).unwrap();
    assert_eq!(h.code("transfer.download", json!({"session": s, "refs": ["/f.bin"], "dest_dir": dst.to_string_lossy(), "collision": "fail"})), "SERVER_ANOMALY");
    assert!(fs::read_dir(&dst).unwrap().next().is_none(), "no file may remain after an anomalous transfer");
    // fewer bytes than declared -> error, and the partial is never presented as the final file
    let pid = evil_profile(&h, "/short", port);
    let s = h.ok("session.connect", json!({"profile_id": pid}))["session"].as_str().unwrap().to_string();
    let dst = h.path("short");
    fs::create_dir_all(&dst).unwrap();
    assert!(h.call("transfer.download", json!({"session": s, "refs": ["/f.bin"], "dest_dir": dst.to_string_lossy(), "collision": "fail"})).is_err());
    assert!(!dst.join("f.bin").exists(), "truncated download must not appear as final");

    // no secret ever appears in an event
    let _ = port2;
    for e in h.raw_events.lock().unwrap().iter() {
        assert!(!e.contains("CANARY-EVIL-PW"), "secret leaked in event: {e}");
    }
}

// ---------------------------------------------------------------------------------------------
// 2. SSH: credentials only after host verification; weak algorithms refused
// ---------------------------------------------------------------------------------------------

fn spawn_sftp(py: &str, port: u16, root: &std::path::Path, key: &std::path::Path, auth_log: &std::path::Path, weak: bool) -> Child {
    let mut c = std::process::Command::new(py);
    c.arg(env_dir().join("sftp_server.py")).args([&port.to_string(), root.to_str().unwrap(), key.to_str().unwrap()]);
    c.env("ND_AUTH_LOG", auth_log);
    if weak {
        c.env("ND_WEAK", "1");
    }
    let ch = c.stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null()).spawn().unwrap();
    wait_port(port);
    Child(ch)
}

#[test]
fn ssh_credentials_only_after_host_key_acceptance() {
    let Some(py) = external("ND_TEST_PY") else { return };
    let h = harness();
    let auth_log = h.path("auth.log");
    let _srv = spawn_sftp(&py[0], 22241, &h.path("r1"), &h.path("k1"), &auth_log, false);
    let pid = save_profile(&h, json!({"name":"s","protocol":"sftp","host":"127.0.0.1","port":22241,"username":"nd","auth":"password"}), json!({"password":"ndpass"}));
    *h.policy.lock().unwrap() = Box::new(|_| (false, None, false));
    assert_eq!(h.code("session.connect", json!({"profile_id": pid})), "HOST_KEY_REJECTED");
    assert!(fs::read_to_string(&auth_log).unwrap_or_default().is_empty(), "a credential reached the server before the host key was accepted");
    // a prompt-less cancel (user closes the dialog) must behave the same way
    let op = h.start("session.connect", json!({"profile_id": pid}));
    *h.policy.lock().unwrap() = Box::new(|_| (false, None, false));
    let (r, _) = h.wait_full(op);
    assert!(r.is_err());
    assert!(fs::read_to_string(&auth_log).unwrap_or_default().is_empty());
    // accepted -> now the credential is sent
    *h.policy.lock().unwrap() = Box::new(|_| (true, None, false));
    h.ok("session.close", json!({"session": h.ok("session.connect", json!({"profile_id": pid}))["session"]}));
    assert!(fs::read_to_string(&auth_log).unwrap().contains("auth-attempt"));
    for e in h.raw_events.lock().unwrap().iter() {
        assert!(!e.contains("ndpass"), "secret leaked in event: {e}");
    }
}

#[test]
fn ssh_weak_algorithms_refused() {
    let Some(py) = external("ND_TEST_PY") else { return };
    let h = harness();
    let auth_log = h.path("auth.log");
    let _srv = spawn_sftp(&py[0], 22242, &h.path("r2"), &h.path("k2"), &auth_log, true);
    let pid = save_profile(&h, json!({"name":"w","protocol":"sftp","host":"127.0.0.1","port":22242,"username":"nd","auth":"password"}), json!({"password":"ndpass"}));
    *h.policy.lock().unwrap() = Box::new(|_| (true, None, false));
    let r = h.call("session.connect", json!({"profile_id": pid}));
    let code = r.as_ref().err().map(|e| e["code"].as_str().unwrap_or("").to_string());
    assert!(r.is_err(), "a SHA-1/CBC/group14-sha1/ssh-rsa-only server must be refused");
    assert!(h.prompts_seen.lock().unwrap().is_empty(), "no host-key prompt may be shown for a downgraded session ({code:?})");
    assert!(fs::read_to_string(&auth_log).unwrap_or_default().is_empty(), "credential sent over weak crypto");
}

// ---------------------------------------------------------------------------------------------
// 3. Local confinement with hostile remote names (provider-level)
// ---------------------------------------------------------------------------------------------

#[tokio::test]
async fn hostile_remote_names_stay_inside_destination() {
    use nd_engine::provider::fake::FakeProvider;
    use nd_engine::provider::{Progress, Provider};
    use nd_engine::transfer::{self, Collision, Reporter};
    let fp = FakeProvider::new();
    let evil = ["..\\..\\evil.txt", "CON", "a:b.txt", "x\u{202e}txt.exe", "trail. ", "nul.txt", "C:\\Windows\\win.ini", "..", "~root", "very\u{7f}ctl"];
    for (i, n) in evil.iter().enumerate() {
        if *n == ".." {
            continue; // not even representable as a provider entry
        }
        fp.put_file(&format!("/{n}"), format!("data{i}").as_bytes());
    }
    fp.injected.lock().unwrap().extend(["../escape.txt".to_string(), "a/b".to_string(), "".to_string(), "..".to_string(), "x\0y".to_string()]);
    let tmp = tempfile::tempdir().unwrap();
    let outer = tmp.path().join("outer");
    let dest = outer.join("dest");
    fs::create_dir_all(&dest).unwrap();
    let raw = fp.list("/").await.unwrap();
    let (entries, anomalies) = pathguard::sanitize_listing("/", raw);
    assert!(anomalies >= 5, "hostile entries must be counted as anomalies, got {anomalies}");
    let refs: Vec<String> = entries.iter().map(|e| e.ref_.clone()).collect();
    let rep = Reporter { pr: Progress::none(), total: Default::default(), on_item: Arc::new(|_, _, _| {}) };
    let out = transfer::download(fp.as_ref(), &refs, dest.to_str().unwrap(), Collision::Rename, &rep).await.unwrap();
    assert_eq!(out.len(), refs.len());
    // nothing outside `dest`
    let outer_entries: Vec<_> = fs::read_dir(&outer).unwrap().flatten().map(|e| e.file_name().to_string_lossy().to_string()).collect();
    assert_eq!(outer_entries, vec!["dest"], "file escaped the destination: {outer_entries:?}");
    assert!(!tmp.path().join("evil.txt").exists() && !tmp.path().join("escape.txt").exists());
    for p in &out {
        let p = std::path::Path::new(p);
        assert_eq!(p.parent().unwrap(), dest, "{p:?}");
        let n = p.file_name().unwrap().to_string_lossy().to_string();
        assert!(!n.chars().any(|c| "<>:\"/\\|?*".contains(c) || (c as u32) < 32), "unsafe local name {n:?}");
        assert!(!n.ends_with('.') && !n.ends_with(' '), "{n:?}");
    }
}

#[tokio::test]
async fn local_symlinks_are_never_followed_on_upload() {
    use nd_engine::provider::fake::FakeProvider;
    use nd_engine::provider::Progress;
    use nd_engine::transfer::{self, Collision, Reporter};
    let tmp = tempfile::tempdir().unwrap();
    let secret = tmp.path().join("secret.txt");
    fs::write(&secret, "TOP-SECRET").unwrap();
    let tree = tmp.path().join("tree");
    fs::create_dir_all(&tree).unwrap();
    fs::write(tree.join("ok.txt"), "ok").unwrap();
    #[cfg(unix)]
    let linked = std::os::unix::fs::symlink(&secret, tree.join("link.txt")).is_ok();
    #[cfg(windows)]
    let linked = std::os::windows::fs::symlink_file(&secret, tree.join("link.txt")).is_ok();
    if !linked {
        eprintln!("NON ESEGUITO (impossibile creare symlink: privilegi insufficienti su questo host)");
        return;
    }
    let fp = FakeProvider::new();
    let rep = Reporter { pr: Progress::none(), total: Default::default(), on_item: Arc::new(|_, _, _| {}) };
    transfer::upload(fp.as_ref(), &[tree.to_string_lossy().to_string()], "/", Collision::Fail, &rep).await.unwrap();
    assert!(fp.exists_sync("/tree/ok.txt"));
    assert!(!fp.exists_sync("/tree/link.txt"), "a local symlink was followed/uploaded");
}

// ---------------------------------------------------------------------------------------------
// 4. Storage tampering and crypto invariants
// ---------------------------------------------------------------------------------------------

#[test]
fn store_tamper_wrong_key_truncation() {
    use nd_engine::store::Store;
    let d = tempfile::tempdir().unwrap();
    let dir = d.path().to_str().unwrap();
    let pmk = [9u8; 32];
    {
        let mut s = Store::open(dir, &pmk).unwrap();
        s.data.settings.insert("k".into(), json!("v"));
        s.save().unwrap();
    }
    assert!(Store::open(dir, &[8u8; 32]).is_err(), "wrong key must not open the store");
    assert!(Store::open(dir, &[9u8; 16]).is_err(), "short PMK rejected");
    let path = d.path().join("store.nd");
    let orig = fs::read(&path).unwrap();
    for i in [0usize, 5, 12, orig.len() / 2, orig.len() - 1] {
        let mut t = orig.clone();
        t[i] ^= 0x01;
        fs::write(&path, &t).unwrap();
        assert!(Store::open(dir, &pmk).is_err(), "flip at {i} undetected");
    }
    for cut in [0usize, 1, 11, 27, orig.len() - 1] {
        fs::write(&path, &orig[..cut]).unwrap();
        assert!(Store::open(dir, &pmk).is_err(), "truncation to {cut} undetected");
    }
    fs::write(&path, &orig).unwrap();
    assert!(Store::open(dir, &pmk).is_ok());
}

#[test]
fn aead_nonces_unique_and_kdf_domain_separation() {
    use nd_engine::crypto;
    let k = crypto::derive(&[1u8; 32], "store");
    let mut seen = std::collections::HashSet::new();
    for _ in 0..20_000 {
        let blob = crypto::seal(&k, b"aad", b"x");
        assert!(seen.insert(blob[..12].to_vec()), "nonce reuse");
    }
    assert_ne!(crypto::derive(&[1u8; 32], "store"), crypto::derive(&[1u8; 32], "cache"));
    assert_ne!(crypto::derive(&[1u8; 32], "store"), crypto::derive(&[2u8; 32], "store"));
    // AAD binding
    let b = crypto::seal(&k, b"profile-1", b"secret");
    assert!(crypto::open(&k, b"profile-2", &b).is_err());
}

// ---------------------------------------------------------------------------------------------
// 5. Prompt integrity
// ---------------------------------------------------------------------------------------------

#[test]
fn prompt_reply_validation() {
    let h = harness();
    assert_eq!(h.code("prompt.reply", json!({"prompt_id":"p999","accept":true})), "INVALID_ARGUMENT");
    assert_eq!(h.code("prompt.reply", json!({"accept":true})), "INVALID_ARGUMENT");
}

// ---------------------------------------------------------------------------------------------
// 6. Fuzz-style loops (deterministic seeds)
// ---------------------------------------------------------------------------------------------

#[test]
fn fuzz_command_surface_never_panics_or_hangs() {
    let h = harness();
    let fake = save_profile(&h, json!({"name":"d","protocol":"fake"}), json!(null));
    let s = h.ok("session.connect", json!({"profile_id": fake}))["session"].as_str().unwrap().to_string();
    let cmds = [
        "profiles.list", "profiles.save", "profiles.delete", "profiles.export", "profiles.import", "settings.get", "settings.set", "favorites.list",
        "favorites.add", "favorites.remove", "session.connect", "session.list", "session.mkdir", "session.rename", "session.delete", "session.close",
        "transfer.download", "transfer.upload", "prompt.reply", "init", "version", "nope",
    ];
    let keys = ["session", "ref", "refs", "parent_ref", "name", "new_name", "recursive", "dest_dir", "local_paths", "collision", "profile_id", "profile", "id", "page_token", "limit", "sort", "desc", "passphrase", "data_b64", "apply", "settings", "prompt_id", "accept", "options", "secrets"];
    let evil_strs = [
        "", "/", "..", "../..", "/a/../b", "a\0b", "\u{202e}", "C:\\", "\\\\?\\C:\\x", "%00", "A".repeat(10_000).leak() as &str, "-1", "99999999999999999999",
        "/proc/self/fd/0", "con", "😀", "{\"a\":1}", "null",
    ];
    let mut rng = Rng(0x9E3779B97F4A7C15);
    let mut internal = vec![];
    for i in 0..3000 {
        let cmd = cmds[rng.below(cmds.len())];
        let mut args = serde_json::Map::new();
        for _ in 0..rng.below(6) {
            let k = keys[rng.below(keys.len())];
            let v: Value = match rng.below(8) {
                0 => json!(null),
                1 => json!(rng.next() as i64),
                2 => json!(rng.next() % 2 == 0),
                3 => json!([evil_strs[rng.below(evil_strs.len())], rng.next() % 5]),
                4 => json!({"x": evil_strs[rng.below(evil_strs.len())]}),
                5 => json!(s.clone()),
                _ => json!(evil_strs[rng.below(evil_strs.len())]),
            };
            args.insert(k.to_string(), v);
        }
        // never let the fuzzer re-initialise the engine with a real directory
        if cmd == "init" {
            continue;
        }
        let r = h.call(cmd, Value::Object(args.clone()));
        if let Err(e) = r {
            if e["code"] == "INTERNAL" {
                internal.push(format!("{i}: {cmd} {} -> {e}", Value::Object(args)));
            }
        }
    }
    assert!(internal.is_empty(), "INTERNAL errors (panics/bugs):\n{}", internal.join("\n"));
    // raw garbage envelopes
    for bad in ["", "{", "null", "[]", "{\"op\":0}", "{\"op\":1,\"cmd\":5}", "\u{0}", "{\"op\":18446744073709551615,\"cmd\":\"version\"}"] {
        h.eng.submit(bad);
    }
    // the engine is still alive
    assert_eq!(h.ok("version", json!({}))["api_version"], 1);
}

#[test]
fn fuzz_parsers_never_panic_and_hold_invariants() {
    use nd_engine::provider::{s3::parse_list, webdav::parse_multistatus};
    let mut rng = Rng(0xDEADBEEFCAFEF00D);
    let alphabet: Vec<char> = "ab./\\:*?\"<>|\0 \t\n%&;#x1.. \u{202e}\u{7f}é日😀CONcomLPT9".chars().collect();
    for _ in 0..50_000 {
        let len = rng.below(40);
        let s: String = (0..len).map(|_| alphabet[rng.below(alphabet.len())]).collect();
        let l = pathguard::safe_local_name(&s);
        assert!(!l.is_empty() && l.len() <= 200, "{s:?} -> {l:?}");
        assert!(l != "." && l != "..", "{s:?} -> {l:?}");
        assert!(!l.chars().any(|c| "<>:\"/\\|?*".contains(c) || (c as u32) < 32 || c == '\u{7f}'), "{s:?} -> {l:?}");
        assert!(!l.ends_with('.') && !l.ends_with(' '), "{s:?} -> {l:?}");
        let stem = l.split('.').next().unwrap().to_ascii_uppercase();
        assert!(!["CON", "PRN", "AUX", "NUL", "COM1", "LPT1", "LPT9", "COM9"].contains(&stem.as_str()), "{s:?} -> {l:?}");
        if let Ok(r) = pathguard::norm_ref(&s) {
            assert!(r.starts_with('/') && !r.contains("//") && !r.split('/').any(|x| x == ".." || x == "."), "{s:?} -> {r:?}");
        }
        let _ = pathguard::validate_user_name(&s);
        let _ = pathguard::numbered(&s, 3);
    }
    // XML: random mutations of valid documents
    let seeds: [&[u8]; 2] = [
        br#"<?xml version="1.0"?><D:multistatus xmlns:D="DAV:"><D:response><D:href>/a%20b</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype><D:getcontentlength>3</D:getcontentlength></D:prop></D:propstat></D:response></D:multistatus>"#,
        br#"<ListBucketResult><IsTruncated>true</IsTruncated><Contents><Key>a/b</Key><Size>5</Size><LastModified>2023-11-14T22:13:20.000Z</LastModified></Contents><CommonPrefixes><Prefix>p/</Prefix></CommonPrefixes></ListBucketResult>"#,
    ];
    for _ in 0..30_000 {
        let mut d = seeds[rng.below(2)].to_vec();
        for _ in 0..1 + rng.below(6) {
            if d.is_empty() {
                break;
            }
            let i = rng.below(d.len());
            match rng.below(4) {
                0 => d[i] = rng.next() as u8,
                1 => {
                    d.remove(i);
                }
                2 => d.insert(i, rng.next() as u8),
                _ => d.truncate(i),
            }
        }
        let _ = parse_multistatus(&d, "/dav");
        let _ = parse_multistatus(&d, "");
        let _ = parse_list(&d);
    }
}

#[test]
fn import_fuzz_never_panics() {
    let h = harness();
    let mut rng = Rng(42);
    use base64::Engine as _;
    for _ in 0..300 {
        let len = rng.below(200);
        let mut bytes: Vec<u8> = (0..len).map(|_| rng.next() as u8).collect();
        if len > 5 && rng.below(2) == 0 {
            bytes[..5].copy_from_slice(b"NDPF1");
        }
        let b64 = base64::engine::general_purpose::STANDARD.encode(&bytes);
        let r = h.call("profiles.import", json!({"data_b64": b64, "passphrase": "correct horse battery", "apply": false}));
        let c = r.unwrap_err()["code"].as_str().unwrap().to_string();
        assert!(["IMPORT_INVALID", "PASSPHRASE_INVALID", "LIMIT_EXCEEDED", "INVALID_ARGUMENT"].contains(&c.as_str()), "{c}");
    }
}

#[test]
fn error_codes_are_stable_strings() {
    // codes cross the bridge as SCREAMING_SNAKE_CASE; the UI maps them 1:1 to localized text
    for c in [Code::HostKeyChanged, Code::TlsUntrusted, Code::PolicyDenied, Code::ServerAnomaly, Code::LimitExceeded] {
        let s = serde_json::to_string(&c).unwrap();
        assert!(s.chars().all(|ch| ch == '"' || ch.is_ascii_uppercase() || ch == '_'), "{s}");
    }
}

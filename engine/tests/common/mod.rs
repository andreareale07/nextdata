#![allow(dead_code)]
use nd_engine::engine::Engine;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::mpsc::{channel, Receiver};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

pub type PromptPolicy = Box<dyn Fn(&Value) -> (bool, Option<String>, bool) + Send + Sync>;

pub struct H {
    pub eng: Arc<Engine>,
    rx: Mutex<Receiver<String>>,
    stash: Mutex<HashMap<u64, Vec<Value>>>,
    next: Mutex<u64>,
    pub policy: Mutex<PromptPolicy>,
    pub prompts_seen: Mutex<Vec<Value>>,
    pub raw_events: Mutex<Vec<String>>,
    pub dir: tempfile::TempDir,
}

pub fn harness() -> H {
    let (tx, rx) = channel::<String>();
    let tx = Mutex::new(tx);
    let eng = Engine::new(Arc::new(move |s| {
        let _ = tx.lock().unwrap().send(s);
    }));
    let h = H {
        eng,
        rx: Mutex::new(rx),
        stash: Mutex::new(HashMap::new()),
        next: Mutex::new(1),
        policy: Mutex::new(Box::new(|_| (true, Some("secret".into()), true))),
        prompts_seen: Mutex::new(vec![]),
        raw_events: Mutex::new(vec![]),
        dir: tempfile::tempdir().unwrap(),
    };
    let pmk = base64_std(&[7u8; 32]);
    let r = h.call("init", json!({"data_dir": h.dir.path().join("data").to_string_lossy(), "pmk_b64": pmk})).unwrap();
    assert_eq!(r["api_version"], 1);
    h
}

fn base64_std(b: &[u8]) -> String {
    const T: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut o = String::new();
    for c in b.chunks(3) {
        let n = (c[0] as u32) << 16 | (*c.get(1).unwrap_or(&0) as u32) << 8 | *c.get(2).unwrap_or(&0) as u32;
        o.push(T[(n >> 18) as usize & 63] as char);
        o.push(T[(n >> 12) as usize & 63] as char);
        o.push(if c.len() > 1 { T[(n >> 6) as usize & 63] as char } else { '=' });
        o.push(if c.len() > 2 { T[n as usize & 63] as char } else { '=' });
    }
    o
}

impl H {
    pub fn start(&self, cmd: &str, args: Value) -> u64 {
        let op = {
            let mut n = self.next.lock().unwrap();
            *n += 1;
            *n
        };
        self.eng.submit(&json!({"op": op, "cmd": cmd, "args": args}).to_string());
        op
    }

    /// Wait for the terminal event of `op`; handles prompts; collects progress.
    pub fn wait_full(&self, op: u64) -> (Result<Value, Value>, Vec<Value>) {
        let mut progress = vec![];
        let deadline = Instant::now() + Duration::from_secs(300);
        loop {
            // stashed first
            let pending: Vec<Value> = self.stash.lock().unwrap().remove(&op).unwrap_or_default();
            for ev in pending {
                if let Some(r) = self.handle(op, ev, &mut progress) {
                    return (r, progress);
                }
            }
            let msg = self.rx.lock().unwrap().recv_timeout(Duration::from_millis(200));
            match msg {
                Ok(s) => {
                    self.raw_events.lock().unwrap().push(s.clone());
                    let v: Value = serde_json::from_str(&s).unwrap();
                    let o = v["op"].as_u64().unwrap_or(0);
                    if o == op {
                        if let Some(r) = self.handle(op, v, &mut progress) {
                            return (r, progress);
                        }
                    } else {
                        self.stash.lock().unwrap().entry(o).or_default().push(v);
                    }
                }
                Err(_) => assert!(Instant::now() < deadline, "timeout waiting for op {op}"),
            }
        }
    }

    fn handle(&self, op: u64, ev: Value, progress: &mut Vec<Value>) -> Option<Result<Value, Value>> {
        match ev["ev"].as_str().unwrap() {
            "ok" => Some(Ok(ev["data"].clone())),
            "err" => Some(Err(ev["err"].clone())),
            "cancelled" => Some(Err(json!({"code":"CANCELLED"}))),
            "progress" => {
                progress.push(ev["data"].clone());
                None
            }
            "prompt" => {
                let d = ev["data"].clone();
                self.prompts_seen.lock().unwrap().push(d.clone());
                let (accept, value, replace) = (self.policy.lock().unwrap())(&d);
                self.eng.submit(
                    &json!({"op": op + 1_000_000_000 + self.prompts_seen.lock().unwrap().len() as u64, "cmd":"prompt.reply",
                        "args":{"prompt_id": d["prompt_id"], "accept": accept, "value": value, "replace": replace}})
                    .to_string(),
                );
                None
            }
            other => panic!("unknown event {other}"),
        }
    }

    pub fn call(&self, cmd: &str, args: Value) -> Result<Value, Value> {
        let op = self.start(cmd, args);
        self.wait_full(op).0
    }
    pub fn ok(&self, cmd: &str, args: Value) -> Value {
        self.call(cmd, args.clone()).unwrap_or_else(|e| panic!("{cmd} {args} failed: {e}"))
    }
    pub fn code(&self, cmd: &str, args: Value) -> String {
        match self.call(cmd, args) {
            Err(e) => e["code"].as_str().unwrap().to_string(),
            Ok(v) => panic!("expected error, got {v}"),
        }
    }
    pub fn path(&self, rel: &str) -> std::path::PathBuf {
        self.dir.path().join(rel)
    }
}

pub fn save_profile(h: &H, profile: Value, secrets: Value) -> String {
    let r = h.ok("profiles.save", json!({"profile": profile, "secrets": secrets}));
    r["profile"]["id"].as_str().unwrap().to_string()
}

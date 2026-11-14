//! Crash/fault sweep for the journaled SFTP-style replacement (backup + rollback).
//!
//! A shared event counter numbers every remote operation AND every journal record of one attempt.
//! For each event k and each fault mode the "process" dies at that event: the event is lost (BEFORE) or
//! executed with its answer lost (AFTER), and every later remote op / journal write fails, so the remote
//! state is frozen exactly as a real crash would leave it. Then the engine "restarts": the journal is
//! re-read from disk and `recover` runs. Invariants: neither the original nor the new content is ever
//! lost silently; recovery never claims a state the remote does not have; foreign data is never overwritten.
use async_trait::async_trait;
use nd_engine::error::{Code, NdError, Result};
use nd_engine::journal::{FileLog, JournalDir, ReplaceLog, Step};
use nd_engine::provider::fake::FakeProvider;
use nd_engine::provider::{Progress, Provider, Reader};
use nd_engine::replace::{bak_name, recover, tmp_name, Recovered, ReplaceCtx};
use nd_engine::types::RawEntry;
use std::sync::{Arc, Mutex};

const PATH: &str = "/d/doc.txt";
const ORIG: &[u8] = b"ORIGINAL CONTENT of the remote file";
const NEW: &[u8] = b"NEW CONTENT written by the user, longer than the original one";
const OTHER: &[u8] = b"content written by ANOTHER client";

#[derive(Clone, Copy, PartialEq, Debug)]
enum Mode {
    /// the event never happens
    Before,
    /// the event happens, its answer is lost
    After,
    /// put only: half the data lands, then the connection dies
    Partial,
}

#[derive(Default)]
struct Ctl {
    n: usize,
    crash_at: Option<usize>,
    mode: Option<Mode>,
    /// true = every later event fails forever (process death); false = only event k fails (transient link loss)
    permanent: bool,
    dead: bool,
    /// run once, right after the event with this number executed (simulates another client acting)
    hook_after: Option<(usize, Box<dyn FnOnce() + Send>)>,
    /// fail deletes of paths containing this text (residue test)
    fail_delete_containing: Option<String>,
}

type Shared = Arc<Mutex<Ctl>>;

enum Verdict {
    Run,
    Skip,
    RunThenFail,
    Partial,
}

fn tick(c: &Shared, is_put: bool) -> Verdict {
    let mut g = c.lock().unwrap();
    g.n += 1;
    let n = g.n;
    if g.dead {
        return Verdict::Skip;
    }
    if g.crash_at == Some(n) {
        if g.permanent {
            g.dead = true;
        }
        return match g.mode.unwrap() {
            Mode::Before => Verdict::Skip,
            Mode::After => Verdict::RunThenFail,
            Mode::Partial => {
                if is_put {
                    Verdict::Partial
                } else {
                    Verdict::Skip
                }
            }
        };
    }
    Verdict::Run
}

fn run_hook(c: &Shared) {
    let h = {
        let mut g = c.lock().unwrap();
        let n = g.n;
        match g.hook_after.take() {
            Some((at, f)) if at == n => Some(f),
            other => {
                g.hook_after = other;
                None
            }
        }
    };
    if let Some(f) = h {
        f();
    }
}

fn lost() -> NdError {
    NdError::new(Code::Network).detail("injected: connection lost")
}

struct Faulty {
    inner: Arc<FakeProvider>,
    ctl: Shared,
}

macro_rules! gate {
    ($self:ident, $is_put:expr, $call:expr) => {{
        match tick(&$self.ctl, $is_put) {
            Verdict::Skip => Err(lost()),
            Verdict::Run => {
                let r = $call;
                run_hook(&$self.ctl);
                r
            }
            Verdict::RunThenFail => {
                let _ = $call;
                run_hook(&$self.ctl);
                Err(lost())
            }
            Verdict::Partial => unreachable!(),
        }
    }};
}

#[async_trait]
impl Provider for Faulty {
    async fn list(&self, path: &str) -> Result<Vec<RawEntry>> {
        gate!(self, false, self.inner.list(path).await)
    }
    async fn stat(&self, path: &str) -> Result<RawEntry> {
        gate!(self, false, self.inner.stat(path).await)
    }
    async fn mkdir(&self, path: &str) -> Result<()> {
        gate!(self, false, self.inner.mkdir(path).await)
    }
    async fn rename(&self, from: &str, to: &str) -> Result<()> {
        gate!(self, false, self.inner.rename(from, to).await)
    }
    async fn delete_file(&self, path: &str) -> Result<()> {
        if let Some(t) = self.ctl.lock().unwrap().fail_delete_containing.clone() {
            if path.contains(&t) {
                return Err(NdError::new(Code::PermissionDenied).with("path", path));
            }
        }
        gate!(self, false, self.inner.delete_file(path).await)
    }
    async fn delete_dir(&self, path: &str) -> Result<()> {
        self.inner.delete_dir(path).await
    }
    async fn open_read(&self, path: &str, offset: u64) -> Result<Reader> {
        gate!(self, false, self.inner.open_read(path, offset).await)
    }
    async fn put(&self, path: &str, src: Reader, size: u64, overwrite: bool, pr: &Progress) -> Result<()> {
        match tick(&self.ctl, true) {
            Verdict::Skip => Err(lost()),
            Verdict::Run => {
                let r = self.inner.put(path, src, size, overwrite, pr).await;
                run_hook(&self.ctl);
                r
            }
            Verdict::RunThenFail => {
                let _ = self.inner.put(path, src, size, overwrite, pr).await;
                run_hook(&self.ctl);
                Err(lost())
            }
            Verdict::Partial => {
                // a truncated temp file is what an interrupted SFTP upload leaves behind
                self.inner.put_file(path, &NEW[..NEW.len() / 2]);
                Err(lost())
            }
        }
    }
}

struct FaultyLog {
    inner: FileLog,
    ctl: Shared,
}

impl ReplaceLog for FaultyLog {
    fn record(&mut self, step: Step) -> Result<()> {
        match tick(&self.ctl, false) {
            Verdict::Skip | Verdict::Partial => Err(NdError::new(Code::Io).detail("injected: journal write lost")),
            Verdict::Run => {
                let r = self.inner.record(step);
                run_hook(&self.ctl);
                r
            }
            Verdict::RunThenFail => {
                let _ = self.inner.record(step);
                Err(NdError::new(Code::Io).detail("injected: crash right after journal write"))
            }
        }
    }
}

fn sha(b: &[u8]) -> String {
    nd_engine::crypto::sha256_hex(b)
}

fn ctx(attempt: &str) -> ReplaceCtx {
    ReplaceCtx { profile_id: "p1".into(), path: PATH.into(), size: NEW.len() as u64, sha256: sha(NEW), attempt: attempt.into(), expected: None }
}

fn world() -> (Arc<FakeProvider>, tempfile::TempDir, JournalDir) {
    let fake = FakeProvider::new();
    fake.put_dir("/d");
    fake.put_file(PATH, ORIG);
    let dir = tempfile::tempdir().unwrap();
    let jd = JournalDir::open(dir.path().to_str().unwrap(), &[3u8; 32]).unwrap();
    (fake, dir, jd)
}

fn reader(b: &[u8]) -> Reader {
    Box::new(std::io::Cursor::new(b.to_vec()))
}

fn ctl(crash_at: Option<usize>, mode: Mode, permanent: bool) -> Shared {
    Arc::new(Mutex::new(Ctl { crash_at, mode: Some(mode), permanent, ..Default::default() }))
}

/// Everything under /d with its content.
fn snapshot(fake: &FakeProvider) -> Vec<(String, Vec<u8>)> {
    let mut v = vec![];
    // enumerate by probing the names that can exist for the attempts used in this file
    for attempt in ["a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8"] {
        for p in [PATH.to_string(), tmp_name(PATH, attempt), bak_name(PATH, attempt), format!("{}.nd-part", tmp_name(PATH, attempt))] {
            if let Some(d) = fake.get_file(&p) {
                if !v.iter().any(|(q, _)| q == &p) {
                    v.push((p, d));
                }
            }
        }
    }
    v
}

fn has_content(fake: &FakeProvider, bytes: &[u8]) -> bool {
    snapshot(fake).iter().any(|(_, d)| d == bytes)
}

fn leftovers(fake: &FakeProvider) -> Vec<String> {
    snapshot(fake).into_iter().map(|(p, _)| p).filter(|p| p != PATH).collect()
}

/// Number of events (remote ops + journal records) of one uninterrupted run.
async fn count_events() -> usize {
    let (fake, _dir, jd) = world();
    let c = ctl(None, Mode::Before, true);
    let p = Faulty { inner: fake, ctl: c.clone() };
    let mut log = FaultyLog { inner: jd.create("a1").unwrap(), ctl: c.clone() };
    p.replace(&ctx("a1"), reader(NEW), &mut log, &Progress::none()).await.unwrap();
    let n = c.lock().unwrap().n;
    n
}

async fn restart_and_recover(fake: &Arc<FakeProvider>, jd: &JournalDir, attempt: &str) -> Recovered {
    let clean = Faulty { inner: fake.clone(), ctl: ctl(None, Mode::Before, true) };
    let replay = jd.read(attempt).unwrap();
    let mut log = jd.reopen(attempt).unwrap();
    recover(&clean, &replay, &mut log).await.unwrap()
}

/// The invariants that must hold after recovery, whatever the crash point was.
fn check_after_recovery(fake: &FakeProvider, rec: &Recovered, label: &str) {
    let at = fake.get_file(PATH);
    // nothing is lost silently
    assert!(has_content(fake, ORIG) || (at.as_deref() == Some(NEW) && matches!(rec, Recovered::NewInPlace | Recovered::AlreadyDone)), "{label}: ORIGINAL lost ({rec:?}) {:?}", snapshot(fake).iter().map(|(p, d)| (p.clone(), d.len())).collect::<Vec<_>>());
    match rec {
        Recovered::NewInPlace => {
            assert_eq!(at.as_deref(), Some(NEW), "{label}: claimed new_in_place but path differs");
            assert!(leftovers(fake).is_empty(), "{label}: leftovers {:?}", leftovers(fake));
        }
        Recovered::OriginalIntact | Recovered::OriginalRestored => {
            assert_eq!(at.as_deref(), Some(ORIG), "{label}: claimed original but path differs");
            assert!(leftovers(fake).is_empty(), "{label}: leftovers {:?}", leftovers(fake));
        }
        Recovered::AlreadyDone => {
            assert!(at.as_deref() == Some(NEW) || at.as_deref() == Some(ORIG), "{label}: finished journal but path is neither");
        }
        Recovered::Uncertain { .. } => {
            // ambiguous: everything involved must be kept; the original must exist somewhere
            assert!(has_content(fake, ORIG), "{label}: uncertain but ORIGINAL gone");
        }
    }
}

#[tokio::test]
async fn crash_sweep_every_event_and_mode() {
    let total = count_events().await;
    assert!(total >= 18, "expected the full journaled sequence, got {total} events");
    let mut combos = 0;
    for mode in [Mode::Before, Mode::After, Mode::Partial] {
        for k in 1..=total {
            let attempt = "a1";
            let (fake, _dir, jd) = world();
            let c = ctl(Some(k), mode, true);
            let p = Faulty { inner: fake.clone(), ctl: c.clone() };
            let mut log = FaultyLog { inner: jd.create(attempt).unwrap(), ctl: c.clone() };
            let res = p.replace(&ctx(attempt), reader(NEW), &mut log, &Progress::none()).await;
            let label = format!("k={k} mode={mode:?} first_result={}", if res.is_ok() { "ok".to_string() } else { format!("{:?}", res.as_ref().unwrap_err().code) });
            // the crash froze the world; a result that says Ok must not lie
            if res.is_ok() {
                assert_eq!(fake.get_file(PATH).as_deref(), Some(NEW), "{label}: Ok but remote is not new");
            }
            // "restart"
            let before = jd.read(attempt);
            if before.is_err() {
                // Begin never reached the disk: no remote operation can have happened yet
                assert_eq!(fake.get_file(PATH).as_deref(), Some(ORIG), "{label}");
                assert!(leftovers(&fake).is_empty(), "{label}");
                continue;
            }
            let steps = format!("{:?}", jd.read(attempt).unwrap().steps.iter().map(|s| format!("{s:?}").split(|c: char| !c.is_alphanumeric()).next().unwrap().to_string()).collect::<Vec<_>>());
            let label = format!("{label} journal={steps}");
            let rec = restart_and_recover(&fake, &jd, attempt).await;
            check_after_recovery(&fake, &rec, &label);
            // recovery is idempotent and never destroys more
            let again = restart_and_recover(&fake, &jd, attempt).await;
            assert!(matches!(again, Recovered::AlreadyDone | Recovered::Uncertain { .. }), "{label}: second recovery {again:?}");
            check_after_recovery(&fake, &again, &label);
            combos += 1;
        }
    }
    assert!(combos > 40, "sweep too small: {combos}");
}

#[tokio::test]
async fn transient_link_loss_at_every_event_ends_in_a_known_state() {
    let total = count_events().await;
    for mode in [Mode::Before, Mode::After] {
        for k in 1..=total {
            let (fake, _dir, jd) = world();
            let c = ctl(Some(k), mode, false); // only event k fails; the link comes back
            let p = Faulty { inner: fake.clone(), ctl: c.clone() };
            let mut log = FaultyLog { inner: jd.create("a2").unwrap(), ctl: c.clone() };
            let res = p.replace(&ctx("a2"), reader(NEW), &mut log, &Progress::none()).await;
            let label = format!("k={k} mode={mode:?}");
            match &res {
                Ok(r) => {
                    assert_eq!(fake.get_file(PATH).as_deref(), Some(NEW), "{label}: Ok but not new");
                    assert!(!r.atomic, "{label}: the backup sequence must never claim atomicity");
                }
                Err(e) if e.code == Code::SaveUncertain => {
                    assert!(has_content(&fake, ORIG), "{label}: uncertain but ORIGINAL gone");
                    assert!(has_content(&fake, NEW) || fake.get_file(PATH).as_deref() == Some(ORIG), "{label}: uncertain and NEW unreachable");
                }
                Err(e) if e.params.get("original_preserved") == Some(&serde_json::json!(true)) => {
                    assert_eq!(fake.get_file(PATH).as_deref(), Some(ORIG), "{label}: original_preserved but path differs");
                }
                Err(_) => {
                    // plain failure before the original was touched
                    assert_eq!(fake.get_file(PATH).as_deref(), Some(ORIG), "{label}: plain error but original changed");
                }
            }
            // whatever happened, a restart-recovery must not make anything worse
            if jd.read("a2").is_ok() {
                let rec = restart_and_recover(&fake, &jd, "a2").await;
                check_after_recovery(&fake, &rec, &label);
            }
        }
    }
}

#[tokio::test]
async fn happy_path_is_journaled_in_order_and_cleans_up() {
    let (fake, _dir, jd) = world();
    let c = ctl(None, Mode::Before, true);
    let p = Faulty { inner: fake.clone(), ctl: c.clone() };
    let mut log = FaultyLog { inner: jd.create("a3").unwrap(), ctl: c };
    let r = p.replace(&ctx("a3"), reader(NEW), &mut log, &Progress::none()).await.unwrap();
    assert!(!r.atomic && r.residue.is_empty());
    assert_eq!(fake.get_file(PATH).as_deref(), Some(NEW));
    assert!(leftovers(&fake).is_empty());
    let steps: Vec<String> = jd.read("a3").unwrap().steps.iter().map(|s| format!("{s:?}").split(|c: char| !c.is_alphanumeric()).next().unwrap().to_string()).collect();
    assert_eq!(
        steps,
        ["Begin", "UploadIntent", "Uploaded", "TmpVerified", "BackupIntent", "BackupDone", "SwapIntent", "SwapDone", "NewVerified", "BackupDeleteIntent", "Completed"]
    );
}

#[tokio::test]
async fn another_client_writes_between_backup_and_swap_nothing_is_overwritten() {
    let (fake, _dir, jd) = world();
    let c = ctl(None, Mode::Before, true);
    // find the event number of the original->backup rename: count in a dry run (events before BackupDone)
    let (dry, _d2, jd2) = world();
    let dc = ctl(None, Mode::Before, true);
    let dp = Faulty { inner: dry, ctl: dc.clone() };
    let mut dl = FaultyLog { inner: jd2.create("a4").unwrap(), ctl: dc.clone() };
    dp.replace(&ctx("a4"), reader(NEW), &mut dl, &Progress::none()).await.unwrap();
    // events: Begin, UploadIntent, put, Uploaded, stat, TmpVerified, BackupIntent, rename(orig->bak) = 8th
    let rename_event = 8;
    let f2 = fake.clone();
    c.lock().unwrap().hook_after = Some((rename_event, Box::new(move || f2.put_file(PATH, OTHER))));
    let p = Faulty { inner: fake.clone(), ctl: c.clone() };
    let mut log = FaultyLog { inner: jd.create("a4").unwrap(), ctl: c.clone() };
    let err = p.replace(&ctx("a4"), reader(NEW), &mut log, &Progress::none()).await.unwrap_err();
    assert_eq!(err.code, Code::SaveUncertain, "{err}");
    assert_eq!(fake.get_file(PATH).as_deref(), Some(OTHER), "the other client's file must not be overwritten");
    assert_eq!(fake.get_file(&bak_name(PATH, "a4")).as_deref(), Some(ORIG), "the backup must be kept");
    assert_eq!(fake.get_file(&tmp_name(PATH, "a4")).as_deref(), Some(NEW), "the new content must be kept");
    assert!(matches!(jd.read("a4").unwrap().steps.last(), Some(Step::Uncertain)));
}

#[tokio::test]
async fn recovery_never_restores_over_a_file_recreated_by_another_client() {
    // crash right after "original -> backup"
    let total = count_events().await;
    let _ = total;
    let (fake, _dir, jd) = world();
    let c = ctl(Some(9), Mode::Before, true); // event 9 = journal BackupDone: backup exists, path absent
    let p = Faulty { inner: fake.clone(), ctl: c.clone() };
    let mut log = FaultyLog { inner: jd.create("a5").unwrap(), ctl: c.clone() };
    let _ = p.replace(&ctx("a5"), reader(NEW), &mut log, &Progress::none()).await;
    assert!(fake.get_file(PATH).is_none() && fake.get_file(&bak_name(PATH, "a5")).as_deref() == Some(ORIG), "setup: window not reproduced");
    fake.put_file(PATH, OTHER); // somebody else recreates the path while we are down
    let rec = restart_and_recover(&fake, &jd, "a5").await;
    assert!(matches!(rec, Recovered::Uncertain { .. }), "{rec:?}");
    assert_eq!(fake.get_file(PATH).as_deref(), Some(OTHER));
    assert_eq!(fake.get_file(&bak_name(PATH, "a5")).as_deref(), Some(ORIG));
    assert_eq!(fake.get_file(&tmp_name(PATH, "a5")).as_deref(), Some(NEW));
}

#[tokio::test]
async fn failed_backup_deletion_is_reported_as_residue_not_as_failure() {
    let (fake, _dir, jd) = world();
    let c = ctl(None, Mode::Before, true);
    c.lock().unwrap().fail_delete_containing = Some(".nd-bak-a6".into());
    let p = Faulty { inner: fake.clone(), ctl: c.clone() };
    let mut log = FaultyLog { inner: jd.create("a6").unwrap(), ctl: c };
    let r = p.replace(&ctx("a6"), reader(NEW), &mut log, &Progress::none()).await.unwrap();
    assert_eq!(fake.get_file(PATH).as_deref(), Some(NEW));
    assert_eq!(r.residue, vec![bak_name(PATH, "a6")]);
    assert!(matches!(jd.read("a6").unwrap().steps.last(), Some(Step::Completed { .. })));
}

#[tokio::test]
async fn damaged_journal_is_handled_without_touching_foreign_data() {
    // crash after the backup rename, then damage the journal in three different ways
    for damage in ["truncate_tail", "corrupt_last", "corrupt_begin"] {
        let (fake, _dir, jd) = world();
        let c = ctl(Some(9), Mode::Before, true);
        let p = Faulty { inner: fake.clone(), ctl: c.clone() };
        let mut log = FaultyLog { inner: jd.create("a7").unwrap(), ctl: c.clone() };
        let _ = p.replace(&ctx("a7"), reader(NEW), &mut log, &Progress::none()).await;
        let path = jd.path_of("a7").unwrap();
        let mut d = std::fs::read(&path).unwrap();
        match damage {
            "truncate_tail" => d.truncate(d.len() - 3),
            "corrupt_last" => {
                let l = d.len();
                d[l - 2] ^= 0xff;
            }
            _ => d[10] ^= 0xff,
        }
        std::fs::write(&path, d).unwrap();
        let rec = restart_and_recover(&fake, &jd, "a7").await;
        if damage == "corrupt_begin" {
            // names cannot be trusted: nothing may be touched
            assert!(matches!(rec, Recovered::Uncertain { .. }), "{damage}: {rec:?}");
            assert_eq!(fake.get_file(&bak_name(PATH, "a7")).as_deref(), Some(ORIG), "{damage}");
            assert!(fake.get_file(PATH).is_none(), "{damage}");
        } else {
            // the valid prefix still names the attempt: the original is put back from the backup
            assert_eq!(rec, Recovered::OriginalRestored, "{damage}");
            assert_eq!(fake.get_file(PATH).as_deref(), Some(ORIG), "{damage}");
        }
    }
}

#[tokio::test]
async fn journal_without_any_record_means_nothing_was_started() {
    let (fake, _dir, jd) = world();
    jd.create("a8").unwrap(); // created, never written: crash between create and Begin
    let rec = restart_and_recover(&fake, &jd, "a8").await;
    // Begin precedes every remote operation, so an empty journal proves nothing was started
    assert_eq!(rec, Recovered::OriginalIntact);
    assert_eq!(fake.get_file(PATH).as_deref(), Some(ORIG));
}

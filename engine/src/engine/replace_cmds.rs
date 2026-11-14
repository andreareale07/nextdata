//! `session.replace`, `replace.pending`, `replace.recover`, `replace.forget`: save a local file over an
//! existing remote file without losing either version (see docs/protocol.md "Sostituzione").
use super::*;
use crate::crypto::Sha256Stream;
use crate::journal::{JournalDir, Step};
use crate::replace::{self, recover, ReplaceCtx, Recovered, VERIFY_MAX};
use tokio::io::AsyncReadExt;

/// Largest remote object copied aside before a forced overwrite.
const RECOVERY_COPY_MAX: u64 = 256 * 1024 * 1024;

struct PathLock<'a> {
    set: &'a Mutex<std::collections::HashSet<String>>,
    key: String,
}
impl Drop for PathLock<'_> {
    fn drop(&mut self) {
        self.set.lock().unwrap().remove(&self.key);
    }
}

fn conflict(reason: &str) -> NdError {
    NdError::new(Code::Conflict).with("reason", reason)
}

impl Inner {
    fn journal(&self) -> Result<Arc<JournalDir>> {
        self.journal.lock().unwrap().clone().ok_or_else(|| NdError::new(Code::NotInitialized))
    }

    /// Why the remote can no longer be trusted to be what the edit was based on (Ok = unchanged).
    async fn check_unchanged(p: &dyn Provider, r: &str, exp: &RemoteVersion, base_sha: Option<&str>, confirm_unverified: bool) -> Result<()> {
        let cur = match p.version(r).await {
            Ok(v) => v,
            Err(e) if e.code == Code::NotFound => return Err(conflict("remote_missing")),
            Err(e) => return Err(e),
        };
        let changed = |reason: &str| conflict(reason).with("remote_version", serde_json::to_value(&cur).unwrap_or(Value::Null));
        if exp.etag.is_some() || exp.version_id.is_some() {
            // server-issued identity: must match exactly; losing it is itself a reason to doubt
            if cur.etag != exp.etag || (exp.version_id.is_some() && cur.version_id != exp.version_id) {
                return Err(changed("changed"));
            }
            return Ok(());
        }
        if cur.size != exp.size || cur.mtime_ms != exp.mtime_ms {
            return Err(changed("changed"));
        }
        if exp.size.is_none() || exp.mtime_ms.is_none() {
            return Err(changed("unverifiable"));
        }
        // size and mtime alone prove nothing (1 s resolution, same-size edits): compare contents when affordable
        let size = exp.size.unwrap_or(0);
        match base_sha {
            Some(h) if size <= VERIFY_MAX => match replace::matches(p, r, size, h).await? {
                Some(_) => Ok(()),
                None => Err(changed("content_changed")),
            },
            _ if confirm_unverified => Ok(()),
            _ => Err(changed("unverifiable")),
        }
    }

    pub(super) async fn cmd_replace(self: &Arc<Self>, token: CancellationToken, a: &Value) -> Result<Value> {
        let sid = req_str(a, "session")?;
        let s = self.session(&sid)?;
        let r = norm_ref(&req_str(a, "ref")?)?;
        if r == "/" {
            return Err(NdError::invalid("ref"));
        }
        let local = std::path::PathBuf::from(req_str(a, "local_path")?);
        let resolution = opt_str(a, "resolution").unwrap_or_else(|| "normal".into());
        if !matches!(resolution.as_str(), "normal" | "overwrite" | "copy") {
            return Err(NdError::invalid("resolution"));
        }
        let expected: Option<RemoteVersion> = match a.get("expect_version") {
            Some(v) if !v.is_null() => Some(serde_json::from_value(v.clone()).map_err(|_| NdError::invalid("expect_version"))?),
            _ => None,
        };
        let base_sha = opt_str(a, "base_sha256");
        let confirm = a.get("confirm_unverified").and_then(|v| v.as_bool()).unwrap_or(false);
        let jd = self.journal()?;
        let pr = Progress { cb: Arc::new(|_| {}), cancel: token };

        // one replacement per path: running now ...
        let key = format!("{}|{}", s.profile_id, r);
        if !self.replacing.lock().unwrap().insert(key.clone()) {
            return Err(conflict("replace_in_progress"));
        }
        let _lock = PathLock { set: &self.replacing, key };
        // ... or left unfinished by an earlier run (must be recovered first, never retried blindly)
        for att in jd.attempts() {
            if let Ok(rp) = jd.read(&att) {
                if let (true, Some(Step::Begin { profile_id, path, .. })) = (rp.pending(), rp.begin()) {
                    if *profile_id == s.profile_id && *path == r {
                        return Err(conflict("pending_recovery").with("attempt", att));
                    }
                }
            }
        }

        // the snapshot: it must be a regular file (never a link)
        let md = tokio::fs::symlink_metadata(&local).await?;
        if !md.is_file() {
            return Err(NdError::invalid("local_path"));
        }
        let size = md.len();
        let sha = {
            let mut f = tokio::fs::File::open(&local).await?;
            let mut h = Sha256Stream::new();
            let mut buf = vec![0u8; 256 * 1024];
            loop {
                pr.check()?;
                let n = f.read(&mut buf).await?;
                if n == 0 {
                    break;
                }
                h.update(&buf[..n]);
            }
            h.finish_hex()
        };

        let parent = parent_of(&r).unwrap_or("/".into());
        if resolution == "copy" {
            // keep the remote untouched; the edit goes next to it under a free name
            let name = pathguard::basename(&r).to_string();
            let stem_name = match name.rfind('.') {
                Some(i) if i > 0 => format!("{} (modifica NextData){}", &name[..i], &name[i..]),
                _ => format!("{name} (modifica NextData)"),
            };
            let mut target = None;
            for n in 0..1000u32 {
                let cand = if n == 0 { stem_name.clone() } else { pathguard::numbered(&stem_name, n) };
                if !crate::provider::exists(s.provider.as_ref(), &join(&parent, &cand)).await? {
                    target = Some(join(&parent, &cand));
                    break;
                }
            }
            let target = target.ok_or_else(|| NdError::new(Code::AlreadyExists))?;
            let f = tokio::fs::File::open(&local).await?;
            s.provider.put(&target, Box::new(f), size, false, &pr).await?;
            *s.cache.lock().unwrap() = None;
            let st = s.provider.stat(&target).await?;
            let version = s.provider.version(&target).await.ok();
            return Ok(json!({"entry": transfer::entry_of(&parent, st), "version": version, "resolution": "copy"}));
        }

        if resolution == "normal" {
            let exp = expected.as_ref().ok_or_else(|| NdError::invalid("expect_version"))?;
            Self::check_unchanged(s.provider.as_ref(), &r, exp, base_sha.as_deref(), confirm).await?;
        } else if let Some(dir) = opt_str(a, "recovery_dir") {
            // forced overwrite: keep a copy of what is about to be replaced (when it exists and fits)
            match s.provider.stat(&r).await {
                Ok(st) if st.size.unwrap_or(0) <= RECOVERY_COPY_MAX => {
                    let rep = self.reporter(0, pr.cancel.clone(), true);
                    transfer::download(s.provider.as_ref(), std::slice::from_ref(&r), &dir, Collision::Rename, &rep).await?;
                }
                Ok(_) => return Err(conflict("recovery_copy_too_large")),
                Err(e) if e.code == Code::NotFound => {}
                Err(e) => return Err(e),
            }
        }

        let attempt = crypto::random_hex(8);
        let ctx = ReplaceCtx { profile_id: s.profile_id.clone(), path: r.clone(), size, sha256: sha, attempt: attempt.clone(), expected };
        let mut log = jd.create(&attempt)?;
        let f = tokio::fs::File::open(&local).await?;
        let out = s.provider.replace(&ctx, Box::new(f), &mut log, &pr).await;
        *s.cache.lock().unwrap() = None;
        let replaced = out?;
        let st = s.provider.stat(&r).await?;
        let version = s.provider.version(&r).await.ok();
        if replaced.residue.is_empty() {
            let _ = jd.forget(&attempt); // finished cleanly: nothing left to recover
        }
        Ok(json!({"entry": transfer::entry_of(&parent, st), "version": version, "replaced": replaced, "attempt": attempt, "resolution": resolution}))
    }

    pub(super) fn cmd_replace_pending(&self, a: &Value) -> Result<Value> {
        let jd = self.journal()?;
        let only = opt_str(a, "profile_id");
        let mut out = vec![];
        for att in jd.attempts() {
            let Ok(rp) = jd.read(&att) else { continue };
            if !rp.pending() {
                continue;
            }
            let (profile, path) = match rp.begin() {
                Some(Step::Begin { profile_id, path, .. }) => (Some(profile_id.clone()), Some(path.clone())),
                _ => (None, None),
            };
            if let Some(o) = &only {
                if profile.as_deref() != Some(o.as_str()) {
                    continue;
                }
            }
            out.push(json!({"attempt": att, "profile_id": profile, "path": path, "steps": rp.steps.len(), "damage": rp.damage}));
        }
        Ok(json!({"pending": out}))
    }

    pub(super) async fn cmd_replace_recover(self: &Arc<Self>, a: &Value) -> Result<Value> {
        let s = self.session(&req_str(a, "session")?)?;
        let jd = self.journal()?;
        let mut out = vec![];
        for att in jd.attempts() {
            let Ok(rp) = jd.read(&att) else { continue };
            if !rp.pending() {
                continue;
            }
            let Some(Step::Begin { profile_id, path, .. }) = rp.begin() else { continue };
            if *profile_id != s.profile_id {
                continue;
            }
            let key = format!("{}|{}", s.profile_id, path);
            if !self.replacing.lock().unwrap().insert(key.clone()) {
                continue; // a live replacement owns this path
            }
            let _lock = PathLock { set: &self.replacing, key };
            let mut log = jd.reopen(&att)?;
            let res = recover(s.provider.as_ref(), &rp, &mut log).await;
            *s.cache.lock().unwrap() = None;
            let (outcome, reason) = match res {
                Ok(Recovered::AlreadyDone) => ("already_done", None),
                Ok(Recovered::NewInPlace) => ("new_in_place", None),
                Ok(Recovered::OriginalIntact) => ("original_intact", None),
                Ok(Recovered::OriginalRestored) => ("original_restored", None),
                Ok(Recovered::Uncertain { reason }) => ("uncertain", Some(reason)),
                Err(e) => ("unreachable", Some(format!("{:?}", e.code))),
            };
            if !matches!(outcome, "uncertain" | "unreachable") {
                let _ = jd.forget(&att);
            }
            out.push(json!({"attempt": att, "path": path, "outcome": outcome, "reason": reason}));
        }
        Ok(json!({"recovered": out}))
    }

    /// Explicit user decision: drop the journal of an attempt that stayed Uncertain/damaged. Remote leftovers
    /// (`.nd-part-<attempt>` / `.nd-bak-<attempt>`) are NOT touched: they are the user's evidence.
    pub(super) fn cmd_replace_forget(&self, a: &Value) -> Result<Value> {
        if a.get("confirm").and_then(|v| v.as_bool()) != Some(true) {
            return Err(NdError::invalid("confirm"));
        }
        let att = req_str(a, "attempt")?;
        let jd = self.journal()?;
        let path = jd.path_of(&att)?;
        std::fs::remove_file(path)?;
        Ok(json!({}))
    }
}

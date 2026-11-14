//! Safe remote replacement for providers without an atomic "replace" primitive (SFTP, and the default).
//!
//! Sequence (never removes the original first); every transition is journaled BEFORE the remote
//! operation it announces (see `journal.rs`):
//!   1. upload to `<path>.nd-part-<attempt>`, verify its size
//!   2. rename original -> `<path>.nd-bak-<attempt>`
//!   3. rename temp -> original, verify
//!   4. delete the backup (only after step 3 is verified)
//! Between 2 and 3 the original path does not exist: this is NOT atomic and is reported as such.
//! Any failure after step 1 triggers `reconcile` (decided from the REMOTE state, not from error codes)
//! before reporting; when the outcome cannot be established nothing is cleaned up and `SAVE_UNCERTAIN`
//! is returned.
use crate::crypto::Sha256Stream;
use crate::error::{Code, NdError, Result};
use crate::journal::{ReplaceLog, Step};
use crate::provider::{Progress, Provider, Reader};
use crate::types::RemoteVersion;
use serde::Serialize;
use tokio::io::AsyncReadExt;

/// Largest object re-read to compare contents (otherwise only the size is compared).
pub const VERIFY_MAX: u64 = 16 * 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Verified {
    Content,
    Size,
}

#[derive(Debug, Clone, Serialize)]
pub struct Replaced {
    pub atomic: bool,
    pub verified: Verified,
    /// Leftovers we own that could not be removed (to be cleaned later).
    pub residue: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Recovery {
    NewInPlace,
    OriginalIntact,
    OriginalRestored,
    Uncertain,
}

pub fn tmp_name(path: &str, attempt: &str) -> String {
    format!("{path}.nd-part-{attempt}")
}
pub fn bak_name(path: &str, attempt: &str) -> String {
    format!("{path}.nd-bak-{attempt}")
}

/// Does `path` hold exactly the snapshot? Compares size always, contents when small enough.
/// Ok(None) = it does not (or the file is absent).
pub async fn matches<P: Provider + ?Sized>(p: &P, path: &str, size: u64, sha: &str) -> Result<Option<Verified>> {
    let st = match p.stat(path).await {
        Ok(s) => s,
        Err(e) if e.code == Code::NotFound => return Ok(None),
        Err(e) => return Err(e),
    };
    if st.size != Some(size) {
        return Ok(None);
    }
    if size > VERIFY_MAX {
        return Ok(Some(Verified::Size));
    }
    let mut r = p.open_read(path, 0).await?;
    let mut h = Sha256Stream::new();
    let mut buf = vec![0u8; 64 * 1024];
    let mut total = 0u64;
    loop {
        let n = r.read(&mut buf).await.map_err(NdError::from)?;
        if n == 0 {
            break;
        }
        total += n as u64;
        if total > size {
            return Ok(None);
        }
        h.update(&buf[..n]);
    }
    Ok(if total == size && h.finish_hex() == sha { Some(Verified::Content) } else { None })
}

async fn exists<P: Provider + ?Sized>(p: &P, path: &str) -> Result<bool> {
    crate::provider::exists(p, path).await
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Reconciled {
    pub recovery: Recovery,
    /// How the new content in place was verified (only for `NewInPlace`).
    pub verified: Option<Verified>,
}

fn rc(recovery: Recovery, verified: Option<Verified>) -> Result<Reconciled> {
    Ok(Reconciled { recovery, verified })
}

/// Decides what state a (possibly interrupted) replacement left behind and finishes or undoes it safely.
/// Decisions come from the REMOTE state (never from an error code). Only names derived from `attempt`
/// are ever deleted; the original is only restored (never over something that exists) or completed.
pub async fn reconcile<P: Provider + ?Sized>(p: &P, path: &str, size: u64, sha: &str, attempt: &str) -> Result<Reconciled> {
    let (tmp, bak) = (tmp_name(path, attempt), bak_name(path, attempt));
    let orig_exists = exists(p, path).await?;
    let tmp_exists = exists(p, &tmp).await?;
    let bak_exists = exists(p, &bak).await?;
    if orig_exists {
        if let Some(v) = matches(p, path, size, sha).await? {
            // the new content is in place and verified: leftovers are ours to remove
            if bak_exists {
                let _ = p.delete_file(&bak).await;
            }
            if tmp_exists {
                let _ = p.delete_file(&tmp).await;
            }
            return rc(Recovery::NewInPlace, Some(v));
        }
        if !bak_exists {
            // original never moved aside (or replaced by somebody else): our temp is the only leftover
            if tmp_exists {
                let _ = p.delete_file(&tmp).await;
            }
            return rc(Recovery::OriginalIntact, None);
        }
        // original differs AND a backup exists: somebody else wrote there meanwhile. Touch nothing.
        return rc(Recovery::Uncertain, None);
    }
    // original path is absent
    if bak_exists {
        // put the original back (provider rename never replaces); the temp copy goes only if that worked
        return match p.rename(&bak, path).await {
            Ok(()) => {
                if tmp_exists {
                    let _ = p.delete_file(&tmp).await;
                }
                rc(Recovery::OriginalRestored, None)
            }
            Err(_) => rc(Recovery::Uncertain, None),
        };
    }
    if tmp_exists {
        if let Some(v) = matches(p, &tmp, size, sha).await? {
            // the original was moved aside and its backup is gone, or never existed: complete the swap
            return match p.rename(&tmp, path).await {
                Ok(()) => rc(Recovery::NewInPlace, Some(v)),
                Err(_) => rc(Recovery::Uncertain, None),
            };
        }
    }
    rc(Recovery::Uncertain, None)
}

fn uncertain(path: &str, cause: &NdError) -> NdError {
    NdError::new(Code::SaveUncertain).with("path", path).with("cause", format!("{:?}", cause.code))
}

/// Everything that identifies one replacement attempt.
#[derive(Debug, Clone)]
pub struct ReplaceCtx {
    pub profile_id: String,
    pub path: String,
    pub size: u64,
    pub sha256: String,
    pub attempt: String,
    pub expected: Option<RemoteVersion>,
}

impl ReplaceCtx {
    pub fn begin_step(&self) -> Step {
        Step::Begin { profile_id: self.profile_id.clone(), path: self.path.clone(), size: self.size, sha256: self.sha256.clone(), expected: self.expected.clone() }
    }
}

/// After a failed step: look at the remote, finish/undo what is safe, report faithfully.
async fn settle<P: Provider + ?Sized>(p: &P, c: &ReplaceCtx, log: &mut dyn ReplaceLog, first: NdError) -> Result<Replaced> {
    match reconcile(p, &c.path, c.size, &c.sha256, &c.attempt).await {
        Ok(Reconciled { recovery: Recovery::NewInPlace, verified }) => {
            let _ = log.record(Step::Resolved { outcome: "new_in_place".into() });
            Ok(Replaced { atomic: false, verified: verified.unwrap_or(Verified::Size), residue: vec![] })
        }
        Ok(Reconciled { recovery: r @ (Recovery::OriginalIntact | Recovery::OriginalRestored), .. }) => {
            let outcome = if r == Recovery::OriginalIntact { "original_intact" } else { "original_restored" };
            let _ = log.record(Step::Resolved { outcome: outcome.into() });
            Err(first.with("original_preserved", true))
        }
        Ok(Reconciled { recovery: Recovery::Uncertain, .. }) | Err(_) => {
            let _ = log.record(Step::Uncertain);
            Err(uncertain(&c.path, &first))
        }
    }
}

macro_rules! rec {
    ($p:expr, $c:expr, $log:expr, $step:expr) => {
        if let Err(e) = $log.record($step) {
            return settle($p, $c, $log, e).await;
        }
    };
}

/// The generic NON-ATOMIC replacement (see module docs). Every transition is journaled before the
/// remote operation it announces.
pub async fn replace_with_backup<P: Provider + ?Sized>(p: &P, c: &ReplaceCtx, src: Reader, log: &mut dyn ReplaceLog, pr: &Progress) -> Result<Replaced> {
    let (path, tmp, bak) = (c.path.as_str(), tmp_name(&c.path, &c.attempt), bak_name(&c.path, &c.attempt));
    log.record(c.begin_step())?; // nothing remote has happened yet: a failure here is a plain error
    log.record(Step::UploadIntent)?;
    // 1. upload + verify size (original untouched so far)
    if let Err(e) = p.put(&tmp, src, c.size, false, pr).await {
        let _ = p.delete_file(&tmp).await;
        let _ = log.record(Step::Failed { code: format!("{:?}", e.code) });
        return Err(e);
    }
    rec!(p, c, log, Step::Uploaded);
    match p.stat(&tmp).await {
        Ok(s) if s.size == Some(c.size) => {}
        other => {
            let _ = p.delete_file(&tmp).await;
            let e = match other {
                Err(e) => e,
                Ok(_) => NdError::new(Code::Network).with("path", path).detail("short upload"),
            };
            let _ = log.record(Step::Failed { code: format!("{:?}", e.code) });
            return Err(e);
        }
    }
    rec!(p, c, log, Step::TmpVerified);
    if pr.check().is_err() {
        // cancelled before the original was touched: drop the temp copy
        let _ = p.delete_file(&tmp).await;
        let _ = log.record(Step::Failed { code: "Cancelled".into() });
        return Err(NdError::new(Code::Cancelled));
    }
    // 2. original -> backup
    rec!(p, c, log, Step::BackupIntent);
    if let Err(e) = p.rename(path, &bak).await {
        return settle(p, c, log, e).await;
    }
    rec!(p, c, log, Step::BackupDone);
    // 3. temp -> original
    rec!(p, c, log, Step::SwapIntent);
    if let Err(e) = p.rename(&tmp, path).await {
        return settle(p, c, log, e).await;
    }
    rec!(p, c, log, Step::SwapDone);
    // 4. verify the new original; the backup may only go after this
    let verified = match matches(p, path, c.size, &c.sha256).await {
        Ok(Some(v)) => v,
        Ok(None) => {
            // renames succeeded but the content is not what we wrote (concurrent writer / bad server): keep everything
            return match reconcile(p, path, c.size, &c.sha256, &c.attempt).await {
                Ok(Reconciled { recovery: Recovery::OriginalRestored | Recovery::OriginalIntact, .. }) => {
                    let _ = log.record(Step::Resolved { outcome: "original_restored".into() });
                    Err(NdError::new(Code::Conflict).with("path", path).with("original_preserved", true))
                }
                _ => {
                    let _ = log.record(Step::Uncertain);
                    Err(uncertain(path, &NdError::new(Code::ServerAnomaly)))
                }
            };
        }
        Err(e) => return settle(p, c, log, e).await,
    };
    rec!(p, c, log, Step::NewVerified);
    rec!(p, c, log, Step::BackupDeleteIntent);
    let mut residue = vec![];
    if p.delete_file(&bak).await.is_err() && exists(p, &bak).await.unwrap_or(true) {
        residue.push(bak);
    }
    let _ = log.record(Step::Completed { atomic: false });
    Ok(Replaced { atomic: false, verified, residue })
}

/// What recovery did with one journaled attempt.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Recovered {
    /// The attempt had already finished: nothing to do.
    AlreadyDone,
    NewInPlace,
    OriginalIntact,
    OriginalRestored,
    /// Cannot tell / journal unusable: leftovers kept, the user must decide.
    Uncertain { reason: String },
}

/// Recovery of one attempt after a crash/restart/reconnect. Remote state decides; the journal only says
/// which names belong to this attempt. Appends the decision to the journal.
pub async fn recover<P: Provider + ?Sized>(p: &P, replay: &crate::journal::Replay, log: &mut dyn ReplaceLog) -> Result<Recovered> {
    if replay.terminal() && replay.damage.is_none() {
        return Ok(Recovered::AlreadyDone);
    }
    // Begin is journaled before ANY remote operation: no (or only a torn) first record proves nothing started.
    if replay.steps.is_empty() && matches!(replay.damage, None | Some("truncated_header") | Some("truncated_record")) {
        let _ = log.record(Step::Resolved { outcome: "original_intact".into() });
        return Ok(Recovered::OriginalIntact);
    }
    let Some(Step::Begin { path, size, sha256, .. }) = replay.begin() else {
        // the first record is the one that names the files: without it nothing may be touched
        let _ = log.record(Step::Uncertain);
        return Ok(Recovered::Uncertain { reason: "journal_without_begin".into() });
    };
    let r = reconcile(p, path, *size, sha256, &replay.attempt).await?;
    Ok(match r.recovery {
        Recovery::NewInPlace => {
            let _ = log.record(Step::Resolved { outcome: "new_in_place".into() });
            Recovered::NewInPlace
        }
        Recovery::OriginalIntact => {
            let _ = log.record(Step::Resolved { outcome: "original_intact".into() });
            Recovered::OriginalIntact
        }
        Recovery::OriginalRestored => {
            let _ = log.record(Step::Resolved { outcome: "original_restored".into() });
            Recovered::OriginalRestored
        }
        Recovery::Uncertain => {
            let _ = log.record(Step::Uncertain);
            Recovered::Uncertain { reason: replay.damage.map(|d| d.to_string()).unwrap_or_else(|| "remote_state_ambiguous".into()) }
        }
    })
}

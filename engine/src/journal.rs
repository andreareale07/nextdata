//! Write-ahead journal for remote replacements.
//!
//! One file per attempt: `<data_dir>/journal/<attempt>.ndj`, append-only. Every record is
//! `u32 length (LE) || AEAD blob`, sealed with HKDF(PMK, "journal") and an AAD binding the attempt id
//! and the record sequence number (records cannot be reordered, dropped from the middle or moved
//! between attempts). Each append is fsynced BEFORE the remote operation it announces is started
//! ("intent" records) and again after it finished ("done" records).
//!
//! Reading stops at the first record that is incomplete (torn tail: crash while writing) or fails
//! authentication (corruption/tampering). The valid prefix is trusted, the rest is reported as
//! `damage` and the attempt is treated as `Uncertain` unless the prefix already ends in a terminal record.
use crate::crypto;
use crate::error::{Code, NdError, Result};
use crate::types::RemoteVersion;
use serde::{Deserialize, Serialize};
use std::io::Write;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "step", rename_all = "snake_case")]
pub enum Step {
    /// First record: everything needed to find and reconcile the remote leftovers.
    Begin { profile_id: String, path: String, size: u64, sha256: String, expected: Option<RemoteVersion> },
    UploadIntent,
    Uploaded,
    TmpVerified,
    BackupIntent,
    BackupDone,
    SwapIntent,
    SwapDone,
    NewVerified,
    BackupDeleteIntent,
    /// Plain overwrite providers (SMB/WebDAV/S3): announces the single PUT/MOVE.
    PutIntent,
    PutDone,
    // ---- terminal records
    Completed { atomic: bool },
    /// The attempt failed and the original is known to be intact.
    Failed { code: String },
    /// Recovery decided: "new_in_place" | "original_intact" | "original_restored"
    Resolved { outcome: String },
    /// Recovery could not establish what happened: leftovers are kept, user decides.
    Uncertain,
}

impl Step {
    pub fn is_terminal(&self) -> bool {
        matches!(self, Step::Completed { .. } | Step::Failed { .. } | Step::Resolved { .. })
    }
}

/// Sink for transitions. `record` must be durable when it returns.
pub trait ReplaceLog: Send {
    fn record(&mut self, step: Step) -> Result<()>;
}

/// No journaling (tests of pure sequences, providers used outside the engine).
pub struct NoLog;
impl ReplaceLog for NoLog {
    fn record(&mut self, _: Step) -> Result<()> {
        Ok(())
    }
}

pub struct JournalDir {
    dir: PathBuf,
    key: [u8; 32],
}

fn aad(attempt: &str, seq: u32) -> Vec<u8> {
    format!("nextdata-journal-v1|{attempt}|{seq}").into_bytes()
}

/// What a journal file says after reading its valid prefix.
#[derive(Debug, Clone)]
pub struct Replay {
    pub attempt: String,
    pub steps: Vec<Step>,
    /// None = file ended cleanly at a record boundary.
    pub damage: Option<&'static str>,
}

impl Replay {
    pub fn begin(&self) -> Option<&Step> {
        self.steps.first().filter(|s| matches!(s, Step::Begin { .. }))
    }
    pub fn terminal(&self) -> bool {
        self.steps.last().is_some_and(|s| s.is_terminal())
    }
    /// Needs attention at startup/reconnect: not finished, or finished state cannot be trusted.
    pub fn pending(&self) -> bool {
        !self.terminal()
    }
}

impl JournalDir {
    pub fn open(data_dir: &str, pmk: &[u8]) -> Result<JournalDir> {
        let dir = PathBuf::from(data_dir).join("journal");
        std::fs::create_dir_all(&dir)?;
        Ok(JournalDir { dir, key: crypto::derive(pmk, "journal") })
    }

    fn file(&self, attempt: &str) -> Result<PathBuf> {
        if attempt.is_empty() || !attempt.chars().all(|c| c.is_ascii_alphanumeric()) {
            return Err(NdError::invalid("attempt"));
        }
        Ok(self.dir.join(format!("{attempt}.ndj")))
    }

    /// Starts a new attempt log (fails if the attempt id already exists).
    pub fn create(&self, attempt: &str) -> Result<FileLog> {
        let path = self.file(attempt)?;
        let f = std::fs::OpenOptions::new().append(true).create_new(true).open(&path)?;
        Ok(FileLog { f, key: self.key, attempt: attempt.to_string(), seq: 0 })
    }

    /// Reopens an existing log to append recovery records after its last valid record.
    pub fn reopen(&self, attempt: &str) -> Result<FileLog> {
        let rp = self.read(attempt)?;
        let path = self.file(attempt)?;
        // a torn/corrupt tail must not sit between old and new records: rewrite the valid prefix
        let log = FileLog::rewrite(&path, self.key, attempt, &rp.steps)?;
        Ok(log)
    }

    pub fn read(&self, attempt: &str) -> Result<Replay> {
        let path = self.file(attempt)?;
        let data = std::fs::read(&path)?;
        let (mut steps, mut damage) = (vec![], None);
        let mut off = 0usize;
        let mut seq = 0u32;
        while off < data.len() {
            if data.len() - off < 4 {
                damage = Some("truncated_header");
                break;
            }
            let len = u32::from_le_bytes(data[off..off + 4].try_into().unwrap()) as usize;
            if len > 1 << 20 {
                damage = Some("bad_length");
                break;
            }
            if data.len() - off - 4 < len {
                damage = Some("truncated_record");
                break;
            }
            let blob = &data[off + 4..off + 4 + len];
            match crypto::open(&self.key, &aad(attempt, seq), blob).ok().and_then(|p| serde_json::from_slice::<Step>(&p).ok()) {
                Some(s) => steps.push(s),
                None => {
                    damage = Some("corrupt_record");
                    break;
                }
            }
            off += 4 + len;
            seq += 1;
        }
        Ok(Replay { attempt: attempt.to_string(), steps, damage })
    }

    /// Attempt ids that have a journal file.
    pub fn attempts(&self) -> Vec<String> {
        let mut v: Vec<String> = std::fs::read_dir(&self.dir)
            .map(|rd| {
                rd.filter_map(|e| e.ok())
                    .filter_map(|e| e.file_name().to_str().and_then(|n| n.strip_suffix(".ndj").map(|s| s.to_string())))
                    .collect()
            })
            .unwrap_or_default();
        v.sort();
        v
    }

    pub fn path_of(&self, attempt: &str) -> Result<PathBuf> {
        self.file(attempt)
    }

    /// Removes a finished attempt's journal (only terminal, undamaged attempts).
    pub fn forget(&self, attempt: &str) -> Result<()> {
        let rp = self.read(attempt)?;
        if !rp.terminal() || rp.damage.is_some() {
            return Err(NdError::new(Code::Conflict).with("reason", "journal_not_terminal"));
        }
        std::fs::remove_file(self.file(attempt)?)?;
        Ok(())
    }
}

pub struct FileLog {
    f: std::fs::File,
    key: [u8; 32],
    attempt: String,
    seq: u32,
}

impl FileLog {
    fn rewrite(path: &Path, key: [u8; 32], attempt: &str, steps: &[Step]) -> Result<FileLog> {
        let tmp = path.with_extension("ndj.tmp");
        {
            let mut f = std::fs::File::create(&tmp)?;
            for (i, s) in steps.iter().enumerate() {
                f.write_all(&Self::frame(&key, attempt, i as u32, s))?;
            }
            f.sync_all()?;
        }
        std::fs::rename(&tmp, path)?;
        let f = std::fs::OpenOptions::new().append(true).open(path)?;
        Ok(FileLog { f, key, attempt: attempt.to_string(), seq: steps.len() as u32 })
    }

    fn frame(key: &[u8; 32], attempt: &str, seq: u32, s: &Step) -> Vec<u8> {
        let plain = serde_json::to_vec(s).expect("step json");
        let blob = crypto::seal(key, &aad(attempt, seq), &plain);
        let mut out = (blob.len() as u32).to_le_bytes().to_vec();
        out.extend_from_slice(&blob);
        out
    }
}

impl ReplaceLog for FileLog {
    fn record(&mut self, step: Step) -> Result<()> {
        let frame = Self::frame(&self.key, &self.attempt, self.seq, &step);
        self.f.write_all(&frame)?;
        self.f.sync_all()?;
        self.seq += 1;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn jd() -> (tempfile::TempDir, JournalDir) {
        let t = tempfile::tempdir().unwrap();
        let j = JournalDir::open(t.path().to_str().unwrap(), &[9u8; 32]).unwrap();
        (t, j)
    }
    fn begin() -> Step {
        Step::Begin { profile_id: "p".into(), path: "/a".into(), size: 3, sha256: "x".into(), expected: None }
    }

    #[test]
    fn roundtrip_and_terminal() {
        let (_t, j) = jd();
        let mut l = j.create("a1").unwrap();
        l.record(begin()).unwrap();
        l.record(Step::UploadIntent).unwrap();
        let r = j.read("a1").unwrap();
        assert_eq!(r.steps.len(), 2);
        assert!(r.damage.is_none() && !r.terminal());
        l.record(Step::Completed { atomic: false }).unwrap();
        assert!(j.read("a1").unwrap().terminal());
        j.forget("a1").unwrap();
        assert!(j.attempts().is_empty());
    }

    #[test]
    fn torn_tail_keeps_valid_prefix() {
        let (_t, j) = jd();
        let mut l = j.create("a2").unwrap();
        l.record(begin()).unwrap();
        l.record(Step::UploadIntent).unwrap();
        let p = j.path_of("a2").unwrap();
        let len = std::fs::metadata(&p).unwrap().len();
        let f = std::fs::OpenOptions::new().write(true).open(&p).unwrap();
        f.set_len(len - 5).unwrap(); // crash while writing the last record
        let r = j.read("a2").unwrap();
        assert_eq!(r.steps, vec![begin()]);
        assert_eq!(r.damage, Some("truncated_record"));
    }

    #[test]
    fn corruption_and_reordering_are_detected() {
        let (_t, j) = jd();
        let mut l = j.create("a3").unwrap();
        l.record(begin()).unwrap();
        l.record(Step::UploadIntent).unwrap();
        l.record(Step::Uploaded).unwrap();
        let p = j.path_of("a3").unwrap();
        let mut d = std::fs::read(&p).unwrap();
        let mid = d.len() / 2;
        d[mid] ^= 0xff;
        std::fs::write(&p, &d).unwrap();
        let r = j.read("a3").unwrap();
        assert!(r.damage.is_some() && r.steps.len() < 3);
        // swapping two records breaks the sequence binding
        let mut l = j.create("a4").unwrap();
        l.record(begin()).unwrap();
        l.record(Step::UploadIntent).unwrap();
        l.record(Step::Uploaded).unwrap();
        let p4 = j.path_of("a4").unwrap();
        let d = std::fs::read(&p4).unwrap();
        let mut frames = vec![];
        let mut o = 0;
        while o < d.len() {
            let n = u32::from_le_bytes(d[o..o + 4].try_into().unwrap()) as usize;
            frames.push(d[o..o + 4 + n].to_vec());
            o += 4 + n;
        }
        let swapped: Vec<u8> = [frames[0].clone(), frames[2].clone(), frames[1].clone()].concat();
        std::fs::write(&p4, swapped).unwrap();
        let r = j.read("a4").unwrap();
        assert_eq!(r.steps.len(), 1);
        assert_eq!(r.damage, Some("corrupt_record"));
        // a journal from another PMK reads as damaged, never as valid
        let j2 = JournalDir::open(p4.parent().unwrap().parent().unwrap().to_str().unwrap(), &[1u8; 32]).unwrap();
        assert_eq!(j2.read("a1_missing").is_err(), true);
    }

    #[test]
    fn hostile_attempt_ids_rejected() {
        let (_t, j) = jd();
        assert!(j.create("../x").is_err());
        assert!(j.read("a/b").is_err());
        assert!(j.create("").is_err());
    }
}

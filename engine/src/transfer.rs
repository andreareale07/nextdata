//! Transfer engine: plan -> execute. Recursive walks use an explicit stack, never follow links,
//! and are bounded. Downloads go to `<name>.nd-part` (+ sidecar for resume) and are published with a
//! rename that can never silently replace somebody else's file (the final name is reserved with
//! `create_new` first). Uploads rely on provider-side temp+rename / conditional create.
use crate::error::{Code, NdError, Result};
use crate::pathguard::{basename, join, numbered, remote_name_ok, safe_local_name};
use crate::provider::{exists, Progress, Provider, CHUNK};
use crate::types::{Entry, EntryKind};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

pub const MAX_DEPTH: usize = 64;
pub const MAX_ENTRIES: usize = 250_000;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Collision {
    Rename,
    Skip,
    Fail,
}
impl Collision {
    pub fn parse(s: &str) -> Result<Collision> {
        match s {
            "rename" | "" => Ok(Collision::Rename),
            "skip" => Ok(Collision::Skip),
            "fail" => Ok(Collision::Fail),
            _ => Err(NdError::invalid("collision")),
        }
    }
}

/// Item-level progress reporting.
pub struct Reporter {
    pub pr: Progress,
    pub total: Arc<std::sync::atomic::AtomicU64>,
    pub on_item: Arc<dyn Fn(&str, u64, u64) + Send + Sync>, // (item name, items_done, items_total)
}

// ---------------------------------------------------------------- remote walk

pub struct PlanItem {
    pub remote: String,
    pub rel: Vec<String>, // sanitized local components
    pub kind: EntryKind,
    pub size: u64,
    pub mtime_ms: Option<i64>,
}

/// Enumerate everything under `ref_` (inclusive). Symlinks/other are skipped (counted by caller if needed).
pub async fn plan_remote(p: &dyn Provider, ref_: &str, pr: &Progress) -> Result<(Vec<PlanItem>, u64)> {
    let root = p.stat(ref_).await?;
    let root_name = safe_local_name(basename(ref_));
    let mut out = vec![];
    let mut total = 0u64;
    match root.kind {
        EntryKind::File => {
            total = root.size.unwrap_or(0);
            out.push(PlanItem { remote: ref_.into(), rel: vec![root_name], kind: EntryKind::File, size: total, mtime_ms: root.mtime_ms });
        }
        EntryKind::Dir => {
            out.push(PlanItem { remote: ref_.into(), rel: vec![root_name.clone()], kind: EntryKind::Dir, size: 0, mtime_ms: None });
            let mut stack: Vec<(String, Vec<String>, usize)> = vec![(ref_.to_string(), vec![root_name], 0)];
            while let Some((dir, rel, depth)) = stack.pop() {
                pr.check()?;
                if depth >= MAX_DEPTH {
                    return Err(NdError::new(Code::LimitExceeded).with("path", dir).with("limit", "depth"));
                }
                let raw = pr.run(async { p.list(&dir).await }).await?;
                let (entries, _anom) = crate::pathguard::sanitize_listing(&dir, raw);
                for e in entries {
                    match e.kind {
                        EntryKind::File => {
                            let mut r = rel.clone();
                            r.push(e.safe_local_name.clone());
                            total += e.size.unwrap_or(0);
                            out.push(PlanItem { remote: e.ref_, rel: r, kind: EntryKind::File, size: e.size.unwrap_or(0), mtime_ms: e.mtime_ms });
                        }
                        EntryKind::Dir => {
                            let mut r = rel.clone();
                            r.push(e.safe_local_name.clone());
                            out.push(PlanItem { remote: e.ref_.clone(), rel: r.clone(), kind: EntryKind::Dir, size: 0, mtime_ms: None });
                            stack.push((e.ref_, r, depth + 1));
                        }
                        _ => {} // never traverse/download links or special files
                    }
                    if out.len() > MAX_ENTRIES {
                        return Err(NdError::new(Code::LimitExceeded).with("limit", "entries"));
                    }
                }
            }
        }
        _ => return Err(NdError::new(Code::Unsupported).with("path", ref_).detail("special file")),
    }
    Ok((out, total))
}

// ---------------------------------------------------------------- download

#[derive(Serialize, Deserialize, PartialEq, Debug)]
struct PartMeta {
    remote: String,
    size: u64,
    mtime_ms: Option<i64>,
}

fn part_paths(final_path: &Path) -> (PathBuf, PathBuf) {
    let name = final_path.file_name().unwrap().to_string_lossy().to_string();
    let dir = final_path.parent().unwrap();
    (dir.join(format!("{name}.nd-part")), dir.join(format!("{name}.nd-part.meta")))
}

/// Pick a non-existing final path honoring the collision policy. Ok(None) = skip.
fn choose_final(dir: &Path, name: &str, col: Collision) -> Result<Option<PathBuf>> {
    let first = dir.join(name);
    if !first.exists() && std::fs::symlink_metadata(&first).is_err() {
        return Ok(Some(first));
    }
    match col {
        Collision::Skip => Ok(None),
        Collision::Fail => Err(NdError::new(Code::AlreadyExists).with("name", name)),
        Collision::Rename => {
            for n in 1..10_000u32 {
                let cand = dir.join(numbered(name, n));
                if std::fs::symlink_metadata(&cand).is_err() {
                    return Ok(Some(cand));
                }
            }
            Err(NdError::new(Code::AlreadyExists).with("name", name))
        }
    }
}

/// Reserve the final name atomically (create_new), re-picking on a race.
fn reserve(dir: &Path, name: &str, col: Collision) -> Result<Option<PathBuf>> {
    for _ in 0..50 {
        let Some(path) = choose_final(dir, name, col)? else { return Ok(None) };
        match std::fs::OpenOptions::new().write(true).create_new(true).open(&path) {
            Ok(_) => return Ok(Some(path)),
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(e) => return Err(e.into()),
        }
    }
    Err(NdError::new(Code::AlreadyExists).with("name", name))
}

async fn download_file(p: &dyn Provider, item: &PlanItem, final_path: &Path, pr: &Progress) -> Result<()> {
    let (part, meta_path) = part_paths(final_path);
    let meta = PartMeta { remote: item.remote.clone(), size: item.size, mtime_ms: item.mtime_ms };
    // resume only when the sidecar proves it is the same remote object
    let mut offset = 0u64;
    if let (Ok(m), Ok(b)) = (std::fs::metadata(&part), std::fs::read(&meta_path)) {
        if serde_json::from_slice::<PartMeta>(&b).ok().as_ref() == Some(&meta) && m.len() < item.size && item.mtime_ms.is_some() {
            offset = m.len();
        }
    }
    let mut f = if offset > 0 {
        tokio::fs::OpenOptions::new().append(true).open(&part).await?
    } else {
        let f = tokio::fs::OpenOptions::new().write(true).create(true).truncate(true).open(&part).await?;
        std::fs::write(&meta_path, serde_json::to_vec(&meta).unwrap())?;
        f
    };
    pr.report(offset);
    let res: Result<()> = async {
        let mut r = pr.run(async { p.open_read(&item.remote, offset).await }).await?;
        let mut buf = vec![0u8; CHUNK];
        let mut written = offset;
        loop {
            pr.check()?;
            let n = pr.run(async { r.read(&mut buf).await.map_err(NdError::from) }).await?;
            if n == 0 {
                break;
            }
            written += n as u64;
            if written > item.size {
                return Err(NdError::new(Code::ServerAnomaly).with("path", item.remote.clone()).detail("server sent more data than declared"));
            }
            f.write_all(&buf[..n]).await?;
            pr.report(n as u64);
        }
        if written != item.size {
            return Err(NdError::new(Code::Network).with("path", item.remote.clone()).detail("short read"));
        }
        f.flush().await?;
        f.sync_all().await?;
        Ok(())
    }
    .await;
    drop(f);
    match res {
        Ok(()) => {
            if let Some(ms) = item.mtime_ms {
                if ms > 0 {
                    if let Ok(file) = std::fs::OpenOptions::new().write(true).open(&part) {
                        let _ = file.set_modified(std::time::UNIX_EPOCH + std::time::Duration::from_millis(ms as u64));
                    }
                }
            }
            std::fs::rename(&part, final_path)?; // replaces our own reservation only
            let _ = std::fs::remove_file(&meta_path);
            Ok(())
        }
        Err(e) => {
            // keep the partial (resumable) on transient failures, drop it otherwise
            if !matches!(e.code, Code::Network | Code::Timeout | Code::Cancelled) {
                let _ = std::fs::remove_file(&part);
                let _ = std::fs::remove_file(&meta_path);
            }
            let _ = std::fs::remove_file(final_path); // the 0-byte reservation
            Err(e)
        }
    }
}

pub async fn download(p: &dyn Provider, refs: &[String], dest_dir: &str, col: Collision, rep: &Reporter) -> Result<Vec<String>> {
    let dest = PathBuf::from(dest_dir);
    if !dest.is_dir() {
        return Err(NdError::new(Code::NotFound).with("path", dest_dir));
    }
    let pr = &rep.pr;
    let mut plans = vec![];
    let mut total = 0u64;
    let mut count = 0u64;
    for r in refs {
        let (items, t) = plan_remote(p, r, pr).await?;
        total += t;
        count += items.iter().filter(|i| i.kind == EntryKind::File).count() as u64;
        plans.push(items);
    }
    rep.total.store(total, std::sync::atomic::Ordering::Relaxed);
    (rep.on_item)("", 0, count);
    let mut results = vec![];
    let mut done = 0u64;
    for items in plans {
        // mapping from rel-dir to the (possibly renamed) local directory name
        let mut root_local: Option<PathBuf> = None;
        let mut skipped_root = false;
        for it in &items {
            pr.check()?;
            if skipped_root {
                break;
            }
            match it.kind {
                EntryKind::Dir => {
                    if it.rel.len() == 1 {
                        match choose_final(&dest, &it.rel[0], col)? {
                            None => {
                                skipped_root = true;
                                continue;
                            }
                            Some(path) => {
                                std::fs::create_dir(&path)?;
                                root_local = Some(path.clone());
                                results.push(path.to_string_lossy().to_string());
                            }
                        }
                    } else {
                        let mut path = root_local.clone().ok_or_else(|| NdError::internal("no root"))?;
                        for c in &it.rel[1..] {
                            path.push(c);
                        }
                        std::fs::create_dir_all(&path)?;
                    }
                }
                EntryKind::File => {
                    let (dir, name) = if it.rel.len() == 1 {
                        (dest.clone(), it.rel[0].clone())
                    } else {
                        let mut path = root_local.clone().ok_or_else(|| NdError::internal("no root"))?;
                        for c in &it.rel[1..it.rel.len() - 1] {
                            path.push(c);
                        }
                        (path, it.rel.last().unwrap().clone())
                    };
                    // children of a freshly created directory merge with the "rename" policy only at the root
                    let inner_col = if it.rel.len() == 1 { col } else { Collision::Rename };
                    let Some(final_path) = reserve(&dir, &name, inner_col)? else {
                        done += 1;
                        continue;
                    };
                    (rep.on_item)(&name, done, count);
                    download_file(p, it, &final_path, pr).await?;
                    done += 1;
                    if it.rel.len() == 1 {
                        results.push(final_path.to_string_lossy().to_string());
                    }
                }
                _ => {}
            }
        }
    }
    (rep.on_item)("", done, count);
    Ok(results)
}

// ---------------------------------------------------------------- upload

async fn unique_remote_name(p: &dyn Provider, parent: &str, name: &str, col: Collision) -> Result<Option<String>> {
    let first = join(parent, name);
    if !exists(p, &first).await? {
        return Ok(Some(name.to_string()));
    }
    match col {
        Collision::Skip => Ok(None),
        Collision::Fail => Err(NdError::new(Code::AlreadyExists).with("name", name)),
        Collision::Rename => {
            for n in 1..10_000u32 {
                let cand = numbered(name, n);
                if !exists(p, &join(parent, &cand)).await? {
                    return Ok(Some(cand));
                }
            }
            Err(NdError::new(Code::AlreadyExists).with("name", name))
        }
    }
}

fn is_fd_path(p: &Path) -> bool {
    p.to_string_lossy().starts_with("/proc/self/fd/")
}

async fn upload_file(p: &dyn Provider, local: &Path, parent: &str, name: &str, col: Collision, pr: &Progress) -> Result<Option<String>> {
    if !remote_name_ok(name) {
        return Err(NdError::invalid("name").with("name", name));
    }
    let size = tokio::fs::metadata(local).await?.len();
    let mut name = name.to_string();
    for _ in 0..5 {
        let Some(n) = unique_remote_name(p, parent, &name, col).await? else { return Ok(None) };
        let target = join(parent, &n);
        let f = tokio::fs::File::open(local).await?;
        match p.put(&target, Box::new(f), size, false, pr).await {
            Ok(()) => return Ok(Some(target)),
            Err(e) if e.code == Code::AlreadyExists && col == Collision::Rename => {
                name = n; // lost a race: the next unique name will be chosen
                continue;
            }
            Err(e) => return Err(e),
        }
    }
    Err(NdError::new(Code::AlreadyExists).with("name", name))
}

pub async fn upload(p: &dyn Provider, locals: &[String], parent: &str, col: Collision, rep: &Reporter) -> Result<Vec<String>> {
    let pr = &rep.pr;
    // plan: (local path, remote relative dir components, is_dir)
    let mut files: Vec<(PathBuf, Vec<String>)> = vec![];
    let mut dirs_to_make: Vec<Vec<String>> = vec![];
    let mut roots: Vec<(String, bool)> = vec![];
    let mut count = 0usize;
    for l in locals {
        let lp = PathBuf::from(l);
        let md = if is_fd_path(&lp) { tokio::fs::metadata(&lp).await? } else { tokio::fs::symlink_metadata(&lp).await? };
        let name = lp.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
        if md.file_type().is_symlink() {
            continue; // never follow links
        }
        if md.is_file() {
            files.push((lp, vec![name.clone()]));
            roots.push((name, false));
            count += 1;
        } else if md.is_dir() {
            dirs_to_make.push(vec![name.clone()]);
            roots.push((name.clone(), true));
            let mut stack = vec![(lp.clone(), vec![name], 0usize)];
            while let Some((dir, rel, depth)) = stack.pop() {
                pr.check()?;
                if depth >= MAX_DEPTH {
                    return Err(NdError::new(Code::LimitExceeded).with("limit", "depth"));
                }
                let mut rd = tokio::fs::read_dir(&dir).await?;
                while let Some(e) = rd.next_entry().await? {
                    let ft = e.file_type().await?;
                    if ft.is_symlink() {
                        continue;
                    }
                    let n = e.file_name().to_string_lossy().to_string();
                    let mut r2 = rel.clone();
                    r2.push(n);
                    if ft.is_dir() {
                        dirs_to_make.push(r2.clone());
                        stack.push((e.path(), r2, depth + 1));
                    } else if ft.is_file() {
                        files.push((e.path(), r2));
                        count += 1;
                    }
                    if files.len() + dirs_to_make.len() > MAX_ENTRIES {
                        return Err(NdError::new(Code::LimitExceeded).with("limit", "entries"));
                    }
                }
            }
        }
    }
    (rep.on_item)("", 0, count as u64);
    let mut created: Vec<String> = vec![];
    // remote root directory names (after collision handling) keyed by the original root name
    let mut root_map: std::collections::HashMap<String, Option<String>> = Default::default();
    for (name, is_dir) in &roots {
        if *is_dir {
            if !remote_name_ok(name) {
                return Err(NdError::invalid("name").with("name", name.clone()));
            }
            match unique_remote_name(p, parent, name, col).await? {
                None => {
                    root_map.insert(name.clone(), None);
                }
                Some(n) => {
                    p.mkdir(&join(parent, &n)).await?;
                    created.push(join(parent, &n));
                    root_map.insert(name.clone(), Some(n));
                }
            }
        }
    }
    // remaining directories (parents come before children because of discovery order)
    dirs_to_make.sort_by_key(|d| d.len());
    for rel in &dirs_to_make {
        if rel.len() == 1 {
            continue;
        }
        let Some(Some(root)) = root_map.get(&rel[0]) else { continue };
        let mut path = join(parent, root);
        for c in &rel[1..] {
            if !remote_name_ok(c) {
                return Err(NdError::invalid("name").with("name", c.clone()));
            }
            path = join(&path, c);
        }
        match p.mkdir(&path).await {
            Ok(()) => {}
            Err(e) if e.code == Code::AlreadyExists => {}
            Err(e) => return Err(e),
        }
    }
    let mut done = 0u64;
    for (local, rel) in files {
        pr.check()?;
        let name = rel.last().unwrap().clone();
        (rep.on_item)(&name, done, count as u64);
        let (target_parent, col2) = if rel.len() == 1 {
            (parent.to_string(), col)
        } else {
            match root_map.get(&rel[0]) {
                Some(Some(root)) => {
                    let mut path = join(parent, root);
                    for c in &rel[1..rel.len() - 1] {
                        path = join(&path, c);
                    }
                    (path, Collision::Rename)
                }
                _ => {
                    done += 1;
                    continue; // root directory skipped
                }
            }
        };
        if let Some(t) = upload_file(p, &local, &target_parent, &name, col2, pr).await? {
            if rel.len() == 1 {
                created.push(t);
            }
        }
        done += 1;
    }
    (rep.on_item)("", done, count as u64);
    Ok(created)
}

// ---------------------------------------------------------------- delete

/// Delete refs. Directories are removed bottom-up using an explicit stack; links are removed as links.
pub async fn delete(p: &dyn Provider, refs: &[String], recursive: bool, rep: &Reporter) -> Result<u64> {
    let pr = &rep.pr;
    let mut done = 0u64;
    for r in refs {
        pr.check()?;
        let st = p.stat(r).await?;
        match st.kind {
            EntryKind::Dir => {
                if !recursive {
                    if !p.list(r).await?.is_empty() {
                        return Err(NdError::new(Code::InvalidArgument).with("path", r.clone()).detail("directory not empty"));
                    }
                    p.delete_dir(r).await?;
                    done += 1;
                    (rep.on_item)(basename(r), done, 0);
                    continue;
                }
                // collect post-order
                let mut order: Vec<(String, bool)> = vec![];
                let mut stack: Vec<(String, usize)> = vec![(r.clone(), 0)];
                while let Some((dir, depth)) = stack.pop() {
                    pr.check()?;
                    if depth >= MAX_DEPTH {
                        return Err(NdError::new(Code::LimitExceeded).with("limit", "depth"));
                    }
                    order.push((dir.clone(), true));
                    let raw = pr.run(async { p.list(&dir).await }).await?;
                    let (entries, _) = crate::pathguard::sanitize_listing(&dir, raw);
                    for e in entries {
                        if e.kind == EntryKind::Dir {
                            stack.push((e.ref_, depth + 1));
                        } else {
                            order.push((e.ref_, false)); // files, links, special: unlink only
                        }
                    }
                    if order.len() > MAX_ENTRIES {
                        return Err(NdError::new(Code::LimitExceeded).with("limit", "entries"));
                    }
                }
                for (path, is_dir) in order.into_iter().rev() {
                    pr.check()?;
                    if is_dir {
                        p.delete_dir(&path).await?;
                    } else {
                        p.delete_file(&path).await?;
                    }
                    done += 1;
                    (rep.on_item)(basename(&path), done, 0);
                }
            }
            _ => {
                p.delete_file(r).await?;
                done += 1;
                (rep.on_item)(basename(r), done, 0);
            }
        }
    }
    Ok(done)
}

/// Used by the engine to convert an Entry list into JSON-able result.
pub fn entry_of(parent: &str, raw: crate::types::RawEntry) -> Entry {
    Entry {
        ref_: join(parent, &raw.name),
        safe_local_name: safe_local_name(&raw.name),
        name: raw.name,
        kind: raw.kind,
        size: raw.size,
        mtime_ms: raw.mtime_ms,
    }
}

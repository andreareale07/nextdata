//! Path Guard: validity of remote names, structural anti-traversal, local-name mapping,
//! and normalisation of opaque `ref`s. Kotlin never builds paths.
use crate::error::{NdError, Result};
use crate::types::{Entry, RawEntry};

pub const MAX_NAME_BYTES: usize = 1024;
pub const MAX_REF_BYTES: usize = 8192;

/// A remote entry name coming from a (possibly hostile) server.
pub fn remote_name_ok(name: &str) -> bool {
    !(name.is_empty() || name == "." || name == ".." || name.contains('/') || name.contains('\0') || name.len() > MAX_NAME_BYTES)
}

/// A name typed by the user for mkdir/rename.
pub fn validate_user_name(name: &str) -> Result<String> {
    let n = name.trim();
    if !remote_name_ok(n) {
        return Err(NdError::invalid("name").with("name", name));
    }
    if n.chars().any(|c| c.is_control()) {
        return Err(NdError::invalid("control characters").with("name", name));
    }
    Ok(n.to_string())
}

const RESERVED: [&str; 22] = [
    "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2",
    "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
];

/// Maps a remote name to a name valid on every supported local OS (Windows is the strictest).
pub fn safe_local_name(name: &str) -> String {
    let mut s: String = name
        .chars()
        .map(|c| match c {
            '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*' => '_',
            c if (c as u32) < 32 || c == '\u{7f}' => '_',
            // bidi controls: avoid visual spoofing in local names
            '\u{202a}'..='\u{202e}' | '\u{2066}'..='\u{2069}' => '_',
            c => c,
        })
        .collect();
    while s.ends_with('.') || s.ends_with(' ') {
        s.pop();
    }
    s = s.trim_start().to_string();
    if s.is_empty() {
        s = "_".into();
    }
    let stem = s.split('.').next().unwrap_or("").to_ascii_uppercase();
    if RESERVED.contains(&stem.as_str()) {
        s.insert(0, '_');
    }
    if s.len() > 200 {
        let (base, ext) = match s.rfind('.') {
            Some(i) if s.len() - i <= 16 => (s[..i].to_string(), s[i..].to_string()),
            _ => (s.clone(), String::new()),
        };
        let mut b = base;
        while b.len() + ext.len() > 200 {
            b.pop();
        }
        s = format!("{b}{ext}");
    }
    s
}

/// Normalise a ref into an absolute '/'-separated path without '.'/'..' or empty segments.
/// `..` is NEVER resolved: a ref containing it is rejected.
pub fn norm_ref(r: &str) -> Result<String> {
    if r.len() > MAX_REF_BYTES || r.contains('\0') {
        return Err(NdError::invalid("ref"));
    }
    let mut out = String::new();
    for seg in r.split('/') {
        match seg {
            "" | "." => {}
            ".." => return Err(NdError::invalid("traversal in ref")),
            s => {
                out.push('/');
                out.push_str(s);
            }
        }
    }
    if out.is_empty() {
        out.push('/');
    }
    Ok(out)
}

pub fn join(parent: &str, name: &str) -> String {
    if parent == "/" {
        format!("/{name}")
    } else {
        format!("{parent}/{name}")
    }
}

pub fn parent_of(r: &str) -> Option<String> {
    if r == "/" {
        return None;
    }
    match r.rfind('/') {
        Some(0) => Some("/".into()),
        Some(i) => Some(r[..i].to_string()),
        None => None,
    }
}

pub fn basename(r: &str) -> &str {
    r.rsplit('/').next().unwrap_or("")
}

pub fn breadcrumbs(r: &str) -> Vec<(String, String)> {
    let mut v = vec![("/".to_string(), "/".to_string())];
    let mut cur = String::new();
    for seg in r.split('/').filter(|s| !s.is_empty()) {
        cur.push('/');
        cur.push_str(seg);
        v.push((seg.to_string(), cur.clone()));
    }
    v
}

/// Filter a raw directory listing: drop anomalous/duplicate names (counted) and build Entries.
pub fn sanitize_listing(parent: &str, raw: Vec<RawEntry>) -> (Vec<Entry>, usize) {
    let mut anomalies = 0;
    let mut seen = std::collections::HashSet::new();
    let mut out = Vec::with_capacity(raw.len());
    for e in raw {
        if !remote_name_ok(&e.name) || !seen.insert(e.name.clone()) {
            anomalies += 1;
            continue;
        }
        out.push(Entry {
            ref_: join(parent, &e.name),
            safe_local_name: safe_local_name(&e.name),
            name: e.name,
            kind: e.kind,
            size: e.size,
            mtime_ms: e.mtime_ms,
        });
    }
    (out, anomalies)
}

/// "name (1).ext" collision renaming
pub fn numbered(name: &str, n: u32) -> String {
    match name.rfind('.') {
        Some(i) if i > 0 => format!("{} ({}){}", &name[..i], n, &name[i..]),
        _ => format!("{name} ({n})"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn names() {
        assert!(!remote_name_ok(".."));
        assert!(!remote_name_ok("a/b"));
        assert!(!remote_name_ok(""));
        assert_eq!(safe_local_name("CON"), "_CON");
        assert_eq!(safe_local_name("nul.txt"), "_nul.txt");
        assert_eq!(safe_local_name("a:b*c?.txt"), "a_b_c_.txt");
        assert_eq!(safe_local_name("trail. "), "trail");
        assert_eq!(safe_local_name("a\u{202e}b"), "a_b");
        assert!(safe_local_name(&"x".repeat(500)).len() <= 200);
    }
    #[test]
    fn refs() {
        assert_eq!(norm_ref("").unwrap(), "/");
        assert_eq!(norm_ref("//a///b/").unwrap(), "/a/b");
        assert!(norm_ref("/a/../b").is_err());
        assert_eq!(parent_of("/a/b").unwrap(), "/a");
        assert_eq!(parent_of("/a").unwrap(), "/");
        assert_eq!(numbered("x.tar.gz", 2), "x.tar (2).gz");
        assert_eq!(breadcrumbs("/a/b").len(), 3);
    }
    #[test]
    fn listing_anomalies() {
        let mk = |n: &str| RawEntry { name: n.into(), kind: crate::types::EntryKind::File, size: Some(1), mtime_ms: None };
        let (v, a) = sanitize_listing("/", vec![mk("ok"), mk(".."), mk("a/b"), mk("ok"), mk("fine")]);
        assert_eq!(v.len(), 2);
        assert_eq!(a, 3);
    }
}

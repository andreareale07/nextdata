//! Minimal civil-date helpers (no external time crate).
pub fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146097 + doe - 719468
}

pub fn civil_from_days(z: i64) -> (i64, i64, i64) {
    let z = z + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (if m <= 2 { y + 1 } else { y }, m, d)
}

/// "20261114T101500Z" and "20261114"
pub fn amz_datetime(epoch_secs: i64) -> (String, String) {
    let days = epoch_secs.div_euclid(86400);
    let rem = epoch_secs.rem_euclid(86400);
    let (y, m, d) = civil_from_days(days);
    let date = format!("{y:04}{m:02}{d:02}");
    (format!("{date}T{:02}{:02}{:02}Z", rem / 3600, (rem % 3600) / 60, rem % 60), date)
}

/// RFC 3339 / ISO-8601 "2026-11-14T10:15:00.000Z" (also with +hh:mm) -> epoch ms
pub fn parse_rfc3339(s: &str) -> Option<i64> {
    let s = s.trim();
    let b = s.as_bytes();
    if b.len() < 19 {
        return None;
    }
    let n = |a: usize, l: usize| s.get(a..a + l)?.parse::<i64>().ok();
    let (y, mo, d, h, mi, se) = (n(0, 4)?, n(5, 2)?, n(8, 2)?, n(11, 2)?, n(14, 2)?, n(17, 2)?);
    let mut idx = 19;
    let mut ms = 0i64;
    if b.get(idx) == Some(&b'.') {
        let start = idx + 1;
        idx = start;
        while idx < b.len() && b[idx].is_ascii_digit() {
            idx += 1;
        }
        let frac = &s[start..idx];
        let mut f = frac.chars().take(3).collect::<String>();
        while f.len() < 3 {
            f.push('0');
        }
        ms = f.parse().ok()?;
    }
    let mut off = 0i64;
    match b.get(idx) {
        Some(b'+') | Some(b'-') => {
            let sign = if b[idx] == b'+' { 1 } else { -1 };
            let oh: i64 = s.get(idx + 1..idx + 3)?.parse().ok()?;
            let om: i64 = s.get(idx + 4..idx + 6).and_then(|x| x.parse().ok()).unwrap_or(0);
            off = sign * (oh * 3600 + om * 60);
        }
        _ => {}
    }
    Some(((days_from_civil(y, mo, d) * 86400 + h * 3600 + mi * 60 + se - off) * 1000) + ms)
}

/// RFC 1123 "Sat, 14 Nov 2026 10:15:00 GMT" -> epoch ms
pub fn parse_http_date(s: &str) -> Option<i64> {
    let s = s.trim();
    let s = s.split_once(',').map(|x| x.1).unwrap_or(s).trim();
    let mut it = s.split_whitespace();
    let d: i64 = it.next()?.parse().ok()?;
    let mon = match it.next()?.to_ascii_lowercase().get(..3)? {
        "jan" => 1, "feb" => 2, "mar" => 3, "apr" => 4, "may" => 5, "jun" => 6,
        "jul" => 7, "aug" => 8, "sep" => 9, "oct" => 10, "nov" => 11, "dec" => 12,
        _ => return None,
    };
    let y: i64 = it.next()?.parse().ok()?;
    let t = it.next()?;
    let mut tp = t.split(':');
    let (h, mi, se): (i64, i64, i64) =
        (tp.next()?.parse().ok()?, tp.next()?.parse().ok()?, tp.next().unwrap_or("0").parse().ok()?);
    Some((days_from_civil(y, mon, d) * 86400 + h * 3600 + mi * 60 + se) * 1000)
}

pub fn now_secs() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn roundtrip() {
        assert_eq!(amz_datetime(0).0, "19700101T000000Z");
        assert_eq!(amz_datetime(1_700_000_000).0, "20231114T221320Z");
        assert_eq!(parse_rfc3339("2023-11-14T22:13:20.000Z"), Some(1_700_000_000_000));
        assert_eq!(parse_rfc3339("2023-11-14T23:13:20+01:00"), Some(1_700_000_000_000));
        assert_eq!(parse_http_date("Tue, 14 Nov 2023 22:13:20 GMT"), Some(1_700_000_000_000));
        assert_eq!(civil_from_days(days_from_civil(2026, 2, 28) + 1), (2026, 3, 1));
    }
}

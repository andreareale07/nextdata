//! Connection policy (pure functions): baseline that the UI can show but never lower.
use crate::error::{Code, NdError, Result};
use crate::types::Profile;
use std::net::IpAddr;

/// Is this host name/IP on a private/local network? (by literal only - no DNS lookups here)
pub fn is_private_host(host: &str) -> bool {
    let h = host.trim().trim_matches(['[', ']']).to_ascii_lowercase();
    if h.is_empty() {
        return false;
    }
    if let Ok(ip) = h.parse::<IpAddr>() {
        return match ip {
            IpAddr::V4(v) => v.is_private() || v.is_loopback() || v.is_link_local(),
            IpAddr::V6(v) => {
                v.is_loopback() || (v.segments()[0] & 0xfe00) == 0xfc00 || (v.segments()[0] & 0xffc0) == 0xfe80
            }
        };
    }
    h == "localhost" || !h.contains('.') || h.ends_with(".local") || h.ends_with(".lan") || h.ends_with(".home") || h.ends_with(".internal") || h.ends_with(".localhost")
}

/// Is this resolved address private/loopback/link-local? (used for SMB, after DNS resolution)
pub fn is_private_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v) => v.is_private() || v.is_loopback() || v.is_link_local(),
        IpAddr::V6(v) => {
            if let Some(v4) = v.to_ipv4_mapped() {
                return is_private_ip(IpAddr::V4(v4));
            }
            v.is_loopback() || (v.segments()[0] & 0xfe00) == 0xfc00 || (v.segments()[0] & 0xffc0) == 0xfe80
        }
    }
}

/// SMB profile checks that need no network: valid share name, known mode, literal public IPs refused.
/// (Host names are checked on their resolved addresses at connect time.)
pub fn check_smb_profile(p: &Profile) -> Result<()> {
    let share = p.share.trim();
    if share.is_empty() || share.len() > 80 || share.chars().any(|c| matches!(c, '/' | '\\' | '\0') || c.is_control()) {
        return Err(NdError::invalid("share"));
    }
    if !p.smb_mode.is_empty() && p.smb_mode != "strict" && p.smb_mode != "relaxed" {
        return Err(NdError::invalid("smb_mode"));
    }
    let h = p.host.trim().trim_matches(['[', ']']);
    if let Ok(ip) = h.parse::<IpAddr>() {
        if !is_private_ip(ip) {
            return Err(NdError::new(Code::PolicyDenied).with("rule", "smb_public_host").with("host", p.host.clone()));
        }
    }
    Ok(())
}

/// Baseline rules applied before any connection is attempted.
pub fn check_profile(p: &Profile) -> Result<()> {
    match p.protocol.as_str() {
        #[cfg(not(feature = "smb"))]
        "smb" => return Err(NdError::new(Code::ProtocolDisabled).with("protocol", "smb").with("reason_code", "SMB_GATE_NOT_PASSED")),
        #[cfg(feature = "smb")]
        "smb" => return check_smb_profile(p),
        "webdav" | "s3" => {
            // credentials over plain HTTP to a public destination: denied
            if !p.tls && p.auth != "none" && !is_private_host(&p.host) {
                return Err(NdError::new(Code::PolicyDenied).with("rule", "cleartext_credentials_public").with("host", p.host.clone()));
            }
        }
        _ => {}
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn classify() {
        assert!(is_private_host("192.168.1.10"));
        assert!(is_private_host("10.0.0.1"));
        assert!(is_private_host("127.0.0.1"));
        assert!(is_private_host("nas"));
        assert!(is_private_host("openmediavault.local"));
        assert!(is_private_host("::1"));
        assert!(is_private_host("fd12::1"));
        assert!(!is_private_host("8.8.8.8"));
        assert!(!is_private_host("example.com"));
    }
    #[test]
    fn rules() {
        let mut p = Profile { protocol: "webdav".into(), host: "example.com".into(), auth: "password".into(), tls: false, ..Default::default() };
        assert_eq!(check_profile(&p).unwrap_err().code, Code::PolicyDenied);
        p.tls = true;
        assert!(check_profile(&p).is_ok());
        p.protocol = "smb".into();
        #[cfg(not(feature = "smb"))]
        assert_eq!(check_profile(&p).unwrap_err().code, Code::ProtocolDisabled);
        #[cfg(feature = "smb")]
        assert_eq!(check_profile(&p).unwrap_err().code, Code::InvalidArgument); // no share set
    }
}

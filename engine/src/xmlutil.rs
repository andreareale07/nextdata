//! Shared XML helpers (quick-xml): entity resolution restricted to predefined/char references.
use crate::error::{Code, NdError, Result};

pub fn resolve_ref(name: &str) -> Result<char> {
    match name {
        "amp" => Ok('&'),
        "lt" => Ok('<'),
        "gt" => Ok('>'),
        "quot" => Ok('"'),
        "apos" => Ok('\''),
        n if n.starts_with("#x") || n.starts_with("#X") => u32::from_str_radix(&n[2..], 16).ok().and_then(char::from_u32).ok_or_else(|| NdError::new(Code::ServerAnomaly).detail("char ref")),
        n if n.starts_with('#') => n[1..].parse::<u32>().ok().and_then(char::from_u32).ok_or_else(|| NdError::new(Code::ServerAnomaly).detail("char ref")),
        _ => Err(NdError::new(Code::ServerAnomaly).detail("custom entity not allowed")),
    }
}

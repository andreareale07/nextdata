//! Error model: stable codes + typed params; never user-facing text.
use serde::Serialize;
use serde_json::{json, Map, Value};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Code {
    Internal,
    InvalidArgument,
    NotFound,
    AlreadyExists,
    PermissionDenied,
    AuthFailed,
    HostKeyRejected,
    HostKeyChanged,
    TlsUntrusted,
    TlsError,
    Network,
    Timeout,
    Unsupported,
    PolicyDenied,
    Io,
    DiskFull,
    ServerAnomaly,
    LimitExceeded,
    ImportInvalid,
    PassphraseInvalid,
    NotInitialized,
    SessionClosed,
    ProtocolDisabled,
    Cancelled,
}

#[derive(Debug, Clone)]
pub struct NdError {
    pub code: Code,
    pub params: Map<String, Value>,
    pub retryable: bool,
}

pub type Result<T> = std::result::Result<T, NdError>;

impl NdError {
    pub fn new(code: Code) -> Self {
        let retryable = matches!(code, Code::Network | Code::Timeout);
        NdError { code, params: Map::new(), retryable }
    }
    pub fn with(mut self, k: &str, v: impl Into<Value>) -> Self {
        self.params.insert(k.to_string(), v.into());
        self
    }
    pub fn detail(self, d: impl ToString) -> Self {
        self.with("detail", d.to_string())
    }
    pub fn invalid(d: impl ToString) -> Self {
        NdError::new(Code::InvalidArgument).detail(d)
    }
    pub fn internal(d: impl ToString) -> Self {
        NdError::new(Code::Internal).detail(d)
    }
    pub fn to_json(&self) -> Value {
        json!({"code": self.code, "params": self.params, "retryable": self.retryable})
    }
}

impl std::fmt::Display for NdError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{:?} {:?}", self.code, self.params)
    }
}
impl std::error::Error for NdError {}

impl From<std::io::Error> for NdError {
    fn from(e: std::io::Error) -> Self {
        use std::io::ErrorKind::*;
        let code = match e.kind() {
            NotFound => Code::NotFound,
            AlreadyExists => Code::AlreadyExists,
            PermissionDenied => Code::PermissionDenied,
            TimedOut => Code::Timeout,
            ConnectionRefused | ConnectionReset | ConnectionAborted | NotConnected | BrokenPipe
            | UnexpectedEof | AddrNotAvailable => Code::Network,
            StorageFull | QuotaExceeded => Code::DiskFull,
            _ => {
                // Windows ERROR_DISK_FULL(112), ERROR_HANDLE_DISK_FULL(39), Unix ENOSPC(28)
                match e.raw_os_error() {
                    Some(112) | Some(39) | Some(28) => Code::DiskFull,
                    _ => Code::Io,
                }
            }
        };
        NdError::new(code).detail(e)
    }
}

impl From<serde_json::Error> for NdError {
    fn from(e: serde_json::Error) -> Self {
        NdError::invalid(e)
    }
}

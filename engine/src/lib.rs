//! NextData Storage Engine.
pub mod bridge;
pub mod crypto;
pub mod dates;
#[cfg(windows)]
pub mod dragout;
pub mod engine;
pub mod error;
pub mod journal;
pub mod pathguard;
pub mod policy;
pub mod provider;
pub mod replace;
pub mod sample;
pub mod store;
pub mod transfer;
pub mod types;
pub mod xmlutil;

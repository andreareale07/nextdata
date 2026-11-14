//! Persistence layer: one AEAD-encrypted file holding profiles, secrets, pins, favorites, settings.
//! Key = HKDF(PMK, "store"); the PMK itself lives in the OS keystore (adapter).
use crate::crypto;
use crate::error::{Code, NdError, Result};
use crate::types::{Favorite, Pin, Profile, Secrets};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use std::collections::HashMap;
use std::path::PathBuf;

const AAD: &[u8] = b"nextdata-store-v1";

#[derive(Debug, Default, Serialize, Deserialize)]
pub struct StoreData {
    #[serde(default)]
    pub version: u32,
    #[serde(default)]
    pub profiles: Vec<Profile>,
    #[serde(default)]
    pub secrets: HashMap<String, Secrets>,
    #[serde(default)]
    pub pins: HashMap<String, Vec<Pin>>,
    #[serde(default)]
    pub favorites: HashMap<String, Vec<Favorite>>,
    #[serde(default)]
    pub settings: Map<String, Value>,
}

pub struct Store {
    path: PathBuf,
    key: [u8; 32],
    pub data: StoreData,
}

impl Store {
    pub fn open(dir: &str, pmk: &[u8]) -> Result<Store> {
        if pmk.len() < 32 {
            return Err(NdError::invalid("pmk too short"));
        }
        std::fs::create_dir_all(dir)?;
        let path = PathBuf::from(dir).join("store.nd");
        let key = crypto::derive(pmk, "store");
        let data = if path.exists() {
            let blob = std::fs::read(&path)?;
            let plain = crypto::open(&key, AAD, &blob).map_err(|_| NdError::new(Code::PassphraseInvalid).detail("store key mismatch"))?;
            serde_json::from_slice(&plain).map_err(|e| NdError::internal(e))?
        } else {
            StoreData { version: 1, ..Default::default() }
        };
        Ok(Store { path, key, data })
    }

    pub fn save(&self) -> Result<()> {
        let plain = serde_json::to_vec(&self.data).map_err(|e| NdError::internal(e))?;
        let blob = crypto::seal(&self.key, AAD, &plain);
        let tmp = self.path.with_extension("tmp");
        std::fs::write(&tmp, &blob)?;
        // rename replaces atomically on all supported platforms
        std::fs::rename(&tmp, &self.path)?;
        Ok(())
    }

    pub fn profile(&self, id: &str) -> Option<&Profile> {
        self.data.profiles.iter().find(|p| p.id == id)
    }

    pub fn public_profiles(&self) -> Vec<Profile> {
        self.data
            .profiles
            .iter()
            .map(|p| {
                let mut q = p.clone();
                q.has_secret = self.data.secrets.get(&p.id).map(|s| !s.is_empty()).unwrap_or(false);
                q
            })
            .collect()
    }

    pub fn delete_profile(&mut self, id: &str) -> Result<()> {
        self.data.profiles.retain(|p| p.id != id);
        self.data.secrets.remove(id);
        self.data.pins.remove(id);
        self.data.favorites.remove(id);
        self.save()
    }
}

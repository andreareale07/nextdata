//! AEAD + KDF helpers. Keys never leave the engine except the PMK handled by the OS adapter.
use crate::error::{Code, NdError, Result};
use ring::{aead, hkdf, rand::{SecureRandom, SystemRandom}};

struct Len(usize);
impl hkdf::KeyType for Len {
    fn len(&self) -> usize {
        self.0
    }
}

pub fn random(n: usize) -> Vec<u8> {
    let mut v = vec![0u8; n];
    SystemRandom::new().fill(&mut v).expect("os rng");
    v
}

pub fn random_hex(n: usize) -> String {
    random(n).iter().map(|b| format!("{b:02x}")).collect()
}

/// HKDF-SHA256 sub-key for a data class.
pub fn derive(pmk: &[u8], info: &str) -> [u8; 32] {
    let salt = hkdf::Salt::new(hkdf::HKDF_SHA256, b"nextdata/v1");
    let prk = salt.extract(pmk);
    let mut out = [0u8; 32];
    prk.expand(&[info.as_bytes()], Len(32)).expect("hkdf len").fill(&mut out).expect("hkdf fill");
    out
}

/// nonce(12) || ciphertext || tag(16)
pub fn seal(key: &[u8; 32], aad: &[u8], plain: &[u8]) -> Vec<u8> {
    let k = aead::LessSafeKey::new(aead::UnboundKey::new(&aead::CHACHA20_POLY1305, key).expect("key"));
    let nonce_bytes: [u8; 12] = random(12).try_into().expect("12");
    let mut buf = plain.to_vec();
    k.seal_in_place_append_tag(
        aead::Nonce::assume_unique_for_key(nonce_bytes),
        aead::Aad::from(aad),
        &mut buf,
    )
    .expect("seal");
    let mut out = Vec::with_capacity(12 + buf.len());
    out.extend_from_slice(&nonce_bytes);
    out.extend_from_slice(&buf);
    out
}

pub fn open(key: &[u8; 32], aad: &[u8], blob: &[u8]) -> Result<Vec<u8>> {
    if blob.len() < 12 + 16 {
        return Err(NdError::new(Code::ImportInvalid).detail("short"));
    }
    let k = aead::LessSafeKey::new(aead::UnboundKey::new(&aead::CHACHA20_POLY1305, key).expect("key"));
    let (n, ct) = blob.split_at(12);
    let nonce = aead::Nonce::try_assume_unique_for_key(n).map_err(|_| NdError::internal("nonce"))?;
    let mut buf = ct.to_vec();
    let plain = k
        .open_in_place(nonce, aead::Aad::from(aad), &mut buf)
        .map_err(|_| NdError::new(Code::PassphraseInvalid).detail("aead"))?;
    Ok(plain.to_vec())
}

pub const ARGON_MIN_M_KIB: u32 = 19 * 1024;
pub const ARGON_MIN_T: u32 = 2;
pub const ARGON_MAX_M_KIB: u32 = 1024 * 1024;
pub const ARGON_MAX_T: u32 = 16;

pub fn argon_key(passphrase: &str, salt: &[u8], m_kib: u32, t: u32, p: u32) -> Result<[u8; 32]> {
    use argon2::{Algorithm, Argon2, Params, Version};
    let params = Params::new(m_kib, t, p, Some(32)).map_err(|e| NdError::invalid(e))?;
    let a = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);
    let mut out = [0u8; 32];
    a.hash_password_into(passphrase.as_bytes(), salt, &mut out).map_err(|e| NdError::internal(e))?;
    Ok(out)
}

pub fn sha256_hex(data: &[u8]) -> String {
    ring::digest::digest(&ring::digest::SHA256, data).as_ref().iter().map(|b| format!("{b:02x}")).collect()
}

pub fn sha256(data: &[u8]) -> [u8; 32] {
    let mut o = [0u8; 32];
    o.copy_from_slice(ring::digest::digest(&ring::digest::SHA256, data).as_ref());
    o
}

pub fn hmac_sha256(key: &[u8], data: &[u8]) -> [u8; 32] {
    let k = ring::hmac::Key::new(ring::hmac::HMAC_SHA256, key);
    let mut o = [0u8; 32];
    o.copy_from_slice(ring::hmac::sign(&k, data).as_ref());
    o
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn seal_open() {
        let k = derive(b"pmk-pmk-pmk-pmk-pmk-pmk-pmk-pmk-", "store");
        let blob = seal(&k, b"aad", b"hello");
        assert_eq!(open(&k, b"aad", &blob).unwrap(), b"hello");
        assert!(open(&k, b"bad", &blob).is_err());
        let mut t = blob.clone();
        let l = t.len();
        t[l - 1] ^= 1;
        assert!(open(&k, b"aad", &t).is_err());
        assert_ne!(derive(b"x", "a"), derive(b"x", "b"));
    }
    #[test]
    fn hmac_vector() {
        // RFC 4231 test case 2
        let h = hmac_sha256(b"Jefe", b"what do ya want for nothing?");
        assert_eq!(
            h.iter().map(|b| format!("{b:02x}")).collect::<String>(),
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
        );
    }
}

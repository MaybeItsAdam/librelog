//! End-to-end encryption (SPEC.md §12.4).
//!
//! One random 256-bit **journal key** per account. It never leaves a device
//! except as a 24-word recovery phrase the user writes down, or encrypted to a
//! new device during QR pairing. Everything the server stores is sealed under
//! a subkey derived from it, one per purpose, so a payload of one kind can't
//! be passed off as another.
//!
//! Sealed format: `0x01 ‖ 24-byte nonce ‖ XChaCha20-Poly1305 ciphertext`,
//! over a plaintext padded to a size bucket so the server learns little from
//! lengths.

use base64::Engine;
use base64::engine::general_purpose::STANDARD as B64;
use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::Zeroizing;

use crate::error::{CoreError, Result};

const VERSION: u8 = 1;

#[derive(Clone, Copy, Debug)]
pub enum Purpose {
    Ops,
    Snapshot,
    DeviceName,
    KeyCheck,
}

impl Purpose {
    fn context(self) -> &'static str {
        match self {
            Purpose::Ops => "dailylog 2026-10 sync ops",
            Purpose::Snapshot => "dailylog 2026-10 sync snapshot",
            Purpose::DeviceName => "dailylog 2026-10 device name",
            Purpose::KeyCheck => "dailylog 2026-10 key check",
        }
    }
}

/// The plaintext sealed for `key_check`: if it opens, the key is right.
const KEY_CHECK: &[u8] = b"dailylog journal key";

pub struct JournalKey(Zeroizing<[u8; 32]>);

impl JournalKey {
    pub fn generate() -> Result<Self> {
        let mut k = Zeroizing::new([0u8; 32]);
        getrandom::fill(k.as_mut()).map_err(|e| CoreError::Crypto(e.to_string()))?;
        Ok(JournalKey(k))
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self> {
        let k: [u8; 32] = bytes.try_into().map_err(|_| CoreError::Crypto("journal key must be 32 bytes".into()))?;
        Ok(JournalKey(Zeroizing::new(k)))
    }

    pub fn bytes(&self) -> &[u8; 32] {
        &self.0
    }

    /// The 24-word BIP-39 phrase for this key.
    pub fn recovery_phrase(&self) -> String {
        bip39::Mnemonic::from_entropy(self.0.as_ref()).expect("32 bytes is valid entropy").to_string()
    }

    /// Accepts the phrase with any case, spacing or line breaks.
    pub fn from_recovery_phrase(phrase: &str) -> Result<Self> {
        let normalised = phrase.split_whitespace().collect::<Vec<_>>().join(" ").to_lowercase();
        let m = bip39::Mnemonic::parse(normalised).map_err(|e| CoreError::Crypto(format!("recovery phrase: {e}")))?;
        let entropy = Zeroizing::new(m.to_entropy());
        if entropy.len() != 32 {
            return Err(CoreError::Crypto("recovery phrase must be 24 words".into()));
        }
        Self::from_bytes(&entropy)
    }

    fn cipher(&self, purpose: Purpose) -> XChaCha20Poly1305 {
        let sub = Zeroizing::new(blake3::derive_key(purpose.context(), self.0.as_ref()));
        XChaCha20Poly1305::new_from_slice(sub.as_ref()).expect("32-byte key")
    }

    pub fn seal(&self, purpose: Purpose, plaintext: &[u8]) -> Result<Vec<u8>> {
        seal_with(&self.cipher(purpose), purpose.context().as_bytes(), plaintext)
    }

    pub fn open(&self, purpose: Purpose, sealed: &[u8]) -> Result<Vec<u8>> {
        open_with(&self.cipher(purpose), purpose.context().as_bytes(), sealed)
    }

    pub fn seal_b64(&self, purpose: Purpose, plaintext: &[u8]) -> Result<String> {
        Ok(B64.encode(self.seal(purpose, plaintext)?))
    }

    pub fn open_b64(&self, purpose: Purpose, sealed: &str) -> Result<Vec<u8>> {
        let bytes = B64.decode(sealed.trim()).map_err(|e| CoreError::Crypto(format!("base64: {e}")))?;
        self.open(purpose, &bytes)
    }

    pub fn key_check(&self) -> Result<String> {
        self.seal_b64(Purpose::KeyCheck, KEY_CHECK)
    }

    pub fn verifies(&self, key_check: &str) -> bool {
        self.open_b64(Purpose::KeyCheck, key_check).is_ok_and(|p| p == KEY_CHECK)
    }
}

fn seal_with(cipher: &XChaCha20Poly1305, aad: &[u8], plaintext: &[u8]) -> Result<Vec<u8>> {
    let mut nonce = [0u8; 24];
    getrandom::fill(&mut nonce).map_err(|e| CoreError::Crypto(e.to_string()))?;
    let padded = Zeroizing::new(pad(plaintext));
    let ct = cipher
        .encrypt(&XNonce::from(nonce), Payload { msg: &padded, aad })
        .map_err(|_| CoreError::Crypto("encrypt failed".into()))?;
    let mut out = Vec::with_capacity(1 + 24 + ct.len());
    out.push(VERSION);
    out.extend_from_slice(&nonce);
    out.extend_from_slice(&ct);
    Ok(out)
}

fn open_with(cipher: &XChaCha20Poly1305, aad: &[u8], sealed: &[u8]) -> Result<Vec<u8>> {
    if sealed.len() < 1 + 24 + 16 || sealed[0] != VERSION {
        return Err(CoreError::Crypto("not a sealed payload".into()));
    }
    let nonce: [u8; 24] = sealed[1..25].try_into().expect("checked length");
    let padded = Zeroizing::new(
        cipher
            .decrypt(&XNonce::from(nonce), Payload { msg: &sealed[25..], aad })
            .map_err(|_| CoreError::Crypto("wrong key or tampered payload".into()))?,
    );
    unpad(&padded)
}

/// `u32 length ‖ data ‖ zeros` up to a bucket: 256 B, then powers of two up to
/// 64 KiB, then whole 64 KiB steps.
fn pad(data: &[u8]) -> Vec<u8> {
    let need = data.len() + 4;
    let bucket = if need <= 256 {
        256
    } else if need <= 65_536 {
        need.next_power_of_two()
    } else {
        need.div_ceil(65_536) * 65_536
    };
    let mut out = Vec::with_capacity(bucket);
    out.extend_from_slice(&(data.len() as u32).to_le_bytes());
    out.extend_from_slice(data);
    out.resize(bucket, 0);
    out
}

fn unpad(padded: &[u8]) -> Result<Vec<u8>> {
    let len = u32::from_le_bytes(padded.get(..4).and_then(|b| b.try_into().ok()).ok_or_else(bad_padding)?) as usize;
    padded.get(4..4 + len).map(<[u8]>::to_vec).ok_or_else(bad_padding)
}

fn bad_padding() -> CoreError {
    CoreError::Crypto("bad padding".into())
}

// --- Device pairing (SPEC.md §12.4) -----------------------------------------
//
// The new device makes an ephemeral X25519 key pair and shows the public half
// in a QR code. An existing device scans it, makes its own ephemeral pair,
// and seals the journal key to the shared secret. Only the new device can
// open the result, and the server only ever relays ciphertext.

pub struct PairingRequest {
    secret: StaticSecret,
}

impl PairingRequest {
    pub fn new() -> Result<Self> {
        let mut s = Zeroizing::new([0u8; 32]);
        getrandom::fill(s.as_mut()).map_err(|e| CoreError::Crypto(e.to_string()))?;
        Ok(PairingRequest { secret: StaticSecret::from(*s) })
    }

    pub fn public_key(&self) -> [u8; 32] {
        PublicKey::from(&self.secret).to_bytes()
    }

    /// Opens the payload the existing device posted.
    pub fn receive(&self, payload_b64: &str) -> Result<JournalKey> {
        let bytes = B64.decode(payload_b64.trim()).map_err(|e| CoreError::Crypto(format!("base64: {e}")))?;
        if bytes.len() < 32 {
            return Err(CoreError::Crypto("pairing payload too short".into()));
        }
        let their: [u8; 32] = bytes[..32].try_into().expect("checked length");
        let cipher = pairing_cipher(&self.secret, &PublicKey::from(their), &self.public_key(), &their);
        let key = Zeroizing::new(open_with(&cipher, PAIRING_CONTEXT.as_bytes(), &bytes[32..])?);
        JournalKey::from_bytes(&key)
    }
}

const PAIRING_CONTEXT: &str = "dailylog 2026-10 device pairing";

/// Run on the existing device: seals `key` for the device that showed
/// `new_device_public`. Returns the payload for `pairing_fulfil`.
pub fn seal_for_pairing(key: &JournalKey, new_device_public: &[u8]) -> Result<String> {
    let new_pub: [u8; 32] =
        new_device_public.try_into().map_err(|_| CoreError::Crypto("pairing key must be 32 bytes".into()))?;
    let mine = PairingRequest::new()?;
    let my_pub = mine.public_key();
    let cipher = pairing_cipher(&mine.secret, &PublicKey::from(new_pub), &new_pub, &my_pub);
    let sealed = seal_with(&cipher, PAIRING_CONTEXT.as_bytes(), key.bytes())?;
    let mut out = my_pub.to_vec();
    out.extend_from_slice(&sealed);
    Ok(B64.encode(out))
}

/// Both public keys go into the derivation in a fixed order (new device's
/// first), so both sides derive the same key and it's bound to this exchange.
fn pairing_cipher(secret: &StaticSecret, their: &PublicKey, new_pub: &[u8; 32], existing_pub: &[u8; 32]) -> XChaCha20Poly1305 {
    let shared = secret.diffie_hellman(their);
    let mut material = Zeroizing::new(Vec::with_capacity(96));
    material.extend_from_slice(shared.as_bytes());
    material.extend_from_slice(new_pub);
    material.extend_from_slice(existing_pub);
    let k = Zeroizing::new(blake3::derive_key(PAIRING_CONTEXT, &material));
    XChaCha20Poly1305::new_from_slice(k.as_ref()).expect("32-byte key")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn seal_and_open_round_trip() {
        let k = JournalKey::generate().unwrap();
        let sealed = k.seal_b64(Purpose::Ops, b"hello").unwrap();
        assert_eq!(k.open_b64(Purpose::Ops, &sealed).unwrap(), b"hello");
    }

    #[test]
    fn a_payload_sealed_for_one_purpose_wont_open_as_another() {
        let k = JournalKey::generate().unwrap();
        let sealed = k.seal(Purpose::Ops, b"x").unwrap();
        assert!(k.open(Purpose::Snapshot, &sealed).is_err());
    }

    #[test]
    fn the_wrong_key_and_tampering_are_rejected() {
        let (a, b) = (JournalKey::generate().unwrap(), JournalKey::generate().unwrap());
        let mut sealed = a.seal(Purpose::Ops, b"secret").unwrap();
        assert!(b.open(Purpose::Ops, &sealed).is_err());
        let last = sealed.len() - 1;
        sealed[last] ^= 1;
        assert!(a.open(Purpose::Ops, &sealed).is_err());
    }

    #[test]
    fn padding_hides_small_differences_in_length() {
        let k = JournalKey::generate().unwrap();
        let short = k.seal(Purpose::Ops, b"a").unwrap();
        let longer = k.seal(Purpose::Ops, &[b'a'; 200]).unwrap();
        assert_eq!(short.len(), longer.len());
        assert_eq!(pad(&[0; 70_000]).len(), 131_072);
        assert_eq!(unpad(&pad(b"xyz")).unwrap(), b"xyz");
    }

    #[test]
    fn recovery_phrase_is_24_words_and_restores_the_key() {
        let k = JournalKey::generate().unwrap();
        let phrase = k.recovery_phrase();
        assert_eq!(phrase.split(' ').count(), 24);
        let messy = format!("  {}\n", phrase.to_uppercase().replace(' ', "   "));
        assert_eq!(JournalKey::from_recovery_phrase(&messy).unwrap().bytes(), k.bytes());
        assert!(JournalKey::from_recovery_phrase("abandon abandon").is_err());
    }

    #[test]
    fn key_check_verifies_only_the_right_key() {
        let (a, b) = (JournalKey::generate().unwrap(), JournalKey::generate().unwrap());
        let check = a.key_check().unwrap();
        assert!(a.verifies(&check));
        assert!(!b.verifies(&check));
    }

    #[test]
    fn pairing_moves_the_key_to_the_new_device() {
        let key = JournalKey::generate().unwrap();
        let new_device = PairingRequest::new().unwrap();
        let payload = seal_for_pairing(&key, &new_device.public_key()).unwrap();
        assert_eq!(new_device.receive(&payload).unwrap().bytes(), key.bytes());

        let eavesdropper = PairingRequest::new().unwrap();
        assert!(eavesdropper.receive(&payload).is_err());
    }
}

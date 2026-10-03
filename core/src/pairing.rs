//! Adding a device by QR code (SPEC.md §12.4), exposed to the apps.
//!
//! New device: `PairingSession::new()`, call `pairing_open(id)` on the server,
//! show `qr_payload()`, poll `pairing_take(id)` until it returns a payload,
//! then `receive(payload)` gives the journal key to store in the keychain.
//!
//! Existing device: scan the QR, `Store::seal_key_for_pairing(qr)`, post the
//! result with `pairing_fulfil(id, payload)`.

use std::sync::Arc;

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD as B64URL;

use crate::crypto::PairingRequest;
use crate::error::{CoreError, Result};

/// QR contents: `librelog-pair:1:<pairing id>:<base64url public key>`.
const QR_PREFIX: &str = "librelog-pair:1:";

#[derive(uniffi::Object)]
pub struct PairingSession {
    id: String,
    request: PairingRequest,
}

#[uniffi::export]
impl PairingSession {
    #[uniffi::constructor]
    pub fn new() -> Result<Arc<Self>> {
        Ok(Arc::new(PairingSession { id: uuid::Uuid::new_v4().to_string(), request: PairingRequest::new()? }))
    }

    /// The id to pass to `pairing_open` and `pairing_take`.
    pub fn id(&self) -> String {
        self.id.clone()
    }

    /// The string to encode in the QR code.
    pub fn qr_payload(&self) -> String {
        format!("{QR_PREFIX}{}:{}", self.id, B64URL.encode(self.request.public_key()))
    }

    /// Opens what the existing device posted; returns the 32-byte journal key.
    pub fn receive(&self, payload: String) -> Result<Vec<u8>> {
        Ok(self.request.receive(&payload)?.bytes().to_vec())
    }
}

/// A scanned QR code, parsed.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PairingCode {
    pub id: String,
    pub public_key: Vec<u8>,
}

#[uniffi::export]
pub fn parse_pairing_qr(qr: String) -> Result<PairingCode> {
    let rest = qr.trim().strip_prefix(QR_PREFIX).ok_or_else(not_ours)?;
    let (id, key) = rest.split_once(':').ok_or_else(not_ours)?;
    uuid::Uuid::parse_str(id).map_err(|_| not_ours())?;
    let public_key = B64URL.decode(key).map_err(|_| not_ours())?;
    if public_key.len() != 32 {
        return Err(not_ours());
    }
    Ok(PairingCode { id: id.to_string(), public_key })
}

fn not_ours() -> CoreError {
    CoreError::Invalid("not a Librelog pairing code".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn qr_round_trips() {
        let s = PairingSession::new().unwrap();
        let code = parse_pairing_qr(s.qr_payload()).unwrap();
        assert_eq!(code.id, s.id());
        assert_eq!(code.public_key, s.request.public_key().to_vec());
        assert!(parse_pairing_qr("https://example.com".into()).is_err());
    }
}

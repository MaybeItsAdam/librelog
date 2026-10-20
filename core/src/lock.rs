//! A locked note on a journal day (docs/locked-notes.md).
//!
//! Each day can hold one note that opens only with the lock password. The
//! note is sealed under a key derived from that password (Argon2id, see
//! [`LockKey`]), so it is unreadable in the database, in backups and in sync
//! without it; the password itself is never stored. Every read takes the
//! password again, so nothing stays unlocked between views.
//!
//! The password's salt and a check value live in synced settings, so every
//! device uses the same password. Turning the feature off only hides locked
//! notes; their sealed text stays until the user resets the lock.

use rusqlite::OptionalExtension;
use serde_json::json;
use zeroize::Zeroizing;

use base64::Engine;
use base64::engine::general_purpose::STANDARD as B64;

use crate::crypto::LockKey;
use crate::error::{CoreError, Result};
use crate::store::{DEFAULT_JOURNAL, Store, name_id, now_rfc3339};

const ENABLED: &str = "lock.enabled";
const SALT: &str = "lock.salt";
const CHECK: &str = "lock.check";
const MIN_PASSWORD: usize = 4;

/// Whether locked notes are on, and whether a password has been set.
#[derive(Debug, Clone, PartialEq, uniffi::Record, serde::Serialize, serde::Deserialize)]
pub struct LockStatus {
    /// Locked notes show on each day. Off hides them without deleting them.
    pub enabled: bool,
    /// A lock password exists (on this account, if syncing).
    pub has_password: bool,
}

#[uniffi::export]
impl Store {
    pub fn lock_status(&self) -> Result<LockStatus> {
        Ok(LockStatus {
            enabled: self.setting(ENABLED.into())?.as_deref() == Some("true"),
            has_password: self.setting(CHECK.into())?.is_some(),
        })
    }

    /// Sets the lock password, or changes it (`current` is the old one).
    /// Changing it re-seals every locked note under the new password. Also
    /// turns locked notes on.
    pub fn set_lock_password(&self, current: Option<String>, new_password: String) -> Result<()> {
        if new_password.chars().count() < MIN_PASSWORD {
            return Err(CoreError::Invalid(format!("the lock password needs at least {MIN_PASSWORD} characters")));
        }
        let old = if self.lock_status()?.has_password {
            Some(self.lock_key(current.as_deref().unwrap_or(""))?)
        } else {
            None
        };

        let salt = LockKey::new_salt()?;
        let key = LockKey::derive(&new_password, &salt)?;
        let mut writes = Vec::new();
        if let Some(old) = old {
            for (id, sealed) in self.sealed_notes()? {
                let text = Zeroizing::new(old.open_b64(&sealed)?);
                writes.push(("locked_notes", id, json!({ "sealed": key.seal_b64(&text)? })));
            }
        }
        for (k, v) in [(SALT, B64.encode(salt)), (CHECK, key.check()?), (ENABLED, "true".into())] {
            writes.push(("synced_settings", k.to_string(), json!({ "value": v, "deleted_at": null })));
        }
        self.write_all(writes)
    }

    /// Shows or hides locked notes. Turning them on needs a password set.
    pub fn set_lock_enabled(&self, enabled: bool) -> Result<()> {
        if enabled && !self.lock_status()?.has_password {
            return Err(CoreError::Invalid("set a lock password first".into()));
        }
        self.set_setting(ENABLED.into(), enabled.then(|| "true".into()))
    }

    /// For a forgotten password: deletes every locked note and the password,
    /// and turns locked notes off. Nothing else in the journal changes.
    pub fn reset_lock(&self) -> Result<()> {
        let now = now_rfc3339();
        let mut writes: Vec<_> = self
            .sealed_notes()?
            .into_iter()
            .map(|(id, _)| ("locked_notes", id, json!({ "deleted_at": now })))
            .collect();
        for k in [SALT, CHECK, ENABLED] {
            writes.push(("synced_settings", k.to_string(), json!({ "value": null, "deleted_at": null })));
        }
        self.write_all(writes)
    }

    /// Whether the password is right, without opening anything.
    pub fn check_lock_password(&self, password: String) -> Result<bool> {
        match self.lock_key(&password) {
            Ok(_) => Ok(true),
            Err(CoreError::Crypto(_)) => Ok(false),
            Err(e) => Err(e),
        }
    }

    /// Whether the day has a locked note, for showing its tile.
    pub fn has_locked_note(&self, journal_id: Option<String>, date: String) -> Result<bool> {
        let id = note_id(journal_id, &date)?;
        let g = self.lock();
        Ok(g.conn
            .query_row("select 1 from locked_notes where id = ?1 and deleted_at is null", [id], |_| Ok(()))
            .optional()?
            .is_some())
    }

    /// The day's locked note, opened with `password`; none if the day has
    /// none. A wrong password is a crypto error ("wrong password").
    pub fn locked_note(&self, journal_id: Option<String>, date: String, password: String) -> Result<Option<String>> {
        let key = self.lock_key(&password)?;
        let id = note_id(journal_id, &date)?;
        let sealed: Option<String> = {
            let g = self.lock();
            g.conn
                .query_row("select sealed from locked_notes where id = ?1 and deleted_at is null", [id], |r| r.get(0))
                .optional()?
        };
        let Some(sealed) = sealed else { return Ok(None) };
        let text = key.open_b64(&sealed).map_err(|_| {
            CoreError::Crypto("this note was locked with an earlier password; enter that one".into())
        })?;
        String::from_utf8(text).map(Some).map_err(|e| CoreError::Crypto(e.to_string()))
    }

    /// Saves the day's locked note under `password`. Empty text removes it.
    pub fn set_locked_note(&self, journal_id: Option<String>, date: String, password: String, text: String) -> Result<()> {
        let key = self.lock_key(&password)?;
        let journal = journal_id.clone().unwrap_or_else(|| DEFAULT_JOURNAL.to_string());
        let id = note_id(journal_id, &date)?;
        if text.trim().is_empty() {
            return self.write("locked_notes", &id, json!({ "deleted_at": now_rfc3339() }));
        }
        self.write(
            "locked_notes",
            &id,
            json!({ "journal_id": journal, "date": date, "sealed": key.seal_b64(text.as_bytes())?, "deleted_at": null }),
        )
    }
}

impl Store {
    /// The lock key for `password`, or "wrong password".
    fn lock_key(&self, password: &str) -> Result<LockKey> {
        let (Some(salt), Some(check)) = (self.setting(SALT.into())?, self.setting(CHECK.into())?) else {
            return Err(CoreError::Invalid("no lock password is set".into()));
        };
        let salt = B64.decode(salt).map_err(|e| CoreError::Crypto(format!("lock salt: {e}")))?;
        let key = LockKey::derive(password, &salt)?;
        if key.verifies(&check) { Ok(key) } else { Err(CoreError::Crypto("wrong password".into())) }
    }

    fn sealed_notes(&self) -> Result<Vec<(String, String)>> {
        let g = self.lock();
        let mut stmt = g.conn.prepare("select id, sealed from locked_notes where deleted_at is null and sealed is not null")?;
        let rows = stmt.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?;
        Ok(rows.collect::<std::result::Result<_, _>>()?)
    }
}

fn note_id(journal_id: Option<String>, date: &str) -> Result<String> {
    let fmt = time::macros::format_description!("[year]-[month]-[day]");
    time::Date::parse(date, &fmt).map_err(|_| CoreError::Invalid(format!("date {date}: want YYYY-MM-DD")))?;
    let journal = journal_id.unwrap_or_else(|| DEFAULT_JOURNAL.to_string());
    Ok(name_id("locked", &format!("{journal}:{date}")))
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use super::*;
    use crate::model::IncomingOps;

    fn store() -> Arc<Store> {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("j.sqlite").to_str().unwrap().to_string();
        std::mem::forget(dir);
        Store::open(path).unwrap()
    }

    const DAY: &str = "2026-10-12";

    #[test]
    fn a_note_opens_only_with_the_password() {
        let s = store();
        assert_eq!(s.lock_status().unwrap(), LockStatus { enabled: false, has_password: false });
        assert!(s.set_locked_note(None, DAY.into(), "x".into(), "secret".into()).is_err());
        assert!(s.set_lock_password(None, "abc".into()).is_err(), "too short");

        s.set_lock_password(None, "hunter22".into()).unwrap();
        assert_eq!(s.lock_status().unwrap(), LockStatus { enabled: true, has_password: true });
        assert!(!s.has_locked_note(None, DAY.into()).unwrap());
        s.set_locked_note(None, DAY.into(), "hunter22".into(), "The thing I can't say".into()).unwrap();
        assert!(s.has_locked_note(None, DAY.into()).unwrap());

        assert_eq!(
            s.locked_note(None, DAY.into(), "hunter22".into()).unwrap().as_deref(),
            Some("The thing I can't say")
        );
        assert!(matches!(s.locked_note(None, DAY.into(), "nope".into()), Err(CoreError::Crypto(_))));
        assert!(!s.check_lock_password("nope".into()).unwrap());
        assert_eq!(s.locked_note(None, "2026-10-13".into(), "hunter22".into()).unwrap(), None);

        // Nothing readable sits in the database.
        let g = s.lock();
        let raw: String = g.conn.query_row("select sealed from locked_notes", [], |r| r.get(0)).unwrap();
        assert!(!raw.contains("thing"));
        let fts: i64 = g
            .conn
            .query_row("select count(*) from entries_fts where entries_fts match 'thing'", [], |r| r.get(0))
            .unwrap();
        assert_eq!(fts, 0);
    }

    #[test]
    fn changing_the_password_reseals_notes() {
        let s = store();
        s.set_lock_password(None, "first-pass".into()).unwrap();
        s.set_locked_note(None, DAY.into(), "first-pass".into(), "kept".into()).unwrap();
        assert!(s.set_lock_password(Some("wrong".into()), "second-pass".into()).is_err());
        s.set_lock_password(Some("first-pass".into()), "second-pass".into()).unwrap();
        assert!(s.locked_note(None, DAY.into(), "first-pass".into()).is_err());
        assert_eq!(s.locked_note(None, DAY.into(), "second-pass".into()).unwrap().as_deref(), Some("kept"));
    }

    #[test]
    fn empty_text_removes_the_note_and_reset_forgets_everything() {
        let s = store();
        s.set_lock_password(None, "pass-word".into()).unwrap();
        s.set_locked_note(None, DAY.into(), "pass-word".into(), "one".into()).unwrap();
        s.set_locked_note(None, DAY.into(), "pass-word".into(), "  ".into()).unwrap();
        assert!(!s.has_locked_note(None, DAY.into()).unwrap());

        s.set_locked_note(None, DAY.into(), "pass-word".into(), "two".into()).unwrap();
        s.set_lock_enabled(false).unwrap();
        assert!(!s.lock_status().unwrap().enabled);
        assert!(s.has_locked_note(None, DAY.into()).unwrap(), "turning off only hides");

        s.reset_lock().unwrap();
        assert_eq!(s.lock_status().unwrap(), LockStatus { enabled: false, has_password: false });
        assert!(!s.has_locked_note(None, DAY.into()).unwrap());
        assert!(s.set_lock_enabled(true).is_err());
    }

    #[test]
    fn another_device_opens_the_note_with_the_same_password() {
        let a = store();
        let b = store();
        let key = a.generate_journal_key().unwrap();
        a.set_journal_key(key.clone()).unwrap();
        b.set_journal_key(key).unwrap();

        a.set_lock_password(None, "shared-pass".into()).unwrap();
        a.set_locked_note(None, DAY.into(), "shared-pass".into(), "travels sealed".into()).unwrap();
        let batch = a.outgoing_batch(500).unwrap().unwrap();
        b.apply_incoming(vec![IncomingOps { seq: 1, device_id: a.device_id(), payload: batch.payload }]).unwrap();

        assert!(b.lock_status().unwrap().enabled);
        assert_eq!(b.locked_note(None, DAY.into(), "shared-pass".into()).unwrap().as_deref(), Some("travels sealed"));
    }
}

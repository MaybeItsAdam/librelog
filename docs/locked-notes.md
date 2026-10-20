# Locked notes

Any day can hold one locked note: a few private lines that open only with
the lock password, every time. It sits on the day alongside its photos and
calendar events, as a single tile reading "Locked".

## Turning it on and off

Settings → Privacy → **Locked notes**. The first time, the user sets a lock
password (at least four characters, entered twice). From then on every day
shows a "Locked note" tile: "Add a locked note" on a day without one,
"Locked" on a day with one.

Turning locked notes off hides the tiles everywhere. It deletes nothing:
turning them back on shows the same notes, under the same password.

The password follows the account: it's checked against a value in synced
settings, so every device uses the same one. Without sync it's per device.

## Opening a note

Tapping the tile always asks for the password. There is no "remember",
no biometric shortcut and no time-out window: the note shows only while
its sheet is open, and closing the sheet forgets the text. Wrong password →
"That's not the lock password" under the field, and the field clears.

Opening shows the note in a plain editor; changes save when the sheet
closes (`Store::set_locked_note`, with the password used to open it).
Clearing all the text removes the note.

## Changing or forgetting the password

- **Change password:** current password, then the new one twice. Every
  locked note is re-sealed under the new password in one step.
- **Forgot password:** there is no recovery; that is the point. "Reset
  locked notes" deletes every locked note and the password, after a
  confirmation that says so. The rest of the journal is untouched.

## How it's kept

`core/src/lock.rs`. The note is sealed with XChaCha20-Poly1305 under a key
derived from the password with Argon2id (19 MiB, 2 passes, a random 16-byte
salt in synced settings). The password itself is never stored, so a locked
note is unreadable in the database, in backups, in exports and in sync
without it. Sync carries only the sealed text, inside the usual encrypted op
log, so the server sees it doubly sealed.

Exports leave locked notes out.

## Core calls

| Call | Use |
| --- | --- |
| `lock_status()` | `enabled`, `has_password`: what Settings and the day show |
| `set_lock_password(current, new)` | first set (current none), or change |
| `set_lock_enabled(bool)` | the Settings switch; on needs a password |
| `reset_lock()` | forgot password: deletes every locked note |
| `check_lock_password(password)` | verify without opening |
| `has_locked_note(journal, date)` | which tile text to show |
| `locked_note(journal, date, password)` | open; wrong password is a crypto error |
| `set_locked_note(journal, date, password, text)` | save; empty text removes |

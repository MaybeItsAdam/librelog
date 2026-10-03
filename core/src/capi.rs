//! A plain C ABI over the core, for the Windows app (SPEC.md §3.1).
//!
//! The UniFFI C# generator trails uniffi-rs, so instead of generated bindings
//! Windows gets four functions and JSON. Every call takes a method name and a
//! JSON object of arguments and returns a JSON envelope:
//!
//! ```text
//! {"ok": <result>}
//! {"err": {"kind": "database" | "not_found" | "invalid" | "sync" | "crypto" | "panic", "message": "…"}}
//! ```
//!
//! Records serialise with their Rust field names (snake_case). Byte strings
//! (journal keys, pairing public keys) travel as standard base64.
//!
//! Strings returned by this module are owned by Rust and must be released
//! with `dl_string_free`. Built only with `--features capi`.

use std::ffi::{CStr, CString, c_char};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Arc;

use base64::Engine;
use base64::engine::general_purpose::STANDARD as B64;
use serde::Deserialize;
use serde::de::DeserializeOwned;
use serde_json::{Value, json};

use crate::doc::{self, Block};
use crate::error::CoreError;
use crate::model::*;
use crate::pairing::{PairingSession, parse_pairing_qr};
use crate::store::Store;

type Outcome = std::result::Result<Value, CoreError>;

/// Opens the database at `path`. Returns a JSON envelope whose `ok` is the
/// store handle as a number; pass it to `dl_store_call` and `dl_store_close`.
///
/// # Safety
/// `path` must be a valid NUL-terminated UTF-8 string.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dl_store_open(path: *const c_char) -> *mut c_char {
    respond(|| {
        let path = unsafe { read_str(path) }?;
        let store = Store::open(path)?;
        Ok(json!(Arc::into_raw(store) as usize))
    })
}

/// Releases a store handle from `dl_store_open`.
///
/// # Safety
/// `handle` must come from `dl_store_open` and not be used afterwards.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dl_store_close(handle: usize) {
    if handle != 0 {
        drop(unsafe { Arc::from_raw(handle as *const Store) });
    }
}

/// Calls a `Store` method (see `store_call` for the names and arguments).
///
/// # Safety
/// `handle` must be live; `method` and `args` must be NUL-terminated UTF-8.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dl_store_call(handle: usize, method: *const c_char, args: *const c_char) -> *mut c_char {
    respond(|| {
        if handle == 0 {
            return Err(CoreError::Invalid("null store handle".into()));
        }
        // Borrow without taking ownership: bump, then rebuild and drop the
        // temporary Arc so the count is unchanged.
        let store = unsafe {
            Arc::increment_strong_count(handle as *const Store);
            Arc::from_raw(handle as *const Store)
        };
        let method = unsafe { read_str(method) }?;
        let args: Value = serde_json::from_str(&unsafe { read_str(args) }?)?;
        store_call(&store, &method, args)
    })
}

/// Calls a function that needs no store: `markdown_shortcut`,
/// `blocks_to_markdown`, `blocks_word_count`, `parse_pairing_qr`,
/// `pairing_new`, and `pairing_qr` / `pairing_id` / `pairing_receive` /
/// `pairing_close` on a pairing handle.
///
/// # Safety
/// `method` and `args` must be NUL-terminated UTF-8.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dl_call(method: *const c_char, args: *const c_char) -> *mut c_char {
    respond(|| {
        let method = unsafe { read_str(method) }?;
        let args: Value = serde_json::from_str(&unsafe { read_str(args) }?)?;
        free_call(&method, args)
    })
}

/// Frees a string returned by any `dl_*` function.
///
/// # Safety
/// `s` must come from this module and not be freed twice.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dl_string_free(s: *mut c_char) {
    if !s.is_null() {
        drop(unsafe { CString::from_raw(s) });
    }
}

fn store_call(s: &Store, method: &str, a: Value) -> Outcome {
    macro_rules! args {
        ($($name:ident : $ty:ty),* $(,)?) => {{
            #[derive(Deserialize)]
            struct A { $($name: $ty),* }
            let A { $($name),* } = from::<A>(a)?;
            ($($name),*)
        }};
    }
    let ok = |v: Value| Ok(v);
    match method {
        "device_id" => ok(json!(s.device_id())),
        "journals" => ok(json!(s.journals()?)),
        "create_journal" => {
            let (name, color) = args!(name: String, color: Option<String>);
            ok(json!(s.create_journal(name, color)?))
        }
        "default_journal_id" => ok(json!(s.default_journal_id())),
        "create_entry" => {
            let (journal_id, date, time) = args!(journal_id: Option<String>, date: String, time: Option<String>);
            ok(json!(s.create_entry(journal_id, date, time)?))
        }
        "entry" => {
            let id = args!(id: String);
            ok(json!(s.entry(id)?))
        }
        "save_body" => {
            let (id, body) = args!(id: String, body: Vec<Block>);
            ok(json!(s.save_body(id, body)?))
        }
        "set_title" => {
            let (id, title) = args!(id: String, title: Option<String>);
            ok(json!(s.set_title(id, title)?))
        }
        "set_rating" => {
            let (id, rating) = args!(id: String, rating: Option<f64>);
            ok(json!(s.set_rating(id, rating)?))
        }
        "set_favorite" => {
            let (id, favorite) = args!(id: String, favorite: bool);
            ok(json!(s.set_favorite(id, favorite)?))
        }
        "set_date_time" => {
            let (id, date, time) = args!(id: String, date: String, time: Option<String>);
            ok(json!(s.set_date_time(id, date, time)?))
        }
        "move_entry" => {
            let (id, journal_id) = args!(id: String, journal_id: String);
            ok(json!(s.move_entry(id, journal_id)?))
        }
        "delete_entry" => {
            let id = args!(id: String);
            ok(json!(s.delete_entry(id)?))
        }
        "restore_entry" => {
            let id = args!(id: String);
            ok(json!(s.restore_entry(id)?))
        }
        "export" => {
            let (format, path, journal_id) = args!(format: crate::transfer::ExportFormat, path: String, journal_id: Option<String>);
            ok(json!(s.export(format, path, journal_id)?))
        }
        "preview_import" => {
            let path = args!(path: String);
            ok(json!(s.preview_import(path)?))
        }
        "import_file" => {
            let (path, journal_id) = args!(path: String, journal_id: Option<String>);
            ok(json!(s.import_file(path, journal_id)?))
        }
        "day" => {
            let (date, journal_id) = args!(date: String, journal_id: Option<String>);
            ok(json!(s.day(date, journal_id)?))
        }
        "timeline" => {
            let (filter, after, limit) = args!(filter: Option<TimelineFilter>, after: Option<TimelineCursor>, limit: u32);
            ok(json!(s.timeline(filter.unwrap_or_default(), after, limit)?))
        }
        "month" => {
            let (year_month, journal_id) = args!(year_month: String, journal_id: Option<String>);
            ok(json!(s.month(year_month, journal_id)?))
        }
        "on_this_day" => {
            let date = args!(date: String);
            ok(json!(s.on_this_day(date)?))
        }
        "search" => {
            let (query, limit) = args!(query: String, limit: u32);
            ok(json!(s.search(query, limit)?))
        }
        "stats" => {
            let today = args!(today: String);
            ok(json!(s.stats(today)?))
        }
        "tags" => ok(json!(s.tags()?)),
        "tag_named" => {
            let name = args!(name: String);
            ok(json!(s.tag_named(name)?))
        }
        "tag_entry" => {
            let (entry_id, tag_id, value) = args!(entry_id: String, tag_id: String, value: Option<String>);
            ok(json!(s.tag_entry(entry_id, tag_id, value)?))
        }
        "untag_entry" => {
            let (entry_id, tag_id) = args!(entry_id: String, tag_id: String);
            ok(json!(s.untag_entry(entry_id, tag_id)?))
        }
        "revisions" => {
            let entry_id = args!(entry_id: String);
            ok(json!(s.revisions(entry_id)?))
        }
        "resolve_conflicts" => {
            let entry_id = args!(entry_id: String);
            ok(json!(s.resolve_conflicts(entry_id)?))
        }
        "generate_journal_key" => ok(json!(B64.encode(s.generate_journal_key()?))),
        "set_journal_key" => {
            let key = args!(key: String);
            ok(json!(s.set_journal_key(bytes(&key)?)?))
        }
        "forget_journal_key" => {
            s.forget_journal_key();
            ok(Value::Null)
        }
        "recovery_phrase" => ok(json!(s.recovery_phrase()?)),
        "key_from_recovery_phrase" => {
            let phrase = args!(phrase: String);
            ok(json!(B64.encode(s.key_from_recovery_phrase(phrase)?)))
        }
        "key_check" => ok(json!(s.key_check()?)),
        "key_matches" => {
            let (key, key_check) = args!(key: String, key_check: String);
            ok(json!(s.key_matches(bytes(&key)?, key_check)?))
        }
        "seal_device_name" => {
            let name = args!(name: String);
            ok(json!(s.seal_device_name(name)?))
        }
        "open_device_name" => {
            let sealed = args!(sealed: String);
            ok(json!(s.open_device_name(sealed)?))
        }
        "seal_key_for_pairing" => {
            let public_key = args!(public_key: String);
            ok(json!(s.seal_key_for_pairing(bytes(&public_key)?)?))
        }
        "pending_count" => ok(json!(s.pending_count()?)),
        "outgoing_batch" => {
            let max_ops = args!(max_ops: u32);
            ok(json!(s.outgoing_batch(max_ops)?))
        }
        "push_succeeded" => {
            let batch_id = args!(batch_id: String);
            ok(json!(s.push_succeeded(batch_id)?))
        }
        "sync_cursor" => ok(json!(s.sync_cursor()?)),
        "apply_incoming" => {
            let rows = args!(rows: Vec<IncomingOps>);
            ok(json!(s.apply_incoming(rows)?))
        }
        "snapshot" => ok(json!(s.snapshot()?)),
        "apply_snapshot" => {
            let (seq, payload) = args!(seq: i64, payload: String);
            ok(json!(s.apply_snapshot(seq, payload)?))
        }
        "reset_sync" => ok(json!(s.reset_sync()?)),
        other => Err(CoreError::Invalid(format!("unknown store method {other}"))),
    }
}

fn free_call(method: &str, a: Value) -> Outcome {
    #[derive(Deserialize)]
    struct Text {
        text: String,
    }
    #[derive(Deserialize)]
    struct Blocks {
        blocks: Vec<Block>,
    }
    #[derive(Deserialize)]
    struct Handle {
        handle: usize,
    }
    #[derive(Deserialize)]
    struct Receive {
        handle: usize,
        payload: String,
    }
    match method {
        "markdown_shortcut" => {
            let Text { text } = from(a)?;
            Ok(match doc::markdown_shortcut(text) {
                Some(s) => json!({ "kind": s.kind, "prefix_len": s.prefix_len }),
                None => Value::Null,
            })
        }
        "blocks_to_markdown" => {
            let Blocks { blocks } = from(a)?;
            Ok(json!(doc::blocks_to_markdown(blocks)))
        }
        "blocks_word_count" => {
            let Blocks { blocks } = from(a)?;
            Ok(json!(doc::blocks_word_count(blocks)))
        }
        "parse_pairing_qr" => {
            let Text { text } = from(a)?;
            let code = parse_pairing_qr(text)?;
            Ok(json!({ "id": code.id, "public_key": B64.encode(code.public_key) }))
        }
        "pairing_new" => Ok(json!(Arc::into_raw(PairingSession::new()?) as usize)),
        "pairing_id" => {
            let Handle { handle } = from(a)?;
            with_pairing(handle, |p| Ok(json!(p.id())))
        }
        "pairing_qr" => {
            let Handle { handle } = from(a)?;
            with_pairing(handle, |p| Ok(json!(p.qr_payload())))
        }
        "pairing_receive" => {
            let Receive { handle, payload } = from(a)?;
            with_pairing(handle, |p| Ok(json!(B64.encode(p.receive(payload)?))))
        }
        "pairing_close" => {
            let Handle { handle } = from(a)?;
            if handle != 0 {
                drop(unsafe { Arc::from_raw(handle as *const PairingSession) });
            }
            Ok(Value::Null)
        }
        other => Err(CoreError::Invalid(format!("unknown function {other}"))),
    }
}

fn with_pairing(handle: usize, f: impl FnOnce(&PairingSession) -> Outcome) -> Outcome {
    if handle == 0 {
        return Err(CoreError::Invalid("null pairing handle".into()));
    }
    let p = unsafe {
        Arc::increment_strong_count(handle as *const PairingSession);
        Arc::from_raw(handle as *const PairingSession)
    };
    f(&p)
}

fn from<T: DeserializeOwned>(v: Value) -> std::result::Result<T, CoreError> {
    // A call with no arguments may pass `null` or `{}`.
    let v = if v.is_null() { json!({}) } else { v };
    serde_json::from_value(v).map_err(|e| CoreError::Invalid(format!("arguments: {e}")))
}

fn bytes(b64: &str) -> std::result::Result<Vec<u8>, CoreError> {
    B64.decode(b64.trim()).map_err(|e| CoreError::Invalid(format!("base64: {e}")))
}

unsafe fn read_str(p: *const c_char) -> std::result::Result<String, CoreError> {
    if p.is_null() {
        return Err(CoreError::Invalid("null string".into()));
    }
    unsafe { CStr::from_ptr(p) }
        .to_str()
        .map(str::to_string)
        .map_err(|_| CoreError::Invalid("string is not UTF-8".into()))
}

fn respond(f: impl FnOnce() -> Outcome) -> *mut c_char {
    let envelope = match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(v)) => json!({ "ok": v }),
        Ok(Err(e)) => json!({ "err": { "kind": kind(&e), "message": e.to_string() } }),
        Err(_) => json!({ "err": { "kind": "panic", "message": "the core panicked" } }),
    };
    // JSON never contains a NUL byte (they're escaped as \u0000).
    CString::new(envelope.to_string()).expect("no interior NUL").into_raw()
}

fn kind(e: &CoreError) -> &'static str {
    match e {
        CoreError::Database(_) => "database",
        CoreError::NotFound(_) => "not_found",
        CoreError::Invalid(_) => "invalid",
        CoreError::Sync(_) => "sync",
        CoreError::Crypto(_) => "crypto",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn call(handle: usize, method: &str, args: Value) -> Value {
        let m = CString::new(method).unwrap();
        let a = CString::new(args.to_string()).unwrap();
        unsafe {
            let p = dl_store_call(handle, m.as_ptr(), a.as_ptr());
            let v: Value = serde_json::from_str(CStr::from_ptr(p).to_str().unwrap()).unwrap();
            dl_string_free(p);
            v
        }
    }

    #[test]
    fn open_write_read_and_errors_through_the_c_abi() {
        let dir = tempfile::tempdir().unwrap();
        let path = CString::new(dir.path().join("j.sqlite").to_str().unwrap()).unwrap();
        let opened: Value = unsafe {
            let p = dl_store_open(path.as_ptr());
            let v = serde_json::from_str(CStr::from_ptr(p).to_str().unwrap()).unwrap();
            dl_string_free(p);
            v
        };
        let h = opened["ok"].as_u64().unwrap() as usize;

        let e = call(h, "create_entry", json!({"date": "2026-10-03", "time": "08:00"}));
        let id = e["ok"]["id"].as_str().unwrap().to_string();
        let body = json!([{"kind": {"t": "paragraph"}, "text": "hello world"}]);
        assert_eq!(call(h, "save_body", json!({"id": id, "body": body}))["ok"], 2);
        assert_eq!(call(h, "entry", json!({"id": id}))["ok"]["body"][0]["text"], "hello world");
        assert_eq!(call(h, "search", json!({"query": "hel", "limit": 5}))["ok"][0]["entry"]["id"], id.as_str());

        let key = call(h, "generate_journal_key", Value::Null)["ok"].as_str().unwrap().to_string();
        assert_eq!(call(h, "set_journal_key", json!({"key": key}))["ok"], Value::Null);
        assert!(call(h, "outgoing_batch", json!({"max_ops": 10}))["ok"]["payload"].is_string());

        assert_eq!(call(h, "entry", json!({"id": "nope"}))["err"]["kind"], "not_found");
        assert_eq!(call(h, "no_such_method", json!({}))["err"]["kind"], "invalid");
        assert_eq!(call(h, "entry", json!({}))["err"]["kind"], "invalid");
        unsafe { dl_store_close(h) };
    }
}

//! Recording local changes and merging remote ones (SPEC.md §12.5).
//!
//! Every write to a synced row goes through [`write`], which stamps each
//! changed field with a hybrid-logical-clock value, remembers that stamp in
//! `field_clocks`, and queues an [`Op`] for the next push. [`apply`] merges an
//! op from another device field by field: the higher stamp wins.
//!
//! Entry bodies are the one place last-writer-wins would silently lose
//! writing, so they carry the stamp they were edited *from* (`base`). When a
//! remote body and the local body were edited concurrently, the losing
//! version is kept as a conflict revision instead of disappearing.

use std::collections::BTreeMap;

use rusqlite::{OptionalExtension, Transaction, params, types::Value as Sql};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::db::synced_fields;
use crate::doc;
use crate::error::{CoreError, Result};
use crate::hlc::{self, Clock};

/// Fields where a concurrent edit keeps the loser as a conflict revision.
const CONFLICT_FIELDS: &[(&str, &str)] = &[("entries", "body")];

fn is_conflict_field(table: &str, field: &str) -> bool {
    CONFLICT_FIELDS.iter().any(|(t, f)| *t == table && *f == field)
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Op {
    /// Table.
    pub t: String,
    /// Row id.
    pub id: String,
    /// Changed fields and their new values.
    pub f: BTreeMap<String, Value>,
    /// HLC stamp shared by every field in this op.
    pub h: String,
    /// Device that made the change.
    pub d: String,
    /// For conflict fields: the stamp the change was based on ("" = none).
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub b: BTreeMap<String, String>,
}

/// Writes fields to a synced row (inserting it if new) and queues the change.
pub fn write(
    tx: &Transaction,
    clock: &mut Clock,
    device: &str,
    table: &str,
    id: &str,
    fields: BTreeMap<String, Value>,
) -> Result<()> {
    let allowed = synced_fields(table).ok_or_else(|| CoreError::Invalid(format!("table {table} doesn't sync")))?;
    if let Some(bad) = fields.keys().find(|k| !allowed.contains(&k.as_str())) {
        return Err(CoreError::Invalid(format!("{table}.{bad} doesn't sync")));
    }
    if fields.is_empty() {
        return Ok(());
    }

    let stamp = clock.tick();
    let mut base = BTreeMap::new();
    for field in fields.keys().filter(|f| is_conflict_field(table, f)) {
        base.insert(field.clone(), field_clock(tx, table, id, field)?.unwrap_or_default());
    }

    put_row(tx, table, id, &fields, &stamp)?;
    for field in fields.keys() {
        set_field_clock(tx, table, id, field, &stamp)?;
    }

    let op = Op { t: table.into(), id: id.into(), f: fields, h: stamp, d: device.into(), b: base };
    tx.execute("insert into pending_ops (op) values (?1)", [serde_json::to_string(&op)?])?;
    Ok(())
}

/// What merging one remote op did, so the UI can refresh and flag conflicts.
#[derive(Debug, Default, PartialEq)]
pub struct Applied {
    pub changed: bool,
    pub conflict_entry: Option<String>,
}

/// Merges one op from another device.
///
/// Ops arrive in server `seq` order. That ordering is what makes the conflict
/// rule sound: if the local field already has a *higher* stamp than an op we
/// are only now seeing, the local edit can't have been made with knowledge of
/// it, so the two were concurrent.
pub fn apply(tx: &Transaction, clock: &mut Clock, my_device: &str, op: &Op) -> Result<Applied> {
    if op.d == my_device {
        return Ok(Applied::default()); // our own change coming back
    }
    clock.observe(&op.h);

    // Unknown tables and fields come from newer app versions; skip them rather
    // than fail, so an old device keeps syncing everything it understands.
    let Some(allowed) = synced_fields(&op.t) else { return Ok(Applied::default()) };

    let mut winners = BTreeMap::new();
    let mut out = Applied::default();
    for (field, value) in op.f.iter().filter(|(f, _)| allowed.contains(&f.as_str())) {
        let local = field_clock(tx, &op.t, &op.id, field)?;
        let remote_wins = local.as_deref().is_none_or(|l| op.h.as_str() > l);

        if is_conflict_field(&op.t, field) {
            let base = op.b.get(field).map(String::as_str).unwrap_or("");
            let concurrent = match &local {
                None => false,
                Some(l) if remote_wins => base != l,
                Some(_) => true, // local is newer yet hasn't seen this op: see doc comment
            };
            if concurrent {
                let current: Option<String> = row_field(tx, &op.t, &op.id, field)?;
                let incoming = value.as_str().map(str::to_string);
                if current != incoming {
                    let (loser_body, loser_stamp) = if remote_wins {
                        (current.unwrap_or_default(), local.clone().unwrap_or_default())
                    } else {
                        (incoming.unwrap_or_default(), op.h.clone())
                    };
                    save_revision(tx, &op.id, &loser_body, &loser_stamp, true)?;
                    out.conflict_entry = Some(op.id.clone());
                }
            }
        }

        if remote_wins {
            winners.insert(field.clone(), value.clone());
        }
    }

    if !winners.is_empty() {
        put_row(tx, &op.t, &op.id, &winners, &op.h)?;
        for field in winners.keys() {
            set_field_clock(tx, &op.t, &op.id, field, &op.h)?;
        }
        out.changed = true;
    }
    Ok(out)
}

pub fn save_revision(tx: &Transaction, entry_id: &str, body: &str, stamp: &str, conflict: bool) -> Result<()> {
    let device = hlc::parse(stamp).map(|(_, d)| d.to_string()).unwrap_or_default();
    tx.execute(
        "insert into revisions (id, entry_id, body, hlc, device_id, is_conflict, created_at)
         values (?1, ?2, ?3, ?4, ?5, ?6, strftime('%Y-%m-%dT%H:%M:%fZ','now'))",
        params![uuid::Uuid::now_v7().to_string(), entry_id, body, stamp, device, conflict],
    )?;
    Ok(())
}

fn field_clock(tx: &Transaction, table: &str, id: &str, field: &str) -> Result<Option<String>> {
    Ok(tx
        .query_row(
            "select hlc from field_clocks where tbl = ?1 and row_id = ?2 and field = ?3",
            params![table, id, field],
            |r| r.get(0),
        )
        .optional()?)
}

fn set_field_clock(tx: &Transaction, table: &str, id: &str, field: &str, stamp: &str) -> Result<()> {
    tx.execute(
        "insert into field_clocks (tbl, row_id, field, hlc) values (?1, ?2, ?3, ?4)
         on conflict (tbl, row_id, field) do update set hlc = excluded.hlc",
        params![table, id, field, stamp],
    )?;
    Ok(())
}

fn row_field(tx: &Transaction, table: &str, id: &str, field: &str) -> Result<Option<String>> {
    // table and field are checked against db::SYNCED before reaching here.
    Ok(tx
        .query_row(&format!("select {field} from {table} where id = ?1"), [id], |r| r.get(0))
        .optional()?
        .flatten())
}

/// Inserts or updates a row with the given fields, then refreshes anything
/// derived from them. `table` and every field name have already been checked
/// against `db::SYNCED`, so interpolating them into SQL is safe.
fn put_row(tx: &Transaction, table: &str, id: &str, fields: &BTreeMap<String, Value>, stamp: &str) -> Result<()> {
    let updated_at = stamp_time(stamp);
    let values: Vec<Sql> = fields.values().map(to_sql).collect();
    let exists = tx
        .query_row(&format!("select 1 from {table} where id = ?1"), [id], |_| Ok(()))
        .optional()?
        .is_some();

    if exists {
        let sets: Vec<String> = fields.keys().enumerate().map(|(i, f)| format!("{f} = ?{}", i + 3)).collect();
        let sql = format!("update {table} set updated_at = ?2, {} where id = ?1", sets.join(", "));
        let mut args: Vec<Sql> = vec![Sql::Text(id.into()), Sql::Text(updated_at)];
        args.extend(values);
        tx.execute(&sql, rusqlite::params_from_iter(args))?;
    } else {
        let cols: Vec<&str> = fields.keys().map(String::as_str).collect();
        let marks: Vec<String> = (0..cols.len()).map(|i| format!("?{}", i + 3)).collect();
        let sql = format!(
            "insert into {table} (id, updated_at, {}) values (?1, ?2, {})",
            cols.join(", "),
            marks.join(", ")
        );
        let mut args: Vec<Sql> = vec![Sql::Text(id.into()), Sql::Text(updated_at)];
        args.extend(values);
        tx.execute(&sql, rusqlite::params_from_iter(args))?;
    }

    if table == "entries" && fields.contains_key("body") {
        let body: String = tx.query_row("select body from entries where id = ?1", [id], |r| r.get(0))?;
        let blocks = doc::parse(&body);
        tx.execute(
            "update entries set body_text = ?2, word_count = ?3 where id = ?1",
            params![id, doc::plain_text(&blocks), doc::word_count(&blocks)],
        )?;
    }
    Ok(())
}

fn to_sql(v: &Value) -> Sql {
    match v {
        Value::Null => Sql::Null,
        Value::Bool(b) => Sql::Integer(*b as i64),
        Value::Number(n) => match n.as_i64() {
            Some(i) => Sql::Integer(i),
            None => Sql::Real(n.as_f64().unwrap_or(0.0)),
        },
        Value::String(s) => Sql::Text(s.clone()),
        other => Sql::Text(other.to_string()),
    }
}

/// `updated_at` is the change's own wall-clock time, so it's the same on
/// every device rather than "when this device happened to receive it".
fn stamp_time(stamp: &str) -> String {
    hlc::parse(stamp)
        .and_then(|(s, _)| time::OffsetDateTime::from_unix_timestamp_nanos(s.millis as i128 * 1_000_000).ok())
        .and_then(|t| t.format(&time::format_description::well_known::Rfc3339).ok())
        .unwrap_or_default()
}

/// The oldest queued local ops, up to `limit`, with the id of the last one.
pub fn pending(tx: &Transaction, limit: u32) -> Result<(Vec<Op>, Option<i64>)> {
    let mut stmt = tx.prepare("select id, op from pending_ops order by id limit ?1")?;
    let rows = stmt.query_map([limit], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?)))?;
    let mut ops = Vec::new();
    let mut last = None;
    for row in rows {
        let (id, json) = row?;
        ops.push(serde_json::from_str(&json)?);
        last = Some(id);
    }
    Ok((ops, last))
}

/// Exactly the queued ops with id <= `last_id`: a batch being retried.
pub fn pending_through(tx: &Transaction, last_id: i64) -> Result<(Vec<Op>, Option<i64>)> {
    let mut stmt = tx.prepare("select id, op from pending_ops where id <= ?1 order by id")?;
    let rows = stmt.query_map([last_id], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?)))?;
    let mut ops = Vec::new();
    let mut last = None;
    for row in rows {
        let (id, json) = row?;
        ops.push(serde_json::from_str(&json)?);
        last = Some(id);
    }
    Ok((ops, last))
}

pub fn pending_count(tx: &rusqlite::Connection) -> Result<u32> {
    Ok(tx.query_row("select count(*) from pending_ops", [], |r| r.get(0))?)
}

pub fn drop_pending_through(tx: &Transaction, last_id: i64) -> Result<()> {
    tx.execute("delete from pending_ops where id <= ?1", [last_id])?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusqlite::Connection;
    use serde_json::json;

    struct Device {
        conn: Connection,
        clock: Clock,
        id: String,
    }

    impl Device {
        fn new(id: &str) -> Self {
            let dir = tempfile::tempdir().unwrap();
            let path = dir.path().join("t.sqlite");
            let conn = crate::db::open(path.to_str().unwrap()).unwrap();
            std::mem::forget(dir); // keep the file for the test's lifetime
            Device { conn, clock: Clock::new(id.into(), None), id: id.into() }
        }

        fn write(&mut self, table: &str, id: &str, f: Value) {
            let fields: BTreeMap<String, Value> = serde_json::from_value(f).unwrap();
            let tx = self.conn.transaction().unwrap();
            write(&tx, &mut self.clock, &self.id, table, id, fields).unwrap();
            tx.commit().unwrap();
        }

        /// Takes everything queued, as a push would.
        fn drain(&mut self) -> Vec<Op> {
            let tx = self.conn.transaction().unwrap();
            let (ops, last) = pending(&tx, 10_000).unwrap();
            if let Some(last) = last {
                drop_pending_through(&tx, last).unwrap();
            }
            tx.commit().unwrap();
            ops
        }

        fn receive(&mut self, ops: &[Op]) -> Vec<Applied> {
            let tx = self.conn.transaction().unwrap();
            let out = ops.iter().map(|op| apply(&tx, &mut self.clock, &self.id, op).unwrap()).collect();
            tx.commit().unwrap();
            out
        }

        fn get(&self, sql: &str) -> String {
            self.conn.query_row(sql, [], |r| r.get::<_, Option<String>>(0)).unwrap().unwrap_or_default()
        }

        fn conflicts(&self) -> i64 {
            self.conn.query_row("select count(*) from revisions where is_conflict = 1", [], |r| r.get(0)).unwrap()
        }
    }

    fn entry(body: &str) -> Value {
        json!({
            "journal_id": "j", "date": "2026-10-03", "created_at": "2026-10-03T09:00:00Z",
            "body": doc::to_json(&[doc::Block { kind: doc::BlockKind::Paragraph, text: body.into(), marks: vec![], indent: 0 }]),
        })
    }

    fn body(text: &str) -> Value {
        json!({ "body": doc::to_json(&[doc::Block { kind: doc::BlockKind::Paragraph, text: text.into(), marks: vec![], indent: 0 }]) })
    }

    #[test]
    fn refuses_fields_that_dont_sync() {
        let mut a = Device::new("a");
        let tx = a.conn.transaction().unwrap();
        let err = write(&tx, &mut a.clock, "a", "attachments", "x", serde_json::from_value(json!({"local_path": "/tmp"})).unwrap());
        assert!(matches!(err, Err(CoreError::Invalid(_))));
    }

    #[test]
    fn a_new_row_reaches_another_device_with_derived_columns() {
        let (mut a, mut b) = (Device::new("a"), Device::new("b"));
        a.write("entries", "e1", entry("ran five kilometres today"));
        let ops = a.drain();
        b.receive(&ops);
        assert_eq!(b.get("select body_text from entries where id = 'e1'"), "ran five kilometres today");
        assert_eq!(b.get("select cast(word_count as text) from entries where id = 'e1'"), "4");
        // and it is searchable on the receiving device
        assert_eq!(b.get("select e.id from entries_fts join entries e on e.rowid = entries_fts.rowid where entries_fts match 'kilo*'"), "e1");
    }

    #[test]
    fn last_writer_wins_per_field_not_per_row() {
        let (mut a, mut b) = (Device::new("a"), Device::new("b"));
        a.write("entries", "e1", entry("hello"));
        let create = a.drain();
        b.receive(&create);

        // concurrently: a rates it, b favourites it
        a.write("entries", "e1", json!({"rating": 4}));
        b.write("entries", "e1", json!({"is_favorite": true}));
        let (from_a, from_b) = (a.drain(), b.drain());
        a.receive(&from_b);
        b.receive(&from_a);

        for d in [&a, &b] {
            assert_eq!(d.get("select cast(rating as text) from entries where id='e1'"), "4.0");
            assert_eq!(d.get("select cast(is_favorite as text) from entries where id='e1'"), "1");
        }
    }

    #[test]
    fn sequential_body_edits_are_not_conflicts() {
        let (mut a, mut b) = (Device::new("a"), Device::new("b"));
        a.write("entries", "e1", entry("v1"));
        b.receive(&a.drain());
        b.write("entries", "e1", body("v2 from b"));
        a.receive(&b.drain());
        a.write("entries", "e1", body("v3 from a"));
        b.receive(&a.drain());

        for d in [&a, &b] {
            assert_eq!(d.get("select body_text from entries where id='e1'"), "v3 from a");
            assert_eq!(d.conflicts(), 0);
        }
    }

    #[test]
    fn concurrent_body_edits_converge_and_both_devices_keep_the_loser() {
        let (mut a, mut b) = (Device::new("a"), Device::new("b"));
        a.write("entries", "e1", entry("v1"));
        b.receive(&a.drain());

        a.write("entries", "e1", body("written on a"));
        b.write("entries", "e1", body("written on b")); // later stamp: b wins
        let (from_a, from_b) = (a.drain(), b.drain());
        let on_a = a.receive(&from_b);
        let on_b = b.receive(&from_a);

        for d in [&a, &b] {
            assert_eq!(d.get("select body_text from entries where id='e1'"), "written on b");
            assert_eq!(d.conflicts(), 1);
            let loser = d.get("select body from revisions where is_conflict = 1");
            assert!(loser.contains("written on a"), "{loser}");
        }
        assert_eq!(on_a[0].conflict_entry.as_deref(), Some("e1"));
        assert_eq!(on_b[0].conflict_entry.as_deref(), Some("e1"));
    }

    #[test]
    fn three_devices_converge_whatever_order_ops_arrive_in_seq() {
        let (mut a, mut b, mut c) = (Device::new("a"), Device::new("b"), Device::new("c"));
        a.write("tags", "t1", json!({"name": "run"}));
        let seed = a.drain();
        b.receive(&seed);
        c.receive(&seed);

        a.write("tags", "t1", json!({"name": "running", "color": "#4cc38e"}));
        b.write("tags", "t1", json!({"color": "#007fff"}));
        c.write("tags", "t1", json!({"name": "jog", "deleted_at": null}));
        let (oa, ob, oc) = (a.drain(), b.drain(), c.drain());

        // The server's seq order is one interleaving every device sees.
        let log: Vec<Op> = [ob.clone(), oc.clone(), oa.clone()].concat();
        for d in [&mut a, &mut b, &mut c] {
            d.receive(&log);
        }
        let snapshot = |d: &Device| d.get("select name || '|' || color from tags where id='t1'");
        assert_eq!(snapshot(&a), snapshot(&b));
        assert_eq!(snapshot(&b), snapshot(&c));
    }

    #[test]
    fn ignores_tables_and_fields_from_newer_versions() {
        let mut b = Device::new("b");
        let op = Op {
            t: "moods".into(),
            id: "m".into(),
            f: BTreeMap::from([("x".into(), json!(1))]),
            h: hlc::render(hlc::Stamp { millis: 1, counter: 0 }, "a"),
            d: "a".into(),
            b: BTreeMap::new(),
        };
        assert_eq!(b.receive(&[op]), vec![Applied::default()]);
    }
}

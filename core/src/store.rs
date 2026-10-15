//! `Store`: the one object each app holds. It owns the database connection,
//! the device's clock and (once sync is set up) the journal key.
//!
//! Every method is synchronous and fast (SPEC.md §17 budgets); the apps call
//! them off the main thread. Writes that sync go through `changes::write`.

use std::collections::BTreeMap;
use std::sync::{Arc, Mutex, MutexGuard};

use rusqlite::{Connection, OptionalExtension, Row, params, types::Value as Sql};
use serde_json::{Value, json};

use crate::changes::{self, Op};
use crate::crypto::{self, JournalKey, Purpose};
use crate::doc::{self, Block};
use crate::error::{CoreError, Result};
use crate::hlc::Clock;
use crate::model::*;
use crate::{db, search};

/// The default journal has a fixed id so every device that starts fresh
/// creates the *same* row, and they converge instead of multiplying.
pub(crate) const DEFAULT_JOURNAL: &str = "00000000-0000-7000-8000-000000000001";
const EXCERPT_CHARS: usize = 160;

struct Inner {
    conn: Connection,
    clock: Clock,
    key: Option<JournalKey>,
}

#[derive(uniffi::Object)]
pub struct Store {
    device_id: String,
    inner: Mutex<Inner>,
}

#[uniffi::export]
impl Store {
    /// Opens (creating and migrating if needed) the database at `path`.
    #[uniffi::constructor]
    pub fn open(path: String) -> Result<Arc<Self>> {
        let mut conn = db::open(&path)?;

        let device_id = match get_device_setting(&conn, "device_id")? {
            Some(id) => id,
            None => {
                let id = uuid::Uuid::new_v4().to_string();
                set_device_setting(&conn, "device_id", &id)?;
                id
            }
        };
        let last: Option<String> = conn.query_row("select max(hlc) from field_clocks", [], |r| r.get(0))?;
        let mut clock = Clock::new(device_id.clone(), last.as_deref());

        let has_default: bool =
            conn.query_row("select exists (select 1 from journals where id = ?1)", [DEFAULT_JOURNAL], |r| r.get(0))?;
        if !has_default {
            let tx = conn.transaction()?;
            changes::write(
                &tx,
                &mut clock,
                &device_id,
                "journals",
                DEFAULT_JOURNAL,
                fields(json!({"name": "Journal", "is_default": true, "sort": 0})),
            )?;
            tx.commit()?;
        }

        Ok(Arc::new(Store { device_id, inner: Mutex::new(Inner { conn, clock, key: None }) }))
    }

    /// This install's id, a UUID. Sent as `p_device_id` to every sync RPC.
    pub fn device_id(&self) -> String {
        self.device_id.clone()
    }

    // --- Journals ------------------------------------------------------------

    pub fn journals(&self) -> Result<Vec<Journal>> {
        let g = self.lock();
        let mut stmt = g.conn.prepare_cached(
            "select id, name, color, is_default from journals where deleted_at is null order by sort, name",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok(Journal { id: r.get(0)?, name: r.get(1)?, color: r.get(2)?, is_default: r.get(3)? })
        })?;
        Ok(rows.collect::<std::result::Result<_, _>>()?)
    }

    pub fn create_journal(&self, name: String, color: Option<String>) -> Result<Journal> {
        let name = non_empty(name, "journal name")?;
        let id = new_id();
        let sort: i64 = self.lock().conn.query_row("select coalesce(max(sort), 0) + 1 from journals", [], |r| r.get(0))?;
        self.write("journals", &id, json!({"name": name, "color": color, "sort": sort, "is_default": false}))?;
        Ok(Journal { id, name, color, is_default: false })
    }

    pub fn default_journal_id(&self) -> String {
        DEFAULT_JOURNAL.to_string()
    }

    // --- Entries -------------------------------------------------------------

    /// A new, empty entry. `date` is `YYYY-MM-DD`, `time` is `HH:MM`.
    pub fn create_entry(&self, journal_id: Option<String>, date: String, time: Option<String>) -> Result<Entry> {
        check_date(&date)?;
        if let Some(t) = &time {
            check_time(t)?;
        }
        let id = new_id();
        let journal_id = journal_id.unwrap_or_else(|| DEFAULT_JOURNAL.to_string());
        self.write(
            "entries",
            &id,
            json!({
                "journal_id": journal_id, "date": date, "time": time, "body": "[]",
                "is_favorite": false, "created_at": now_rfc3339(),
            }),
        )?;
        self.entry(id)
    }

    pub fn entry(&self, id: String) -> Result<Entry> {
        let g = self.lock();
        let mut e = g.conn.query_row(
            "select id, journal_id, date, time, title, body, word_count, rating, is_favorite, updated_at,
                    exists (select 1 from revisions r where r.entry_id = entries.id and r.is_conflict = 1),
                    end_date
             from entries where id = ?1 and deleted_at is null",
            [&id],
            |r| {
                Ok(Entry {
                    id: r.get(0)?,
                    journal_id: r.get(1)?,
                    date: r.get(2)?,
                    time: r.get(3)?,
                    end_date: r.get(11)?,
                    title: r.get(4)?,
                    body: doc::parse(&r.get::<_, String>(5)?),
                    word_count: r.get(6)?,
                    rating: r.get(7)?,
                    is_favorite: r.get(8)?,
                    updated_at: r.get(9)?,
                    has_conflict: r.get(10)?,
                    tags: Vec::new(),
                })
            },
        )
        .optional()?
        .ok_or_else(|| CoreError::NotFound(format!("entry {id}")))?;
        e.tags = entry_tags(&g.conn, &id)?;
        Ok(e)
    }

    /// Saves the body; returns the new word count for the editor footer.
    pub fn save_body(&self, id: String, body: Vec<Block>) -> Result<u32> {
        let json = doc::to_json(&body);
        let unchanged: bool = self
            .lock()
            .conn
            .query_row("select body = ?2 from entries where id = ?1", params![id, json], |r| r.get(0))
            .optional()?
            .unwrap_or(false);
        if !unchanged {
            self.write("entries", &id, json!({ "body": json }))?;
        }
        Ok(doc::word_count(&body))
    }

    pub fn set_title(&self, id: String, title: Option<String>) -> Result<()> {
        let title = title.map(|t| t.trim().to_string()).filter(|t| !t.is_empty());
        self.write("entries", &id, json!({ "title": title }))
    }

    /// Rating from 0 to 5 in half steps, or none to clear it.
    pub fn set_rating(&self, id: String, rating: Option<f64>) -> Result<()> {
        let rating = rating.map(|r| (r.clamp(0.0, 5.0) * 2.0).round() / 2.0);
        self.write("entries", &id, json!({ "rating": rating }))
    }

    pub fn set_favorite(&self, id: String, favorite: bool) -> Result<()> {
        self.write("entries", &id, json!({ "is_favorite": favorite }))
    }

    /// Moves an entry. One covering several days keeps its length.
    pub fn set_date_time(&self, id: String, date: String, time: Option<String>) -> Result<()> {
        let start = parse_date(&date)?;
        if let Some(t) = &time {
            check_time(t)?;
        }
        let (old, end): (String, Option<String>) = self
            .lock()
            .conn
            .query_row("select date, end_date from entries where id = ?1", [&id], |r| Ok((r.get(0)?, r.get(1)?)))
            .optional()?
            .ok_or_else(|| CoreError::NotFound(format!("entry {id}")))?;
        match end {
            Some(end) => {
                let days = (parse_date(&end)? - parse_date(&old)?).whole_days();
                let end = ymd(start + time::Duration::days(days));
                self.write("entries", &id, json!({ "date": date, "time": time, "end_date": end }))
            }
            None => self.write("entries", &id, json!({ "date": date, "time": time })),
        }
    }

    /// Makes an entry cover every day up to `end_date`, or just its own day
    /// when none. An end on or before the start day clears it.
    pub fn set_end_date(&self, id: String, end_date: Option<String>) -> Result<()> {
        let start: String = self
            .lock()
            .conn
            .query_row("select date from entries where id = ?1", [&id], |r| r.get(0))
            .optional()?
            .ok_or_else(|| CoreError::NotFound(format!("entry {id}")))?;
        let end = match end_date {
            Some(e) => {
                let e = parse_date(&e)?;
                let s = parse_date(&start)?;
                if (e - s).whole_days() > MAX_SPAN_DAYS {
                    return Err(CoreError::Invalid(format!("an entry can cover at most {} days", MAX_SPAN_DAYS + 1)));
                }
                (e > s).then(|| ymd(e))
            }
            None => None,
        };
        self.write("entries", &id, json!({ "end_date": end }))
    }

    pub fn move_entry(&self, id: String, journal_id: String) -> Result<()> {
        self.write("entries", &id, json!({ "journal_id": journal_id }))
    }

    /// Soft delete; the entry stays in Trash for 30 days (SPEC.md §6.2).
    pub fn delete_entry(&self, id: String) -> Result<()> {
        self.write("entries", &id, json!({ "deleted_at": now_rfc3339() }))
    }

    pub fn restore_entry(&self, id: String) -> Result<()> {
        self.write("entries", &id, json!({ "deleted_at": null }))
    }

    // --- Reading -------------------------------------------------------------

    /// All entries on one day, including any covering several days that
    /// take it in, earliest first.
    pub fn day(&self, date: String, journal_id: Option<String>) -> Result<Vec<EntrySummary>> {
        let g = self.lock();
        let mut sql = summary_select("e.date <= ?1 and coalesce(e.end_date, e.date) >= ?1");
        let mut args: Vec<Sql> = vec![Sql::Text(date)];
        if let Some(j) = journal_id {
            sql.push_str(" and e.journal_id = ?2");
            args.push(Sql::Text(j));
        }
        sql.push_str(" order by e.date, coalesce(e.time, ''), e.created_at");
        query_summaries(&g.conn, &sql, args)
    }

    /// Newest first, `limit` at a time. Pass the last row back as `after`.
    pub fn timeline(&self, filter: TimelineFilter, after: Option<TimelineCursor>, limit: u32) -> Result<Vec<EntrySummary>> {
        let g = self.lock();
        let mut sql = summary_select("1");
        let mut args: Vec<Sql> = Vec::new();
        if let Some(c) = after {
            args.extend([Sql::Text(c.date), Sql::Text(c.time.unwrap_or_default()), Sql::Text(c.id)]);
            sql.push_str(&format!(
                " and (e.date, coalesce(e.time, ''), e.id) < (?{}, ?{}, ?{})",
                args.len() - 2,
                args.len() - 1,
                args.len()
            ));
        }
        if let Some(j) = filter.journal_id {
            args.push(Sql::Text(j));
            sql.push_str(&format!(" and e.journal_id = ?{}", args.len()));
        }
        for tag in filter.tag_ids {
            args.push(Sql::Text(tag));
            sql.push_str(&format!(
                " and exists (select 1 from entry_tags et where et.entry_id = e.id and et.tag_id = ?{} and et.deleted_at is null)",
                args.len()
            ));
        }
        if let Some(r) = filter.min_rating {
            args.push(Sql::Real(r));
            sql.push_str(&format!(" and e.rating >= ?{}", args.len()));
        }
        if filter.favorites_only {
            sql.push_str(" and e.is_favorite = 1");
        }
        args.push(Sql::Integer(limit.clamp(1, 500) as i64));
        sql.push_str(&format!(" order by e.date desc, coalesce(e.time, '') desc, e.id desc limit ?{}", args.len()));
        query_summaries(&g.conn, &sql, args)
    }

    /// Calendar marks for one month, `YYYY-MM`. Entries covering several
    /// days mark each of them.
    pub fn month(&self, year_month: String, journal_id: Option<String>) -> Result<Vec<DayMark>> {
        let first = parse_date(&format!("{year_month}-01"))?;
        let last = ymd(first.replace_day(first.month().length(first.year()))
            .map_err(|e| CoreError::Invalid(e.to_string()))?);
        let first_s = ymd(first);
        let g = self.lock();
        let mut stmt = g.conn.prepare_cached(
            "select date, end_date, rating from entries
             where deleted_at is null and date <= ?2 and coalesce(end_date, date) >= ?1
               and (?3 is null or journal_id = ?3)",
        )?;
        let rows = stmt.query_map(params![first_s, last, journal_id], |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, Option<String>>(1)?, r.get::<_, Option<f64>>(2)?))
        })?;
        // date → (count, rating sum, rated count, span before, span after)
        let mut days: std::collections::BTreeMap<String, (u32, f64, u32, bool, bool)> = Default::default();
        for row in rows {
            let (start, end, rating) = row?;
            let s = parse_date(&start)?;
            let e = end.as_deref().map(parse_date).transpose()?.unwrap_or(s).min(s + time::Duration::days(MAX_SPAN_DAYS));
            let mut d = s.max(first);
            let stop = e.min(parse_date(&last)?);
            while d <= stop {
                let m = days.entry(ymd(d)).or_default();
                m.0 += 1;
                if let Some(r) = rating {
                    m.1 += r;
                    m.2 += 1;
                }
                m.3 |= d > s;
                m.4 |= d < e;
                d = d.next_day().expect("in range");
            }
        }
        Ok(days
            .into_iter()
            .map(|(date, (count, sum, rated, before, after))| DayMark {
                date,
                entry_count: count,
                rating: (rated > 0).then(|| sum / rated as f64),
                span_before: before,
                span_after: after,
            })
            .collect())
    }

    /// Entries from the same day and month in earlier years, newest year first.
    pub fn on_this_day(&self, date: String) -> Result<Vec<EntrySummary>> {
        check_date(&date)?;
        let g = self.lock();
        let sql = summary_select("substr(e.date, 6) = substr(?1, 6) and e.date < ?1")
            + " order by e.date desc, coalesce(e.time, '')";
        query_summaries(&g.conn, &sql, vec![Sql::Text(date)])
    }

    pub fn search(&self, query: String, limit: u32) -> Result<Vec<SearchHit>> {
        let q = search::parse(&query);
        if q.fts.is_none() && q.clauses.is_empty() {
            return Ok(Vec::new());
        }
        let g = self.lock();
        let mut args: Vec<Sql> = Vec::new();
        let (from, snippet, order) = match &q.fts {
            Some(m) => {
                args.push(Sql::Text(m.clone()));
                (
                    "entries_fts f join entries e on e.rowid = f.rowid".to_string(),
                    "snippet(entries_fts, 1, char(57344), char(57345), '…', 16)".to_string(),
                    "bm25(entries_fts), e.date desc".to_string(),
                )
            }
            None => ("entries e".to_string(), "substr(e.body_text, 1, 200)".to_string(), "e.date desc".to_string()),
        };
        let mut sql = format!(
            "select {cols}, {snippet} from {from} where e.deleted_at is null",
            cols = SUMMARY_COLS
        );
        if q.fts.is_some() {
            sql.push_str(" and entries_fts match ?1");
        }
        for (clause, param) in clauses_with_params(&q) {
            if let Some(p) = param {
                args.push(p);
            }
            sql.push_str(" and ");
            sql.push_str(&renumber(&clause, args.len()));
        }
        args.push(Sql::Integer(limit.clamp(1, 200) as i64));
        sql.push_str(&format!(" order by {order} limit ?{}", args.len()));

        let mut stmt = g.conn.prepare(&sql)?;
        let rows = stmt.query_map(rusqlite::params_from_iter(args), |r| {
            Ok(SearchHit { entry: summary_from_row(r)?, snippet: r.get::<_, Option<String>>(11)?.unwrap_or_default() })
        })?;
        Ok(rows.collect::<std::result::Result<_, _>>()?)
    }

    /// Totals and streaks. `today` is the device's local date.
    pub fn stats(&self, today: String) -> Result<Stats> {
        check_date(&today)?;
        let g = self.lock();
        let (entries, words, avg): (u32, i64, Option<f64>) = g.conn.query_row(
            "select count(*), coalesce(sum(word_count), 0), avg(rating) from entries where deleted_at is null",
            [],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )?;
        let mut stmt = g.conn.prepare_cached(
            "select distinct date from entries where deleted_at is null and date <= ?1 order by date",
        )?;
        let days: Vec<String> = stmt.query_map([&today], |r| r.get(0))?.collect::<std::result::Result<_, _>>()?;
        let (current, longest) = streaks(&days, &today);
        Ok(Stats {
            entries,
            words: words.max(0) as u64,
            days_written: days.len() as u32,
            current_streak: current,
            longest_streak: longest,
            average_rating: avg,
        })
    }

    // --- Tags ----------------------------------------------------------------

    pub fn tags(&self) -> Result<Vec<Tag>> {
        let g = self.lock();
        let mut stmt = g.conn.prepare_cached(
            "select t.id, t.name, t.color, t.is_tracker, null from tags t
             left join entry_tags et on et.tag_id = t.id and et.deleted_at is null
             where t.deleted_at is null
             group by t.id order by count(et.id) desc, lower(t.name)",
        )?;
        let rows = stmt.query_map([], tag_from_row)?;
        Ok(rows.collect::<std::result::Result<_, _>>()?)
    }

    /// Returns the tag with this name (any case), creating it if needed. The
    /// id derives from the lowercased name, so two devices that each create
    /// "Run" offline end up with one tag, not two.
    pub fn tag_named(&self, name: String) -> Result<Tag> {
        let name = non_empty(name, "tag name")?;
        let existing = {
            let g = self.lock();
            g.conn
                .query_row(
                    "select id, name, color, is_tracker, null from tags where lower(name) = lower(?1) and deleted_at is null",
                    [&name],
                    tag_from_row,
                )
                .optional()?
        };
        if let Some(t) = existing {
            return Ok(t);
        }
        let id = name_id("tag", &name);
        self.write("tags", &id, json!({ "name": name, "is_tracker": false, "deleted_at": null }))?;
        Ok(Tag { id, name, color: None, is_tracker: false, value: None })
    }

    pub fn tag_entry(&self, entry_id: String, tag_id: String, value: Option<String>) -> Result<()> {
        let id = format!("{entry_id}:{tag_id}");
        self.write("entry_tags", &id, json!({ "entry_id": entry_id, "tag_id": tag_id, "value": value, "deleted_at": null }))
    }

    pub fn untag_entry(&self, entry_id: String, tag_id: String) -> Result<()> {
        let id = format!("{entry_id}:{tag_id}");
        self.write("entry_tags", &id, json!({ "entry_id": entry_id, "tag_id": tag_id, "deleted_at": now_rfc3339() }))
    }

    // --- Revisions -----------------------------------------------------------

    pub fn revisions(&self, entry_id: String) -> Result<Vec<Revision>> {
        let g = self.lock();
        let mut stmt = g.conn.prepare_cached(
            "select id, body, device_id, is_conflict, created_at from revisions
             where entry_id = ?1 order by created_at desc limit 50",
        )?;
        let rows = stmt.query_map([entry_id], |r| {
            Ok(Revision {
                id: r.get(0)?,
                body: doc::parse(&r.get::<_, String>(1)?),
                device_id: r.get(2)?,
                is_conflict: r.get(3)?,
                created_at: r.get(4)?,
            })
        })?;
        Ok(rows.collect::<std::result::Result<_, _>>()?)
    }

    /// The user has reviewed an entry's conflicts: clear the banner.
    pub fn resolve_conflicts(&self, entry_id: String) -> Result<()> {
        self.lock().conn.execute("update revisions set is_conflict = 0 where entry_id = ?1", [entry_id])?;
        Ok(())
    }

    // --- Sync: keys ------------------------------------------------------------

    /// A fresh journal key for a first-time sync setup. The app stores it in
    /// the keychain and passes it to `set_journal_key` on every launch.
    pub fn generate_journal_key(&self) -> Result<Vec<u8>> {
        Ok(JournalKey::generate()?.bytes().to_vec())
    }

    pub fn set_journal_key(&self, key: Vec<u8>) -> Result<()> {
        self.lock().key = Some(JournalKey::from_bytes(&key)?);
        Ok(())
    }

    pub fn forget_journal_key(&self) {
        self.lock().key = None;
    }

    pub fn recovery_phrase(&self) -> Result<String> {
        self.with_key(|k| Ok(k.recovery_phrase()))
    }

    /// The key a typed recovery phrase stands for, to check against the
    /// account's `key_check` before storing it.
    pub fn key_from_recovery_phrase(&self, phrase: String) -> Result<Vec<u8>> {
        Ok(JournalKey::from_recovery_phrase(&phrase)?.bytes().to_vec())
    }

    /// The value for `set_key_check`, sealed under the current key.
    pub fn key_check(&self) -> Result<String> {
        self.with_key(|k| k.key_check())
    }

    /// True if `key_check` (from `register_device`) was sealed with `key`.
    pub fn key_matches(&self, key: Vec<u8>, key_check: String) -> Result<bool> {
        Ok(JournalKey::from_bytes(&key)?.verifies(&key_check))
    }

    /// The device's name, sealed for `register_device`'s `p_name_enc`.
    pub fn seal_device_name(&self, name: String) -> Result<String> {
        self.with_key(|k| k.seal_b64(Purpose::DeviceName, name.as_bytes()))
    }

    pub fn open_device_name(&self, sealed: String) -> Result<String> {
        self.with_key(|k| Ok(String::from_utf8_lossy(&k.open_b64(Purpose::DeviceName, &sealed)?).into_owned()))
    }

    /// Existing-device half of QR pairing: seals the journal key for the
    /// device that showed `public_key`. Post the result with `pairing_fulfil`.
    pub fn seal_key_for_pairing(&self, public_key: Vec<u8>) -> Result<String> {
        self.with_key(|k| crypto::seal_for_pairing(k, &public_key))
    }

    // --- Sync: ops ---------------------------------------------------------------

    pub fn pending_count(&self) -> Result<u32> {
        changes::pending_count(&self.lock().conn)
    }

    /// The next batch to push, or none if everything is pushed. Until
    /// `push_succeeded` is called, this keeps returning the same batch.
    pub fn outgoing_batch(&self, max_ops: u32) -> Result<Option<OutgoingBatch>> {
        let mut g = self.lock();
        let Inner { conn, key, .. } = &mut *g;
        let key = key.as_ref().ok_or_else(no_key)?;
        let inflight = get_sync_meta(conn, "inflight")?;

        let tx = conn.transaction()?;
        let (batch_id, ops, last) = match inflight.as_deref().and_then(parse_inflight) {
            // Rebuild exactly the batch that was in flight, so a retry is a no-op
            // on the server if the first attempt actually landed.
            Some((batch_id, last_id)) => {
                let (ops, last) = changes::pending_through(&tx, last_id)?;
                (batch_id, ops, last)
            }
            None => {
                let (ops, last) = changes::pending(&tx, max_ops.clamp(1, 5000))?;
                (uuid::Uuid::new_v4().to_string(), ops, last)
            }
        };
        let Some(last) = last else {
            tx.execute("delete from sync_meta where key = 'inflight'", [])?;
            tx.commit()?;
            return Ok(None);
        };
        tx.execute(
            "insert into sync_meta (key, value) values ('inflight', ?1)
             on conflict (key) do update set value = excluded.value",
            [format!("{batch_id}:{last}")],
        )?;
        tx.commit()?;

        let payload = key.seal_b64(Purpose::Ops, &serde_json::to_vec(&ops)?)?;
        Ok(Some(OutgoingBatch { batch_id, payload, op_count: ops.len() as u32 }))
    }

    /// The server accepted `batch_id`: drop those ops from the queue.
    pub fn push_succeeded(&self, batch_id: String) -> Result<()> {
        let mut g = self.lock();
        let tx = g.conn.transaction()?;
        let inflight: Option<String> =
            tx.query_row("select value from sync_meta where key = 'inflight'", [], |r| r.get(0)).optional()?;
        if let Some((b, last)) = inflight.as_deref().and_then(parse_inflight)
            && b == batch_id
        {
            changes::drop_pending_through(&tx, last)?;
            tx.execute("delete from sync_meta where key = 'inflight'", [])?;
        }
        tx.commit()?;
        Ok(())
    }

    /// Where the last pull ended: pass as `p_after` to `pull_ops`.
    pub fn sync_cursor(&self) -> Result<i64> {
        Ok(get_sync_meta(&self.lock().conn, "cursor")?.and_then(|v| v.parse().ok()).unwrap_or(0))
    }

    /// Merges rows from `pull_ops`, in the order the server returned them.
    pub fn apply_incoming(&self, rows: Vec<IncomingOps>) -> Result<PullResult> {
        let mut g = self.lock();
        let Inner { conn, clock, key } = &mut *g;
        let key = key.as_ref().ok_or_else(no_key)?;
        let mut cursor: i64 = get_sync_meta(conn, "cursor")?.and_then(|v| v.parse().ok()).unwrap_or(0);
        let mut out = PullResult::default();

        let tx = conn.transaction()?;
        for row in rows {
            if row.seq <= cursor {
                continue; // already merged (a retried pull)
            }
            if row.device_id != self.device_id {
                let plaintext = key.open_b64(Purpose::Ops, &row.payload)?;
                let ops: Vec<Op> = serde_json::from_slice(&plaintext)?;
                for op in &ops {
                    let applied = changes::apply(&tx, clock, &self.device_id, op)?;
                    out.changed += applied.changed as u32;
                    if let Some(e) = applied.conflict_entry
                        && !out.conflicts.contains(&e)
                    {
                        out.conflicts.push(e);
                    }
                }
            }
            cursor = row.seq;
        }
        tx.execute(
            "insert into sync_meta (key, value) values ('cursor', ?1)
             on conflict (key) do update set value = excluded.value",
            [cursor.to_string()],
        )?;
        tx.commit()?;
        out.cursor = cursor;
        Ok(out)
    }

    /// The whole synced state, sealed, for `put_snapshot` (SPEC.md §12.5).
    /// Upload one when `pull_ops` has returned a few thousand ops since the
    /// last snapshot; the server then trims the log below `seq`.
    pub fn snapshot(&self) -> Result<OutgoingSnapshot> {
        let g = self.lock();
        let key = g.key.as_ref().ok_or_else(no_key)?;
        let seq: i64 = get_sync_meta(&g.conn, "cursor")?.and_then(|v| v.parse().ok()).unwrap_or(0);
        let mut rows = Vec::new();
        for (table, cols) in db::SYNCED {
            let sql = format!("select id, {} from {table}", cols.join(", "));
            let mut stmt = g.conn.prepare(&sql)?;
            let mut clocks = g.conn.prepare_cached("select field, hlc from field_clocks where tbl = ?1 and row_id = ?2")?;
            let table_rows: Vec<(String, Vec<Value>)> = stmt
                .query_map([], |r| {
                    let vals = (0..cols.len()).map(|i| r.get_ref(i + 1).map(sql_to_json)).collect::<rusqlite::Result<_>>()?;
                    Ok((r.get(0)?, vals))
                })?
                .collect::<std::result::Result<_, _>>()?;
            for (id, vals) in table_rows {
                let stamps: BTreeMap<String, String> = clocks
                    .query_map(params![table, id], |r| Ok((r.get(0)?, r.get(1)?)))?
                    .collect::<std::result::Result<_, _>>()?;
                let f: BTreeMap<&str, (Value, &str)> = cols
                    .iter()
                    .zip(vals)
                    .filter_map(|(c, v)| stamps.get(*c).map(|h| (*c, (v, h.as_str()))))
                    .collect();
                if !f.is_empty() {
                    rows.push(json!({ "t": table, "id": id, "f": f }));
                }
            }
        }
        let body = serde_json::to_vec(&json!({ "v": 1, "rows": rows }))?;
        Ok(OutgoingSnapshot { seq, payload: key.seal_b64(Purpose::Snapshot, &body)? })
    }

    /// Loads a snapshot from `get_snapshot`, then continue with `pull_ops`
    /// from the returned cursor. Used by a new device, or by one whose
    /// cursor fell behind compaction (`pull_ops` raised PT409).
    pub fn apply_snapshot(&self, seq: i64, payload: String) -> Result<PullResult> {
        let mut g = self.lock();
        let Inner { conn, clock, key } = &mut *g;
        let key = key.as_ref().ok_or_else(no_key)?;
        let body: Value = serde_json::from_slice(&key.open_b64(Purpose::Snapshot, &payload)?)?;
        let rows = body.get("rows").and_then(Value::as_array).cloned().unwrap_or_default();

        let mut out = PullResult::default();
        let tx = conn.transaction()?;
        for row in rows {
            let (Some(t), Some(id), Some(f)) =
                (row["t"].as_str(), row["id"].as_str(), row["f"].as_object())
            else {
                continue;
            };
            // Fields that share a stamp were one change; replay each group as
            // an op so the normal merge rule decides every field.
            let mut groups: BTreeMap<String, BTreeMap<String, Value>> = BTreeMap::new();
            for (field, pair) in f {
                if let (Some(v), Some(h)) = (pair.get(0), pair.get(1).and_then(Value::as_str)) {
                    groups.entry(h.to_string()).or_default().insert(field.clone(), v.clone());
                }
            }
            for (h, fields) in groups {
                let d = crate::hlc::parse(&h).map(|(_, d)| d.to_string()).unwrap_or_default();
                let op = Op { t: t.to_string(), id: id.to_string(), f: fields, h, d, b: BTreeMap::new() };
                let applied = changes::apply(&tx, clock, &self.device_id, &op)?;
                out.changed += applied.changed as u32;
                if let Some(e) = applied.conflict_entry
                    && !out.conflicts.contains(&e)
                {
                    out.conflicts.push(e);
                }
            }
        }
        let cursor = seq.max(get_sync_meta(&tx, "cursor")?.and_then(|v| v.parse().ok()).unwrap_or(0));
        tx.execute(
            "insert into sync_meta (key, value) values ('cursor', ?1)
             on conflict (key) do update set value = excluded.value",
            [cursor.to_string()],
        )?;
        tx.commit()?;
        out.cursor = cursor;
        Ok(out)
    }

    // --- Import and export (SPEC.md §16) ------------------------------------

    /// Writes the journal (or one journal) to `path` in `format`. Name the
    /// file with `export_extension(format)`.
    pub fn export(&self, format: crate::transfer::ExportFormat, path: String, journal_id: Option<String>) -> Result<crate::transfer::ExportSummary> {
        crate::transfer::export(&self.lock().conn, format, &path, journal_id.as_deref())
    }

    /// Reads `path` (a file, folder or zip) and says what importing it would
    /// do, without changing anything.
    pub fn preview_import(&self, path: String) -> Result<crate::transfer::ImportPreview> {
        crate::transfer::preview(&self.lock().conn, &path)
    }

    /// Imports `path` into `journal_id` (or each entry's own journal for a
    /// Librelog export, else the default). Entries already imported are
    /// skipped, so running this twice is safe. All or nothing.
    pub fn import_file(&self, path: String, journal_id: Option<String>) -> Result<crate::transfer::ImportResult> {
        let mut g = self.lock();
        let Inner { conn, clock, .. } = &mut *g;
        let tx = conn.transaction()?;
        let result = crate::transfer::import(&tx, clock, &self.device_id, &path, journal_id.as_deref())?;
        tx.commit()?;
        Ok(result)
    }

    /// Forgets all sync state for a fresh start with another account. The
    /// journal itself is untouched; every row is queued to push again.
    pub fn reset_sync(&self) -> Result<()> {
        let mut g = self.lock();
        let Inner { conn, clock, .. } = &mut *g;
        let tx = conn.transaction()?;
        tx.execute("delete from sync_meta", [])?;
        tx.execute("delete from pending_ops", [])?;
        for (table, cols) in db::SYNCED {
            let sql = format!("select id, {} from {table}", cols.join(", "));
            let rows: Vec<(String, BTreeMap<String, Value>)> = {
                let mut stmt = tx.prepare(&sql)?;
                stmt.query_map([], |r| {
                    let mut f = BTreeMap::new();
                    for (i, c) in cols.iter().enumerate() {
                        f.insert(c.to_string(), sql_to_json(r.get_ref(i + 1)?));
                    }
                    Ok((r.get(0)?, f))
                })?
                .collect::<std::result::Result<_, _>>()?
            };
            // Re-stamping is fine: a new account has no other history to lose to.
            for (id, f) in rows {
                changes::write(&tx, clock, &self.device_id, table, &id, f)?;
            }
        }
        tx.commit()?;
        Ok(())
    }
}

impl Store {
    fn lock(&self) -> MutexGuard<'_, Inner> {
        // A panic while holding the lock can't leave SQLite inconsistent (the
        // transaction rolls back), so carry on with the inner value.
        self.inner.lock().unwrap_or_else(|p| p.into_inner())
    }

    fn write(&self, table: &str, id: &str, f: Value) -> Result<()> {
        let mut g = self.lock();
        let Inner { conn, clock, .. } = &mut *g;
        let tx = conn.transaction()?;
        changes::write(&tx, clock, &self.device_id, table, id, fields(f))?;
        tx.commit()?;
        Ok(())
    }

    fn with_key<T>(&self, f: impl FnOnce(&JournalKey) -> Result<T>) -> Result<T> {
        let g = self.lock();
        f(g.key.as_ref().ok_or_else(no_key)?)
    }
}

fn parse_inflight(s: &str) -> Option<(String, i64)> {
    let (batch, last) = s.split_once(':')?;
    Some((batch.to_string(), last.parse().ok()?))
}

fn no_key() -> CoreError {
    CoreError::Sync("no journal key: set up sync first".into())
}

fn fields(v: Value) -> BTreeMap<String, Value> {
    serde_json::from_value(v).expect("callers pass a JSON object literal")
}

pub(crate) fn new_id() -> String {
    uuid::Uuid::now_v7().to_string()
}

/// A stable UUID-shaped id from a name, for rows two devices may create
/// independently and should converge on.
pub(crate) fn name_id(kind: &str, name: &str) -> String {
    let h = blake3::hash(format!("{kind}:{}", name.to_lowercase()).as_bytes());
    let mut b: [u8; 16] = h.as_bytes()[..16].try_into().expect("16 bytes");
    b[6] = (b[6] & 0x0f) | 0x80; // version 8: custom
    b[8] = (b[8] & 0x3f) | 0x80; // RFC 4122 variant
    uuid::Uuid::from_bytes(b).to_string()
}

fn non_empty(s: String, what: &str) -> Result<String> {
    let t = s.trim();
    if t.is_empty() { Err(CoreError::Invalid(format!("{what} is empty"))) } else { Ok(t.to_string()) }
}

fn check_date(d: &str) -> Result<()> {
    parse_date(d).map(|_| ())
}

/// The longest an entry can cover, past its first day: a year.
const MAX_SPAN_DAYS: i64 = 365;

fn parse_date(d: &str) -> Result<time::Date> {
    let fmt = time::macros::format_description!("[year]-[month]-[day]");
    time::Date::parse(d, &fmt).map_err(|_| CoreError::Invalid(format!("date {d}: want YYYY-MM-DD")))
}

fn ymd(d: time::Date) -> String {
    let fmt = time::macros::format_description!("[year]-[month]-[day]");
    d.format(&fmt).expect("valid date")
}

fn check_time(t: &str) -> Result<()> {
    let fmt = time::macros::format_description!("[hour]:[minute]");
    time::Time::parse(t, &fmt).map(|_| ()).map_err(|_| CoreError::Invalid(format!("time {t}: want HH:MM")))
}

pub(crate) fn now_rfc3339() -> String {
    time::OffsetDateTime::now_utc().format(&time::format_description::well_known::Rfc3339).unwrap_or_default()
}

/// Current streak (ending today or yesterday) and longest streak, from sorted
/// distinct dates.
fn streaks(days: &[String], today: &str) -> (u32, u32) {
    let fmt = time::macros::format_description!("[year]-[month]-[day]");
    let parsed: Vec<time::Date> = days.iter().filter_map(|d| time::Date::parse(d, &fmt).ok()).collect();
    let Ok(today) = time::Date::parse(today, &fmt) else { return (0, 0) };

    let mut longest = 0;
    let mut run = 0;
    let mut prev: Option<time::Date> = None;
    for d in &parsed {
        run = match prev {
            Some(p) if p.next_day() == Some(*d) => run + 1,
            _ => 1,
        };
        longest = longest.max(run);
        prev = Some(*d);
    }
    let current = match parsed.last() {
        Some(last) if *last == today || last.next_day() == Some(today) => run,
        _ => 0,
    };
    (current, longest)
}

fn get_device_setting(conn: &Connection, key: &str) -> Result<Option<String>> {
    Ok(conn.query_row("select value from device_settings where key = ?1", [key], |r| r.get(0)).optional()?)
}

fn set_device_setting(conn: &Connection, key: &str, value: &str) -> Result<()> {
    conn.execute(
        "insert into device_settings (key, value) values (?1, ?2) on conflict (key) do update set value = excluded.value",
        [key, value],
    )?;
    Ok(())
}

fn get_sync_meta(conn: &Connection, key: &str) -> Result<Option<String>> {
    Ok(conn.query_row("select value from sync_meta where key = ?1", [key], |r| r.get(0)).optional()?)
}

const SUMMARY_COLS: &str = "e.id, e.journal_id, e.date, e.time, e.title, e.body_text, e.word_count, e.rating, e.is_favorite,
    (select group_concat(t.name, char(31)) from entry_tags et join tags t on t.id = et.tag_id
      where et.entry_id = e.id and et.deleted_at is null and t.deleted_at is null),
    e.end_date";

fn summary_select(condition: &str) -> String {
    format!("select {SUMMARY_COLS} from entries e where e.deleted_at is null and {condition}")
}

fn query_summaries(conn: &Connection, sql: &str, args: Vec<Sql>) -> Result<Vec<EntrySummary>> {
    let mut stmt = conn.prepare_cached(sql)?;
    let rows = stmt.query_map(rusqlite::params_from_iter(args), summary_from_row)?;
    Ok(rows.collect::<std::result::Result<_, _>>()?)
}

fn summary_from_row(r: &Row) -> rusqlite::Result<EntrySummary> {
    let body_text: String = r.get(5)?;
    let tags: Option<String> = r.get(9)?;
    Ok(EntrySummary {
        id: r.get(0)?,
        journal_id: r.get(1)?,
        date: r.get(2)?,
        time: r.get(3)?,
        end_date: r.get(10)?,
        title: r.get(4)?,
        excerpt: doc::excerpt_text(&body_text, EXCERPT_CHARS),
        word_count: r.get(6)?,
        rating: r.get(7)?,
        is_favorite: r.get(8)?,
        tag_names: tags.map(|t| t.split('\u{1f}').map(str::to_string).collect()).unwrap_or_default(),
    })
}

fn tag_from_row(r: &Row) -> rusqlite::Result<Tag> {
    Ok(Tag { id: r.get(0)?, name: r.get(1)?, color: r.get(2)?, is_tracker: r.get(3)?, value: r.get(4)? })
}

fn entry_tags(conn: &Connection, entry_id: &str) -> Result<Vec<Tag>> {
    let mut stmt = conn.prepare_cached(
        "select t.id, t.name, t.color, t.is_tracker, et.value from entry_tags et
         join tags t on t.id = et.tag_id
         where et.entry_id = ?1 and et.deleted_at is null and t.deleted_at is null
         order by lower(t.name)",
    )?;
    let rows = stmt.query_map([entry_id], tag_from_row)?;
    Ok(rows.collect::<std::result::Result<_, _>>()?)
}

/// search::Query's clauses use bare `?`; pair each with its parameter (if it
/// takes one) so the caller can number them after any earlier parameters.
fn clauses_with_params(q: &search::Query) -> Vec<(String, Option<Sql>)> {
    let mut params = q.params.iter().cloned();
    q.clauses
        .iter()
        .map(|c| {
            let p = if c.contains('?') { params.next() } else { None };
            (c.clone(), p)
        })
        .collect()
}

fn renumber(clause: &str, n: usize) -> String {
    clause.replacen('?', &format!("?{n}"), 1)
}

fn sql_to_json(v: rusqlite::types::ValueRef) -> Value {
    use rusqlite::types::ValueRef::*;
    match v {
        Null => Value::Null,
        Integer(i) => json!(i),
        Real(f) => json!(f),
        Text(t) => json!(String::from_utf8_lossy(t)),
        Blob(b) => json!(String::from_utf8_lossy(b)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn store() -> Arc<Store> {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("j.sqlite").to_str().unwrap().to_string();
        std::mem::forget(dir);
        Store::open(path).unwrap()
    }

    fn para(text: &str) -> Vec<Block> {
        vec![Block { kind: doc::BlockKind::Paragraph, text: text.into(), marks: vec![], indent: 0 }]
    }

    fn write_entry(s: &Store, date: &str, time: &str, text: &str) -> String {
        let e = s.create_entry(None, date.into(), Some(time.into())).unwrap();
        s.save_body(e.id.clone(), para(text)).unwrap();
        e.id
    }

    #[test]
    fn first_launch_has_one_default_journal_and_reopening_keeps_the_device_id() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("j.sqlite").to_str().unwrap().to_string();
        let a = Store::open(path.clone()).unwrap();
        let id = a.device_id();
        drop(a);
        let b = Store::open(path).unwrap();
        assert_eq!(b.device_id(), id);
        let j = b.journals().unwrap();
        assert_eq!(j.len(), 1);
        assert!(j[0].is_default);
    }

    #[test]
    fn writing_reading_and_word_counts() {
        let s = store();
        let id = write_entry(&s, "2026-10-03", "08:30", "Coffee by the canal, then work.");
        s.set_title(id.clone(), Some("  Saturday  ".into())).unwrap();
        s.set_rating(id.clone(), Some(4.3)).unwrap();
        let e = s.entry(id).unwrap();
        assert_eq!(e.title.as_deref(), Some("Saturday"));
        assert_eq!(e.rating, Some(4.5));
        assert_eq!(e.word_count, 6);
        assert_eq!(e.body, para("Coffee by the canal, then work."));
    }

    #[test]
    fn rejects_bad_dates_and_times() {
        let s = store();
        assert!(s.create_entry(None, "2026-13-01".into(), None).is_err());
        assert!(s.create_entry(None, "2026-10-03".into(), Some("25:00".into())).is_err());
    }

    #[test]
    fn day_timeline_and_paging() {
        let s = store();
        write_entry(&s, "2026-10-01", "09:00", "one");
        write_entry(&s, "2026-10-02", "09:00", "two");
        write_entry(&s, "2026-10-02", "21:00", "three");
        write_entry(&s, "2026-10-03", "07:00", "four");

        let day: Vec<String> = s.day("2026-10-02".into(), None).unwrap().into_iter().map(|e| e.excerpt).collect();
        assert_eq!(day, ["two", "three"]);

        let page1 = s.timeline(TimelineFilter::default(), None, 2).unwrap();
        assert_eq!(page1.iter().map(|e| e.excerpt.as_str()).collect::<Vec<_>>(), ["four", "three"]);
        let last = page1.last().unwrap();
        let cursor = TimelineCursor { date: last.date.clone(), time: last.time.clone(), id: last.id.clone() };
        let page2 = s.timeline(TimelineFilter::default(), Some(cursor), 10).unwrap();
        assert_eq!(page2.iter().map(|e| e.excerpt.as_str()).collect::<Vec<_>>(), ["two", "one"]);
    }

    #[test]
    fn deleted_entries_disappear_and_can_be_restored() {
        let s = store();
        let id = write_entry(&s, "2026-10-03", "07:00", "gone");
        s.delete_entry(id.clone()).unwrap();
        assert!(s.day("2026-10-03".into(), None).unwrap().is_empty());
        assert!(s.search("gone".into(), 10).unwrap().is_empty());
        s.restore_entry(id).unwrap();
        assert_eq!(s.day("2026-10-03".into(), None).unwrap().len(), 1);
    }

    #[test]
    fn calendar_month_and_on_this_day() {
        let s = store();
        let a = write_entry(&s, "2026-10-03", "07:00", "today");
        s.set_rating(a, Some(4.0)).unwrap();
        write_entry(&s, "2025-10-03", "07:00", "a year ago");
        write_entry(&s, "2024-10-03", "07:00", "two years ago");
        write_entry(&s, "2025-10-04", "07:00", "not this day");

        let marks = s.month("2026-10".into(), None).unwrap();
        assert_eq!(
            marks,
            vec![DayMark { date: "2026-10-03".into(), entry_count: 1, rating: Some(4.0), span_before: false, span_after: false }]
        );

        let otd: Vec<String> = s.on_this_day("2026-10-03".into()).unwrap().into_iter().map(|e| e.date).collect();
        assert_eq!(otd, ["2025-10-03", "2024-10-03"]);
    }

    #[test]
    fn entries_covering_several_days() {
        let s = store();
        let trip = write_entry(&s, "2026-09-29", "09:00", "the trip");
        s.set_end_date(trip.clone(), Some("2026-10-02".into())).unwrap();
        s.set_rating(trip.clone(), Some(5.0)).unwrap();
        write_entry(&s, "2026-10-01", "20:00", "that evening");

        assert_eq!(s.entry(trip.clone()).unwrap().end_date.as_deref(), Some("2026-10-02"));
        let day: Vec<String> = s.day("2026-10-01".into(), None).unwrap().into_iter().map(|e| e.excerpt).collect();
        assert_eq!(day, ["the trip", "that evening"]);
        assert!(s.day("2026-10-03".into(), None).unwrap().is_empty());

        // October only sees the part of the trip inside it; it carries on
        // from September.
        let marks = s.month("2026-10".into(), None).unwrap();
        let summary: Vec<_> = marks.iter().map(|m| (m.date.as_str(), m.entry_count, m.span_before, m.span_after)).collect();
        assert_eq!(summary, [("2026-10-01", 2, true, true), ("2026-10-02", 1, true, false)]);
        let sept = s.month("2026-09".into(), None).unwrap();
        assert_eq!((sept[0].date.as_str(), sept[0].span_before, sept[0].span_after), ("2026-09-29", false, true));
        assert_eq!(sept.len(), 2);

        // Listed once in the journal, on its first day.
        let all = s.timeline(TimelineFilter::default(), None, 50).unwrap();
        assert_eq!(all.iter().filter(|e| e.id == trip).count(), 1);

        // Moving it keeps its length; an end before the start clears it.
        s.set_date_time(trip.clone(), "2026-11-10".into(), None).unwrap();
        assert_eq!(s.entry(trip.clone()).unwrap().end_date.as_deref(), Some("2026-11-13"));
        s.set_end_date(trip.clone(), Some("2026-11-01".into())).unwrap();
        assert_eq!(s.entry(trip.clone()).unwrap().end_date, None);
        assert!(s.set_end_date(trip, Some("2028-01-01".into())).is_err());
    }

    #[test]
    fn search_with_text_and_operators() {
        let s = store();
        let run = write_entry(&s, "2026-10-01", "07:00", "Morning run along the canal, light rain");
        let walk = write_entry(&s, "2026-10-02", "07:00", "Long walk in the park with Sam");
        write_entry(&s, "2026-10-03", "07:00", "Quiet day at home");
        let tag = s.tag_named("Exercise".into()).unwrap();
        s.tag_entry(run.clone(), tag.id.clone(), None).unwrap();
        s.tag_entry(walk.clone(), tag.id, None).unwrap();
        s.set_rating(walk.clone(), Some(5.0)).unwrap();

        let ids = |q: &str| s.search(q.into(), 10).unwrap().into_iter().map(|h| h.entry.id).collect::<Vec<_>>();
        assert_eq!(ids("cana"), vec![run.clone()]);
        assert_eq!(ids("tag:exercise rating:>=4"), vec![walk.clone()]);
        assert_eq!(ids("tag:exercise -rain"), vec![walk.clone()]);
        assert_eq!(ids("\"walk in the park\""), vec![walk.clone()]);
        assert_eq!(ids("after:2026-10-01 before:2026-10-03"), vec![walk.clone()]);

        let hit = &s.search("canal".into(), 1).unwrap()[0];
        assert!(hit.snippet.contains("\u{e000}canal\u{e001}"), "{}", hit.snippet);
        assert_eq!(hit.entry.tag_names, vec!["Exercise".to_string()]);
    }

    #[test]
    fn tags_are_reused_by_name_and_ids_are_stable_across_devices() {
        let (a, b) = (store(), store());
        let t1 = a.tag_named("Run".into()).unwrap();
        let t2 = a.tag_named("run".into()).unwrap();
        let t3 = b.tag_named("RUN".into()).unwrap();
        assert_eq!(t1.id, t2.id);
        assert_eq!(t1.id, t3.id);
        assert!(uuid::Uuid::parse_str(&t1.id).is_ok());
    }

    #[test]
    fn streaks() {
        let s = store();
        for d in ["2026-09-20", "2026-09-21", "2026-09-22", "2026-09-30", "2026-10-01", "2026-10-02"] {
            write_entry(&s, d, "08:00", "x");
        }
        let st = s.stats("2026-10-03".into()).unwrap();
        assert_eq!((st.current_streak, st.longest_streak, st.days_written), (3, 3, 6));
        assert_eq!(s.stats("2026-10-05".into()).unwrap().current_streak, 0);
    }

    /// Two installs syncing through a fake server that behaves like
    /// librelog.push_ops / pull_ops: a shared, seq-ordered log.
    #[test]
    fn two_devices_sync_end_to_end_through_ciphertext() {
        let (a, b) = (store(), store());
        let key = a.generate_journal_key().unwrap();
        a.set_journal_key(key.clone()).unwrap();
        b.set_journal_key(key).unwrap();
        let mut log: Vec<IncomingOps> = Vec::new();

        let push = |s: &Store, log: &mut Vec<IncomingOps>| {
            while let Some(batch) = s.outgoing_batch(100).unwrap() {
                assert!(!batch.payload.contains("canal"), "payload must be ciphertext");
                let seq = log.len() as i64 + 1;
                log.push(IncomingOps { seq, device_id: s.device_id(), payload: batch.payload });
                s.push_succeeded(batch.batch_id).unwrap();
            }
        };
        let pull = |s: &Store, log: &[IncomingOps]| {
            let after = s.sync_cursor().unwrap();
            s.apply_incoming(log.iter().filter(|r| r.seq > after).cloned().collect()).unwrap()
        };

        let id = write_entry(&a, "2026-10-03", "08:30", "Coffee by the canal");
        let tag = a.tag_named("Coffee".into()).unwrap();
        a.tag_entry(id.clone(), tag.id, None).unwrap();
        push(&a, &mut log);
        pull(&b, &log);

        let on_b = b.entry(id.clone()).unwrap();
        assert_eq!(on_b.body, para("Coffee by the canal"));
        assert_eq!(on_b.tags.iter().map(|t| t.name.as_str()).collect::<Vec<_>>(), ["Coffee"]);
        assert_eq!(b.journals().unwrap().len(), 1, "default journal converges, not duplicates");

        // Concurrent edits: both push, both pull; they converge and flag it.
        a.save_body(id.clone(), para("Edited on the phone")).unwrap();
        b.save_body(id.clone(), para("Edited on the laptop")).unwrap();
        push(&a, &mut log);
        push(&b, &mut log);
        let ra = pull(&a, &log);
        let rb = pull(&b, &log);
        let (ea, eb) = (a.entry(id.clone()).unwrap(), b.entry(id.clone()).unwrap());
        assert_eq!(ea.body, eb.body);
        assert!(ea.has_conflict && eb.has_conflict);
        assert_eq!(ra.conflicts.len() + rb.conflicts.len(), 2);
        assert_eq!(a.pending_count().unwrap(), 0);
    }

    #[test]
    fn a_failed_push_retries_the_same_batch() {
        let s = store();
        s.set_journal_key(s.generate_journal_key().unwrap()).unwrap();
        write_entry(&s, "2026-10-03", "08:30", "x");
        let first = s.outgoing_batch(100).unwrap().unwrap();
        write_entry(&s, "2026-10-03", "09:30", "written while the push was in flight");
        let retry = s.outgoing_batch(100).unwrap().unwrap();
        assert_eq!(first.batch_id, retry.batch_id);
        assert_eq!(first.op_count, retry.op_count);
        s.push_succeeded(first.batch_id).unwrap();
        let next = s.outgoing_batch(100).unwrap().unwrap();
        assert_ne!(next.batch_id, retry.batch_id);
        assert!(next.op_count > 0);
    }

    #[test]
    fn a_new_device_starts_from_a_snapshot() {
        let (a, b) = (store(), store());
        let key = a.generate_journal_key().unwrap();
        a.set_journal_key(key.clone()).unwrap();
        b.set_journal_key(key).unwrap();
        let id = write_entry(&a, "2026-10-03", "08:30", "Before the snapshot");
        a.set_rating(id.clone(), Some(3.0)).unwrap();
        a.tag_entry(id.clone(), a.tag_named("Walk".into()).unwrap().id, Some("5km".into())).unwrap();

        let snap = a.snapshot().unwrap();
        assert!(!snap.payload.contains("snapshot"), "payload must be ciphertext");
        let r = b.apply_snapshot(snap.seq, snap.payload).unwrap();
        assert!(r.changed > 0 && r.conflicts.is_empty());

        let e = b.entry(id).unwrap();
        assert_eq!((e.body, e.rating), (para("Before the snapshot"), Some(3.0)));
        assert_eq!(e.tags[0].value.as_deref(), Some("5km"));
        assert_eq!(b.journals().unwrap().len(), 1);
    }

    fn tmp(name: &str) -> String {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join(name).to_str().unwrap().to_string();
        std::mem::forget(dir);
        p
    }

    fn sample(s: &Store) -> String {
        let id = write_entry(s, "2026-10-02", "21:40", "Walked the canal & back");
        s.set_title(id.clone(), Some("Long walk".into())).unwrap();
        s.set_rating(id.clone(), Some(4.0)).unwrap();
        s.set_favorite(id.clone(), true).unwrap();
        s.tag_entry(id.clone(), s.tag_named("Walk".into()).unwrap().id, Some("5km".into())).unwrap();
        let slow = write_entry(s, "2026-10-03", "08:15", "Slow start");
        s.set_end_date(slow, Some("2026-10-04".into())).unwrap();
        id
    }

    #[test]
    fn json_export_restores_losslessly_into_a_new_install_and_reimport_adds_nothing() {
        use crate::transfer::{ExportFormat, ImportSource};
        let (a, b) = (store(), store());
        let id = sample(&a);
        let path = tmp("journal.json");
        assert_eq!(a.export(ExportFormat::Json, path.clone(), None).unwrap().entries, 2);

        let p = b.preview_import(path.clone()).unwrap();
        assert_eq!((p.source, p.entries, p.already_imported, p.tags), (ImportSource::Librelog, 2, 0, 1));
        assert_eq!((p.first_date.as_deref(), p.last_date.as_deref()), (Some("2026-10-02"), Some("2026-10-03")));

        let r = b.import_file(path.clone(), None).unwrap();
        assert_eq!((r.imported, r.skipped), (2, 0));
        let (ea, eb) = (a.entry(id.clone()).unwrap(), b.entry(id.clone()).unwrap());
        assert_eq!((eb.title, eb.rating, eb.is_favorite, eb.body), (ea.title, ea.rating, ea.is_favorite, ea.body));
        assert_eq!(eb.tags[0].value.as_deref(), Some("5km"));

        assert_eq!(b.day("2026-10-04".into(), None).unwrap()[0].end_date.as_deref(), Some("2026-10-04"));

        assert_eq!(b.preview_import(path.clone()).unwrap().already_imported, 2);
        assert_eq!(b.import_file(path, None).unwrap().imported, 0);
        assert_eq!(b.day("2026-10-02".into(), None).unwrap().len(), 1);
    }

    #[test]
    fn markdown_export_round_trips_through_a_zip() {
        use crate::transfer::{ExportFormat, ImportSource};
        let (a, b) = (store(), store());
        let id = sample(&a);
        let zip = tmp("journal.zip");
        a.export(ExportFormat::Markdown, zip.clone(), None).unwrap();
        let r = b.import_file(zip.clone(), None).unwrap();
        assert_eq!((r.source, r.imported), (ImportSource::Markdown, 2));
        // front matter carries the id, so the entry keeps it and re-import skips it
        let e = b.entry(id).unwrap();
        assert_eq!((e.title.as_deref(), e.rating, e.is_favorite, e.time.as_deref()), (Some("Long walk"), Some(4.0), true, Some("21:40")));
        assert_eq!(e.tags[0].name, "Walk");
        assert_eq!(b.day("2026-10-04".into(), None).unwrap().len(), 1);
        assert_eq!(b.import_file(zip, None).unwrap().imported, 0);
    }

    #[test]
    fn html_and_text_exports_escape_and_order() {
        use crate::transfer::ExportFormat;
        let a = store();
        sample(&a);
        let html = tmp("j.html");
        a.export(ExportFormat::Html, html.clone(), None).unwrap();
        let h = std::fs::read_to_string(html).unwrap();
        assert!(h.contains("canal &amp; back") && h.contains("<h2>Long walk</h2>"));
        assert!(h.find("Slow start").unwrap() < h.find("Long walk").unwrap(), "newest first");
        let txt = tmp("j.txt");
        a.export(ExportFormat::Text, txt.clone(), None).unwrap();
        let t = std::fs::read_to_string(txt).unwrap();
        assert!(t.starts_with("Friday 2 October 2026\n"), "{t}");
        assert!(!std::path::Path::new(&format!("{}.part", tmp("x"))).exists());
    }

    #[test]
    fn day_one_json_dates_in_the_entry_time_zone() {
        use crate::transfer::ImportSource;
        let s = store();
        let path = tmp("Journal.json");
        std::fs::write(&path, r##"{"metadata":{"version":"1.0"},"entries":[
          {"uuid":"A1","creationDate":"2026-10-02T23:30:00Z","timeZone":"Europe/London","text":"# Late\n\nWent to bed \\*very\\* late.\n![](dayone-moment://X)","tags":["Night"],"starred":true},
          {"uuid":"B2","creationDate":"2026-10-03T07:00:00Z","timeZone":"America/New_York","text":"Early"}
        ]}"##).unwrap();
        let p = s.preview_import(path.clone()).unwrap();
        assert_eq!((p.source, p.entries, p.skipped_images), (ImportSource::DayOne, 2, 1));
        s.import_file(path.clone(), None).unwrap();
        // 23:30 UTC on 2 Oct is 00:30 BST on 3 Oct.
        let london = s.day("2026-10-03".into(), None).unwrap();
        let late = london.iter().find(|e| e.excerpt.contains("bed")).expect("dated in London time");
        assert_eq!(late.time.as_deref(), Some("00:30"));
        assert!(late.is_favorite && late.tag_names == vec!["Night".to_string()]);
        let full = s.entry(late.id.clone()).unwrap();
        assert_eq!(full.body[0].kind, doc::BlockKind::Heading { level: 1 });
        assert_eq!(full.body[1].text, "Went to bed *very* late.");
        // 07:00 UTC is 03:00 in New York.
        assert!(s.day("2026-10-03".into(), None).unwrap().iter().any(|e| e.time.as_deref() == Some("03:00")));
        assert_eq!(s.import_file(path, None).unwrap().skipped, 2);
    }

    #[test]
    fn diarium_backup_database() {
        use crate::transfer::ImportSource;
        let s = store();
        // No extension, as a downloaded backup can end up: found by its header.
        let path = tmp("Diarium.diary (1)");
        let _ = std::fs::remove_file(&path);
        {
            let db = Connection::open(&path).unwrap();
            db.execute_batch(
                "create table Entries (DiaryEntryId bigint primary key, Heading varchar, Text varchar, Rating integer, Latitude float, Longitude float);
                 create table Tags (DiaryTagId integer primary key, Value varchar, Type integer, Color varchar, Icon varchar);
                 create table EntryTags (DiaryEntryId bigint, DiaryTagId integer, TrackingValue varchar);
                 create table Media (DiaryMediaId varchar primary key, Type integer, Data blob, Name varchar, FileEnding varchar, \"Index\" integer, DiaryEntryId bigint);
                 -- 2023-01-01 17:39:09 and 2024-02-29 08:05:00 as .NET ticks
                 insert into Entries values (638081915490000000, 'Cabling', '<p>Finished <b>King Solomon''s</b> mines</p><p></p><ul><li>one</li><li>two</li></ul>', 0, 0, 0);
                 insert into Entries values (638447907000000000, '', '<p>Leap &amp; day</p>', 4, 0, 0);
                 insert into Tags values (3, 'DND', 0, null, null);
                 insert into EntryTags values (638447907000000000, 3, '');
                 insert into Media values ('m1', 0, x'00', 'a', '.jpg', 0, 638081915490000000);",
            )
            .unwrap();
        }
        let p = s.preview_import(path.clone()).unwrap();
        assert_eq!((p.source, p.entries, p.skipped_images), (ImportSource::Diarium, 2, 1));
        assert_eq!((p.first_date.as_deref(), p.last_date.as_deref()), (Some("2023-01-01"), Some("2024-02-29")));
        assert_eq!(s.import_file(path.clone(), None).unwrap().imported, 2);
        let first = &s.day("2023-01-01".into(), None).unwrap()[0];
        assert_eq!((first.time.as_deref(), first.title.as_deref(), first.rating), (Some("17:39"), Some("Cabling"), None));
        let body = s.entry(first.id.clone()).unwrap().body;
        assert_eq!(body.len(), 3, "empty <p> spacers are dropped, list items kept: {body:?}");
        assert_eq!(body[0].text, "Finished King Solomon's mines");
        assert_eq!(body[1].kind, doc::BlockKind::Bullet);
        let leap = &s.day("2024-02-29".into(), None).unwrap()[0];
        assert_eq!((leap.time.as_deref(), leap.rating, leap.title.as_deref()), (Some("08:05"), Some(4.0), None));
        assert_eq!(leap.tag_names, ["DND"]);
        assert_eq!(s.entry(leap.id.clone()).unwrap().body[0].text, "Leap & day");
        assert_eq!(s.import_file(path, None).unwrap().skipped, 2);
    }

    #[test]
    fn daylio_csv_moods_activities_and_duplicate_times() {
        use crate::transfer::ImportSource;
        let s = store();
        let path = tmp("daylio_export.csv");
        std::fs::write(&path, "\u{feff}full_date,date,weekday,time,mood,activities,note_title,note\n\
2026-10-02,October 2,Friday,9:15 PM,rad,friends | walk,Great day,Pub<br>then home\n\
2026-10-02,October 2,Friday,9:15 PM,meh,,,\n\
2026-10-01,October 1,Thursday,08:00,sleepy,work,,Tired\n").unwrap();
        let p = s.preview_import(path.clone()).unwrap();
        assert_eq!((p.source, p.entries), (ImportSource::Daylio, 3));
        assert!(p.warnings.iter().any(|w| w.contains("sleepy")));
        let r = s.import_file(path.clone(), None).unwrap();
        assert_eq!(r.imported, 3);
        let day = s.day("2026-10-02".into(), None).unwrap();
        assert_eq!(day.len(), 2, "same date and time twice are two entries");
        let great = day.iter().find(|e| e.title.as_deref() == Some("Great day")).unwrap();
        assert_eq!((great.time.as_deref(), great.rating), (Some("21:15"), Some(5.0)));
        assert_eq!(s.entry(great.id.clone()).unwrap().body.len(), 2, "<br> splits paragraphs");
        let custom = &s.day("2026-10-01".into(), None).unwrap()[0];
        assert_eq!(custom.rating, None);
        assert!(custom.tag_names.contains(&"sleepy".to_string()));
        assert_eq!(s.import_file(path, None).unwrap().imported, 0);
    }

    #[test]
    fn markdown_folder_dates_from_front_matter_or_file_name() {
        let s = store();
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("2026-09-30.md"), "Plain note with a **bold** bit").unwrap();
        std::fs::write(dir.path().join("trip.md"), "---\ndate: 2026-09-29T14:05\ntags:\n  - travel\n---\nOn the train").unwrap();
        std::fs::write(dir.path().join("undated.md"), "No date anywhere").unwrap();
        std::fs::write(dir.path().join(".hidden.md"), "---\ndate: 2026-01-01\n---\nskip me").unwrap();
        let path = dir.path().to_str().unwrap().to_string();
        let p = s.preview_import(path.clone()).unwrap();
        assert_eq!(p.entries, 2);
        assert!(p.warnings.iter().any(|w| w.starts_with("1 file")));
        s.import_file(path, None).unwrap();
        let train = &s.day("2026-09-29".into(), None).unwrap()[0];
        assert_eq!((train.time.as_deref(), train.tag_names.clone()), (Some("14:05"), vec!["travel".to_string()]));
        let e = s.entry(s.day("2026-09-30".into(), None).unwrap()[0].id.clone()).unwrap();
        assert_eq!(e.body[0].marks.len(), 1);
    }

    #[test]
    fn unknown_files_are_refused_with_a_clear_error() {
        let s = store();
        let path = tmp("photo.png");
        std::fs::write(&path, [0x89, b'P', b'N', b'G']).unwrap();
        assert!(matches!(s.preview_import(path), Err(CoreError::Invalid(m)) if m.contains("not a format")));
    }

    #[test]
    fn sync_needs_a_key() {
        let s = store();
        assert!(matches!(s.outgoing_batch(10), Err(CoreError::Sync(_))));
    }
}

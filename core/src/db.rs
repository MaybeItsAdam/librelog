//! The local SQLite database: opening it, and its forward-only migrations.
//! SPEC.md §4 is the model this implements.

use rusqlite::Connection;

use crate::error::Result;

/// Synced tables and the columns that sync. Anything not listed here
/// (attachments.local_path, the FTS index, sync bookkeeping) stays on the
/// device. `changes::write` refuses fields outside this list, so a new
/// column has to be added here deliberately to start syncing.
pub const SYNCED: &[(&str, &[&str])] = &[
    ("journals", &["name", "color", "icon", "sort", "is_default", "deleted_at"]),
    (
        "entries",
        &[
            "journal_id", "date", "time", "tz", "title", "body", "rating", "place_id",
            "template_id", "is_favorite", "weather", "created_at", "deleted_at", "import_ref", "end_date",
        ],
    ),
    (
        "tags",
        &["name", "color", "grp", "is_tracker", "tracker_type", "unit", "min", "max", "goal", "deleted_at"],
    ),
    ("entry_tags", &["entry_id", "tag_id", "value", "deleted_at"]),
    ("people", &["name", "contact_ref", "deleted_at"]),
    ("entry_people", &["entry_id", "person_id", "deleted_at"]),
    ("places", &["name", "address", "lat", "lng", "radius_m", "category", "deleted_at"]),
    (
        "attachments",
        &[
            "entry_id", "kind", "content_hash", "mime", "bytes", "width", "height", "duration_ms",
            "taken_at", "lat", "lng", "caption", "sort", "thumbhash", "origin_device_id", "deleted_at",
        ],
    ),
    (
        "templates",
        &["name", "journal_id", "body", "default_tags", "default_rating", "prompts", "schedule", "deleted_at"],
    ),
    ("day_meta", &["journal_id", "date", "kept_items", "deleted_at"]),
    ("synced_settings", &["value", "deleted_at"]),
];

pub fn synced_fields(table: &str) -> Option<&'static [&'static str]> {
    SYNCED.iter().find(|(t, _)| *t == table).map(|(_, f)| *f)
}

const MIGRATIONS: &[&str] = &[
    // 1: the journal model, sync bookkeeping and full-text search.
    r#"
    create table journals (
      id          text primary key,
      name        text not null,
      color       text,
      icon        text,
      sort        integer not null default 0,
      is_default  integer not null default 0,
      updated_at  text not null,
      deleted_at  text
    );

    create table entries (
      id           text primary key,
      journal_id   text not null,
      date         text not null,             -- local YYYY-MM-DD
      time         text,                      -- local HH:MM
      tz           text,
      title        text,
      body         text not null default '[]',-- block document JSON (doc.rs)
      body_text    text not null default '',  -- derived: plain text for FTS
      word_count   integer not null default 0,-- derived
      rating       real,
      place_id     text,
      template_id  text,
      is_favorite  integer not null default 0,
      weather      text,
      created_at   text not null,
      updated_at   text not null,
      deleted_at   text
    );
    create index entries_day on entries (date, time) where deleted_at is null;
    create index entries_journal_day on entries (journal_id, date, time) where deleted_at is null;

    create table tags (
      id            text primary key,
      name          text not null,
      color         text,
      grp           text,
      is_tracker    integer not null default 0,
      tracker_type  text,  -- count | number | duration | scale | boolean | text
      unit          text,
      min           real,
      max           real,
      goal          real,
      updated_at    text not null,
      deleted_at    text
    );

    -- id is "<entry_id>:<tag_id>" so two devices tagging the same entry
    -- with the same tag converge on one row.
    create table entry_tags (
      id          text primary key,
      entry_id    text not null,
      tag_id      text not null,
      value       text,
      updated_at  text not null,
      deleted_at  text
    );
    create index entry_tags_entry on entry_tags (entry_id) where deleted_at is null;
    create index entry_tags_tag on entry_tags (tag_id) where deleted_at is null;

    create table people (
      id           text primary key,
      name         text not null,
      contact_ref  text,
      updated_at   text not null,
      deleted_at   text
    );

    create table entry_people (
      id          text primary key,             -- "<entry_id>:<person_id>"
      entry_id    text not null,
      person_id   text not null,
      updated_at  text not null,
      deleted_at  text
    );
    create index entry_people_entry on entry_people (entry_id) where deleted_at is null;

    create table places (
      id          text primary key,
      name        text not null,
      address     text,
      lat         real,
      lng         real,
      radius_m    real,
      category    text,
      updated_at  text not null,
      deleted_at  text
    );

    create table attachments (
      id                text primary key,
      entry_id          text not null,
      kind              text not null,  -- photo | video | audio | file | drawing
      content_hash      text,
      mime              text,
      bytes             integer,
      width             integer,
      height            integer,
      duration_ms       integer,
      taken_at          text,
      lat               real,
      lng               real,
      caption           text,
      sort              integer not null default 0,
      thumbhash         text,           -- base64, ~25 bytes: the blurred preview other devices show
      origin_device_id  text,
      local_path        text,           -- device-local; never synced
      updated_at        text not null,
      deleted_at        text
    );
    create index attachments_entry on attachments (entry_id) where deleted_at is null;

    create table templates (
      id              text primary key,
      name            text not null,
      journal_id      text,
      body            text not null default '[]',
      default_tags    text,           -- JSON array of tag ids
      default_rating  real,
      prompts         text,           -- JSON array of strings
      schedule        text,           -- RRULE
      updated_at      text not null,
      deleted_at      text
    );

    create table day_meta (
      id          text primary key,     -- "<journal_id>:<date>"
      journal_id  text not null,
      date        text not null,
      kept_items  text,                 -- JSON
      updated_at  text not null,
      deleted_at  text
    );

    create table synced_settings (
      id          text primary key,     -- the setting key
      value       text,
      updated_at  text not null,
      deleted_at  text
    );

    create table device_settings (
      key    text primary key,
      value  text
    );

    create table revisions (
      id           text primary key,
      entry_id     text not null,
      body         text not null,
      hlc          text not null,
      device_id    text not null,
      is_conflict  integer not null default 0,
      created_at   text not null
    );
    create index revisions_entry on revisions (entry_id, created_at);

    -- Sync bookkeeping (SPEC.md §12.5) --------------------------------------

    -- The winning stamp for every synced field, for last-writer-wins.
    create table field_clocks (
      tbl     text not null,
      row_id  text not null,
      field   text not null,
      hlc     text not null,
      primary key (tbl, row_id, field)
    ) without rowid;

    -- Local changes not yet pushed, oldest first. One row per changed row.
    create table pending_ops (
      id  integer primary key autoincrement,
      op  text not null              -- JSON: {"t","id","f":{field: value},"h","d"}
    );

    create table sync_meta (
      key    text primary key,
      value  text not null
    );

    -- Full-text search over entries ----------------------------------------
    create virtual table entries_fts using fts5(
      title, body_text,
      content = 'entries', content_rowid = 'rowid',
      tokenize = 'unicode61 remove_diacritics 2',
      prefix = '2 3'
    );
    create trigger entries_fts_ai after insert on entries begin
      insert into entries_fts (rowid, title, body_text) values (new.rowid, new.title, new.body_text);
    end;
    create trigger entries_fts_ad after delete on entries begin
      insert into entries_fts (entries_fts, rowid, title, body_text) values ('delete', old.rowid, old.title, old.body_text);
    end;
    create trigger entries_fts_au after update of title, body_text on entries begin
      insert into entries_fts (entries_fts, rowid, title, body_text) values ('delete', old.rowid, old.title, old.body_text);
      insert into entries_fts (rowid, title, body_text) values (new.rowid, new.title, new.body_text);
    end;
    "#,
    // 2: where an imported entry came from ("dayone:<uuid>", "daylio:…"), so
    // importing the same file again, on any device, skips what's there.
    r#"
    alter table entries add column import_ref text;
    create unique index entries_import_ref on entries (import_ref) where import_ref is not null;
    "#,
    // 3: the last day of an entry that covers several (a trip, a festival).
    // `date` stays the first day, so lists and sync ordering are unchanged.
    r#"
    alter table entries add column end_date text;
    create index entries_end_date on entries (end_date) where end_date is not null;
    "#,
];

pub fn open(path: &str) -> Result<Connection> {
    let mut conn = Connection::open(path)?;
    conn.execute_batch(
        "pragma journal_mode = wal;
         pragma synchronous = normal;
         pragma foreign_keys = off;
         pragma temp_store = memory;",
    )?;
    migrate(&mut conn)?;
    Ok(conn)
}

fn migrate(conn: &mut Connection) -> Result<()> {
    let version: i64 = conn.query_row("pragma user_version", [], |r| r.get(0))?;
    let version = version as usize;
    for (i, sql) in MIGRATIONS.iter().enumerate().skip(version) {
        let tx = conn.transaction()?;
        tx.execute_batch(sql)?;
        tx.execute_batch(&format!("pragma user_version = {}", i + 1))?;
        tx.commit()?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn migrates_a_fresh_database_and_is_idempotent() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("t.sqlite");
        let path = path.to_str().unwrap();
        open(path).unwrap();
        let conn = open(path).unwrap();
        let v: i64 = conn.query_row("pragma user_version", [], |r| r.get(0)).unwrap();
        assert_eq!(v as usize, MIGRATIONS.len());
    }

    #[test]
    fn every_synced_column_exists() {
        let conn = Connection::open_in_memory().unwrap();
        for m in MIGRATIONS {
            conn.execute_batch(m).unwrap();
        }
        for (table, fields) in SYNCED {
            for f in *fields {
                conn.prepare(&format!("select {f} from {table}"))
                    .unwrap_or_else(|e| panic!("{table}.{f}: {e}"));
            }
        }
    }
}

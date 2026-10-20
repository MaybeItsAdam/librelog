//! Calendar events kept on a journal day (SPEC.md §11, docs/calendar.md).
//!
//! The apps read the device's own calendar (EventKit, CalendarContract) and
//! hand the day's events to [`Store::calendar_day`], which says which of them
//! are already kept and offers the rest as suggestions. Nothing is stored
//! until the user taps one: [`Store::keep_calendar_event`] writes one synced
//! row, and from then on it is tracked. Whenever an app sees that event again
//! — on that day's list, or by looking up every tracked event with
//! [`Store::refresh_calendar_events`] — the row takes the calendar's current
//! title, place and time. A changed time reads as *moved*, a cancelled or
//! vanished event as *cancelled*; neither is ever deleted, and the user's
//! note on it is never touched by a refresh.
//!
//! Rows sync like any other journal data (encrypted, per-field
//! last-writer-wins), and their id derives from the journal and the event's
//! key, so two devices keeping the same event converge on one row.

use std::collections::BTreeMap;

use rusqlite::{OptionalExtension, Row, params};
use serde_json::{Value, json};

use crate::error::{CoreError, Result};
use crate::store::{DEFAULT_JOURNAL, Store, name_id, now_rfc3339};

/// One event as the device's calendar has it now. Times are local wall-clock
/// time on the device: `YYYY-MM-DDTHH:MM` for a timed event, `YYYY-MM-DD`
/// for an all-day one.
#[derive(Debug, Clone, PartialEq, uniffi::Record, serde::Serialize, serde::Deserialize)]
pub struct CalendarEvent {
    /// The calendar's stable identity for this event, prefixed by where it
    /// came from: `apple:<external id>[@<occurrence>]`,
    /// `android:<event id>[@<original instance millis>]`. It must not change
    /// when the event is edited or moved; a recurring event's occurrences
    /// each get their own, from the time the occurrence was *originally* at.
    pub key: String,
    pub title: String,
    pub start: String,
    /// Timed: when it ends. All-day: the last day it covers, or none for a
    /// single day.
    pub end: Option<String>,
    pub location: Option<String>,
    /// The calendar's name ("Work", "Home"), shown as a caption.
    pub calendar: Option<String>,
    /// The calendar marks it cancelled.
    pub cancelled: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum KeptEventState {
    /// As it was when kept.
    Scheduled,
    /// Its time changed since it was kept; `start` / `end` are the new time.
    Moved,
    /// Cancelled in the calendar, or gone from it.
    Cancelled,
}

/// A calendar event the user kept on a day.
#[derive(Debug, Clone, PartialEq, uniffi::Record, serde::Serialize, serde::Deserialize)]
pub struct KeptEvent {
    pub id: String,
    pub journal_id: String,
    /// The journal day it is kept on. A moved event stays on this day.
    pub date: String,
    pub key: String,
    pub title: String,
    pub location: Option<String>,
    pub calendar: Option<String>,
    pub start: String,
    pub end: Option<String>,
    pub all_day: bool,
    /// "09:30–10:00", "09:30", or empty for an all-day event.
    pub time_label: String,
    pub state: KeptEventState,
    /// Moved to a different day than `date` (`start` says which).
    pub moved_to_another_day: bool,
    pub note: Option<String>,
    /// Added by hand rather than kept from a calendar (`own:` key): never
    /// tracked, and editable in the app.
    pub is_own: bool,
}

/// An event the user adds to a day by hand. Times are `HH:MM` on that day;
/// no start time makes it an all-day event.
#[derive(Debug, Clone, PartialEq, uniffi::Record, serde::Serialize, serde::Deserialize)]
pub struct OwnEvent {
    pub title: String,
    pub start_time: Option<String>,
    pub end_time: Option<String>,
    pub location: Option<String>,
}

/// Events the user adds by hand carry this key prefix. No calendar lookup
/// ever matches it, so they are never moved or cancelled by a refresh.
const OWN: &str = "own:";

/// What to show for one day: the events kept there, and the calendar's
/// other events that day to offer as suggestions.
#[derive(Debug, Clone, PartialEq, uniffi::Record, serde::Serialize, serde::Deserialize)]
pub struct CalendarDay {
    pub kept: Vec<KeptEvent>,
    pub suggestions: Vec<CalendarEvent>,
}

/// The result of looking one tracked event up in the device's calendar.
#[derive(Debug, Clone, PartialEq, uniffi::Record, serde::Serialize, serde::Deserialize)]
pub struct CalendarLookup {
    pub key: String,
    /// What the calendar has now, or none if the event wasn't found.
    pub event: Option<CalendarEvent>,
}

#[uniffi::export]
impl Store {
    /// The day's kept events, and suggestions from `events` (the device's
    /// calendar for that day, already fetched by the app). Kept events seen
    /// in `events` are brought up to date on the way. Returns the kept ones
    /// first; suggestions leave out cancelled events and any already kept.
    pub fn calendar_day(&self, journal_id: Option<String>, date: String, events: Vec<CalendarEvent>) -> Result<CalendarDay> {
        check_day(&date)?;
        let journal = journal_id.unwrap_or_else(|| DEFAULT_JOURNAL.to_string());
        let found: Vec<CalendarLookup> =
            events.iter().map(|e| CalendarLookup { key: e.key.clone(), event: Some(e.clone()) }).collect();
        self.refresh_calendar_events(found)?;

        let kept = self.kept_calendar_events(Some(journal.clone()), date)?;
        let tracked = self.tracked_keys(&journal)?;
        let mut suggestions: Vec<CalendarEvent> =
            events.into_iter().filter(|e| !e.cancelled && !tracked.contains(&e.key) && check_event(e).is_ok()).collect();
        suggestions.sort_by(|a, b| order(&a.start).cmp(&order(&b.start)).then_with(|| a.title.cmp(&b.title)));
        suggestions.dedup_by(|a, b| a.key == b.key);
        Ok(CalendarDay { kept, suggestions })
    }

    /// Events kept on one day, for showing without asking the calendar (a
    /// device without calendar access, or before permission is granted).
    pub fn kept_calendar_events(&self, journal_id: Option<String>, date: String) -> Result<Vec<KeptEvent>> {
        let journal = journal_id.unwrap_or_else(|| DEFAULT_JOURNAL.to_string());
        let g = self.lock();
        let mut stmt = g.conn.prepare_cached(&format!(
            "select {COLS} from calendar_events
             where journal_id = ?1 and date = ?2 and deleted_at is null"
        ))?;
        let mut out: Vec<KeptEvent> =
            stmt.query_map(params![journal, date], kept_from_row)?.collect::<std::result::Result<_, _>>()?;
        out.sort_by(|a, b| order(&a.start).cmp(&order(&b.start)).then_with(|| a.title.cmp(&b.title)));
        Ok(out)
    }

    /// Keeps a calendar event on a journal day, and tracks it from then on.
    /// Keeping one that was kept and then removed brings it back, note and all.
    pub fn keep_calendar_event(&self, journal_id: Option<String>, date: String, event: CalendarEvent) -> Result<KeptEvent> {
        check_day(&date)?;
        check_event(&event)?;
        let journal = journal_id.unwrap_or_else(|| DEFAULT_JOURNAL.to_string());
        let id = event_id(&journal, &event.key);
        let existing = self.kept_row(&id)?;
        let mut f = json!({
            "journal_id": journal, "date": date, "event_key": event.key,
            "title": event.title.trim(), "location": tidy(event.location), "calendar": tidy(event.calendar),
            "start": event.start, "end_at": event.end, "kept_start": event.start, "kept_end": event.end,
            "cancelled": event.cancelled, "origin_device_id": self.device_id(), "deleted_at": null,
        });
        if existing.is_none() {
            f["created_at"] = json!(now_rfc3339());
        }
        self.write("calendar_events", &id, f)?;
        self.kept_row(&id)?.ok_or_else(|| CoreError::NotFound(format!("calendar event {id}")))
    }

    /// Adds an event of the user's own to a day, past or future: something
    /// that was never in a calendar, or a calendar this device can't read.
    pub fn add_own_event(&self, journal_id: Option<String>, date: String, event: OwnEvent) -> Result<KeptEvent> {
        check_day(&date)?;
        let (start, end) = own_times(&date, &event)?;
        let journal = journal_id.unwrap_or_else(|| DEFAULT_JOURNAL.to_string());
        let key = format!("{OWN}{}", uuid::Uuid::now_v7());
        let id = event_id(&journal, &key);
        self.write(
            "calendar_events",
            &id,
            json!({
                "journal_id": journal, "date": date, "event_key": key, "title": event.title.trim(),
                "location": tidy(event.location), "calendar": null, "start": start, "end_at": end,
                "kept_start": start, "kept_end": end, "cancelled": false,
                "origin_device_id": self.device_id(), "created_at": now_rfc3339(), "deleted_at": null,
            }),
        )?;
        self.kept_row(&id)?.ok_or_else(|| CoreError::NotFound(format!("calendar event {id}")))
    }

    /// Changes an event the user added by hand. Kept calendar events follow
    /// their calendar instead, so they can't be edited here.
    pub fn update_own_event(&self, id: String, event: OwnEvent) -> Result<KeptEvent> {
        let row = self.kept_row(&id)?.ok_or_else(|| CoreError::NotFound(format!("calendar event {id}")))?;
        if !row.is_own {
            return Err(CoreError::Invalid("only events added by hand can be edited".into()));
        }
        let (start, end) = own_times(&row.date, &event)?;
        self.write(
            "calendar_events",
            &id,
            json!({
                "title": event.title.trim(), "location": tidy(event.location),
                "start": start, "end_at": end, "kept_start": start, "kept_end": end,
            }),
        )?;
        self.kept_row(&id)?.ok_or_else(|| CoreError::NotFound(format!("calendar event {id}")))
    }

    /// Stops tracking a kept event and takes it off the day.
    pub fn remove_calendar_event(&self, id: String) -> Result<()> {
        self.kept_row(&id)?.ok_or_else(|| CoreError::NotFound(format!("calendar event {id}")))?;
        self.write("calendar_events", &id, json!({ "deleted_at": now_rfc3339() }))
    }

    /// The user's own note on a kept event; empty clears it.
    pub fn set_calendar_event_note(&self, id: String, note: Option<String>) -> Result<()> {
        self.kept_row(&id)?.ok_or_else(|| CoreError::NotFound(format!("calendar event {id}")))?;
        let note = note.map(|n| n.trim().to_string()).filter(|n| !n.is_empty());
        self.write("calendar_events", &id, json!({ "note": note }))
    }

    /// Every kept event whose key starts with `key_prefix` ("apple:",
    /// "android:"), for the app to look up and pass to
    /// `refresh_calendar_events`.
    pub fn tracked_calendar_events(&self, key_prefix: String) -> Result<Vec<KeptEvent>> {
        let g = self.lock();
        let mut stmt = g.conn.prepare_cached(&format!(
            "select {COLS} from calendar_events
             where deleted_at is null and substr(event_key, 1, length(?1)) = ?1
             order by date desc"
        ))?;
        let rows = stmt.query_map([key_prefix], kept_from_row)?;
        Ok(rows.collect::<std::result::Result<_, _>>()?)
    }

    /// Brings kept events up to date with what the calendar has now. An
    /// event that wasn't found counts as cancelled only on the device that
    /// kept it, since another device may simply not have that calendar.
    /// Returns how many kept events changed.
    pub fn refresh_calendar_events(&self, lookups: Vec<CalendarLookup>) -> Result<u32> {
        let me = self.device_id();
        let mut writes = Vec::new();
        // Own events have no calendar to follow, whatever an app passes in.
        for lookup in lookups.into_iter().filter(|l| !key_is_own(&l.key)) {
            let rows = self.kept_rows_for_key(&lookup.key)?;
            for row in rows {
                let f = match &lookup.event {
                    Some(seen) if check_event(seen).is_ok() => diff(&row, seen),
                    Some(_) => BTreeMap::new(),
                    None if row.origin_device_id.as_deref() == Some(me.as_str()) && !row.cancelled => {
                        BTreeMap::from([("cancelled".to_string(), json!(true))])
                    }
                    None => BTreeMap::new(),
                };
                if !f.is_empty() {
                    writes.push(("calendar_events", row.id.clone(), Value::Object(f.into_iter().collect())));
                }
            }
        }
        let n = writes.len() as u32;
        if n > 0 {
            self.write_all(writes)?;
        }
        Ok(n)
    }
}

impl Store {
    fn kept_row(&self, id: &str) -> Result<Option<KeptEvent>> {
        let g = self.lock();
        Ok(g.conn
            .query_row(
                &format!("select {COLS} from calendar_events where id = ?1 and deleted_at is null"),
                [id],
                kept_from_row,
            )
            .optional()?)
    }

    fn kept_rows_for_key(&self, key: &str) -> Result<Vec<Stored>> {
        let g = self.lock();
        let mut stmt = g.conn.prepare_cached(
            "select id, title, location, calendar, start, end_at, cancelled, origin_device_id
             from calendar_events where event_key = ?1 and deleted_at is null",
        )?;
        let rows = stmt.query_map([key], |r| {
            Ok(Stored {
                id: r.get(0)?,
                title: r.get(1)?,
                location: r.get(2)?,
                calendar: r.get(3)?,
                start: r.get(4)?,
                end: r.get(5)?,
                cancelled: r.get(6)?,
                origin_device_id: r.get(7)?,
            })
        })?;
        Ok(rows.collect::<std::result::Result<_, _>>()?)
    }

    fn tracked_keys(&self, journal: &str) -> Result<Vec<String>> {
        let g = self.lock();
        let mut stmt = g
            .conn
            .prepare_cached("select event_key from calendar_events where journal_id = ?1 and deleted_at is null")?;
        let rows = stmt.query_map([journal], |r| r.get(0))?;
        Ok(rows.collect::<std::result::Result<_, _>>()?)
    }
}

/// The parts of a kept row a refresh may change.
#[derive(Debug, Clone, PartialEq)]
struct Stored {
    id: String,
    title: String,
    location: Option<String>,
    calendar: Option<String>,
    start: String,
    end: Option<String>,
    cancelled: bool,
    origin_device_id: Option<String>,
}

/// The fields to write so a kept row matches what the calendar has now:
/// only those that differ, so refreshing an unchanged event syncs nothing.
/// Never the note, the day it's kept on, or the time it was kept at.
fn diff(row: &Stored, seen: &CalendarEvent) -> BTreeMap<String, Value> {
    let mut f = BTreeMap::new();
    let title = seen.title.trim();
    if row.title != title {
        f.insert("title".into(), json!(title));
    }
    let location = tidy(seen.location.clone());
    if row.location != location {
        f.insert("location".into(), json!(location));
    }
    // Some calendars leave the name out of a lookup; don't blank it then.
    let calendar = tidy(seen.calendar.clone());
    if calendar.is_some() && row.calendar != calendar {
        f.insert("calendar".into(), json!(calendar));
    }
    if row.start != seen.start {
        f.insert("start".into(), json!(seen.start));
    }
    if row.end != seen.end {
        f.insert("end_at".into(), json!(seen.end));
    }
    if row.cancelled != seen.cancelled {
        f.insert("cancelled".into(), json!(seen.cancelled));
    }
    f
}

const COLS: &str = "id, journal_id, date, event_key, title, location, calendar, start, end_at, kept_start, kept_end, cancelled, note";

fn kept_from_row(r: &Row) -> rusqlite::Result<KeptEvent> {
    let date: String = r.get(2)?;
    let start: String = r.get(7)?;
    let end: Option<String> = r.get(8)?;
    let kept_start: String = r.get(9)?;
    let kept_end: Option<String> = r.get(10)?;
    let cancelled: bool = r.get(11)?;
    let state = if cancelled {
        KeptEventState::Cancelled
    } else if start != kept_start || end != kept_end {
        KeptEventState::Moved
    } else {
        KeptEventState::Scheduled
    };
    Ok(KeptEvent {
        id: r.get(0)?,
        journal_id: r.get(1)?,
        moved_to_another_day: state == KeptEventState::Moved && start.get(..10) != Some(date.as_str()),
        date,
        key: r.get(3)?,
        title: r.get(4)?,
        location: r.get(5)?,
        calendar: r.get(6)?,
        all_day: is_all_day(&start),
        time_label: time_label(&start, end.as_deref()),
        start,
        end,
        state,
        note: r.get(12)?,
        is_own: key_is_own(&r.get::<_, String>(3)?),
    })
}

fn key_is_own(key: &str) -> bool {
    key.starts_with(OWN)
}

/// Start and end in the stored forms, from an own event on `date`.
fn own_times(date: &str, e: &OwnEvent) -> Result<(String, Option<String>)> {
    if e.title.trim().is_empty() {
        return Err(CoreError::Invalid("an event needs a title".into()));
    }
    let tidy = |t: &Option<String>| t.as_deref().map(str::trim).filter(|t| !t.is_empty()).map(str::to_string);
    let (start, end) = (tidy(&e.start_time), tidy(&e.end_time));
    let Some(start) = start else {
        if end.is_some() {
            return Err(CoreError::Invalid("an end time needs a start time".into()));
        }
        return Ok((date.to_string(), None));
    };
    let start = format!("{date}T{start}");
    check_moment(&start)?;
    let end = match end {
        Some(end) => {
            let end = format!("{date}T{end}");
            check_moment(&end)?;
            if end <= start {
                return Err(CoreError::Invalid("the event ends before it starts".into()));
            }
            Some(end)
        }
        None => None,
    };
    Ok((start, end))
}

/// One row per journal and event, the same on every device.
fn event_id(journal: &str, key: &str) -> String {
    name_id("calendar_event", &format!("{journal}:{key}"))
}

fn is_all_day(start: &str) -> bool {
    start.len() == 10
}

/// All-day events sort before timed ones on the same day.
fn order(start: &str) -> String {
    if is_all_day(start) { format!("{start}T") } else { start.to_string() }
}

/// "09:30–10:00" when it ends the same day, "09:30" otherwise, "" all day.
fn time_label(start: &str, end: Option<&str>) -> String {
    if is_all_day(start) {
        return String::new();
    }
    let from = &start[11..16];
    match end {
        Some(e) if e.len() == 16 && e[..10] == start[..10] && &e[11..16] != from => format!("{from}–{}", &e[11..16]),
        _ => from.to_string(),
    }
}

fn tidy(s: Option<String>) -> Option<String> {
    s.map(|s| s.trim().to_string()).filter(|s| !s.is_empty())
}

fn check_day(d: &str) -> Result<()> {
    let fmt = time::macros::format_description!("[year]-[month]-[day]");
    time::Date::parse(d, &fmt).map(|_| ()).map_err(|_| CoreError::Invalid(format!("date {d}: want YYYY-MM-DD")))
}

fn check_moment(s: &str) -> Result<()> {
    let fmt = time::macros::format_description!("[year]-[month]-[day]T[hour]:[minute]");
    match s.len() {
        10 => check_day(s),
        16 => time::PrimitiveDateTime::parse(s, &fmt)
            .map(|_| ())
            .map_err(|_| CoreError::Invalid(format!("time {s}: want YYYY-MM-DDTHH:MM"))),
        _ => Err(CoreError::Invalid(format!("{s}: want YYYY-MM-DD or YYYY-MM-DDTHH:MM"))),
    }
}

fn check_event(e: &CalendarEvent) -> Result<()> {
    if e.key.trim().is_empty() {
        return Err(CoreError::Invalid("calendar event without a key".into()));
    }
    check_moment(&e.start)?;
    if let Some(end) = &e.end {
        check_moment(end)?;
        if is_all_day(end) != is_all_day(&e.start) {
            return Err(CoreError::Invalid(format!("{} and {end} aren't the same kind of time", e.start)));
        }
    }
    Ok(())
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

    fn ev(key: &str, title: &str, start: &str, end: Option<&str>) -> CalendarEvent {
        CalendarEvent {
            key: key.into(),
            title: title.into(),
            start: start.into(),
            end: end.map(Into::into),
            location: None,
            calendar: Some("Work".into()),
            cancelled: false,
        }
    }

    const DAY: &str = "2026-10-17";

    fn standup() -> CalendarEvent {
        ev("apple:abc", "Standup", "2026-10-17T09:30", Some("2026-10-17T09:45"))
    }

    fn own(title: &str, start: Option<&str>, end: Option<&str>) -> OwnEvent {
        OwnEvent { title: title.into(), start_time: start.map(Into::into), end_time: end.map(Into::into), location: None }
    }

    #[test]
    fn own_events_go_on_any_day_and_are_never_tracked() {
        let s = store();
        let past = "2019-06-01".to_string();
        let e = s.add_own_event(None, past.clone(), own("Graduation", Some("14:00"), Some("16:30"))).unwrap();
        assert!(e.is_own);
        assert_eq!((e.start.as_str(), e.end.as_deref()), ("2019-06-01T14:00", Some("2019-06-01T16:30")));
        assert_eq!(e.state, KeptEventState::Scheduled);

        let all_day = s.add_own_event(None, past.clone(), own("Moving day", None, None)).unwrap();
        assert!(all_day.all_day);

        assert!(s.add_own_event(None, past.clone(), own("  ", None, None)).is_err());
        assert!(s.add_own_event(None, past.clone(), own("Late", Some("16:00"), Some("15:00"))).is_err());
        assert!(s.add_own_event(None, past.clone(), own("Odd", None, Some("15:00"))).is_err());

        // A refresh from the calendar never touches them, and they're not offered as tracked.
        assert_eq!(s.refresh_calendar_events(vec![CalendarLookup { key: e.key.clone(), event: None }]).unwrap(), 0);
        assert!(s.tracked_calendar_events("apple:".into()).unwrap().is_empty());
        let day = s.calendar_day(None, past.clone(), vec![]).unwrap();
        assert_eq!(day.kept.len(), 2);
        assert!(day.kept.iter().all(|k| k.state == KeptEventState::Scheduled));

        let moved = s.update_own_event(e.id.clone(), own("Graduation day", Some("13:00"), None)).unwrap();
        assert_eq!((moved.title.as_str(), moved.start.as_str(), moved.state), ("Graduation day", "2019-06-01T13:00", KeptEventState::Scheduled));

        let kept = s.keep_calendar_event(None, "2026-10-03".into(), ev("apple:x", "Standup", "2026-10-03T09:30", None)).unwrap();
        assert!(!kept.is_own);
        assert!(s.update_own_event(kept.id, own("Nope", None, None)).is_err());
    }

    #[test]
    fn suggestions_until_kept_then_kept_and_nothing_else_stored() {
        let s = store();
        let events = vec![
            ev("apple:lunch", "Lunch", "2026-10-17T12:30", Some("2026-10-17T13:30")),
            standup(),
            ev("apple:hol", "Holiday", DAY, None),
            CalendarEvent { cancelled: true, ..ev("apple:x", "Called off", "2026-10-17T15:00", None) },
        ];
        let day = s.calendar_day(None, DAY.into(), events.clone()).unwrap();
        assert!(day.kept.is_empty());
        let titles: Vec<_> = day.suggestions.iter().map(|e| e.title.as_str()).collect();
        assert_eq!(titles, ["Holiday", "Standup", "Lunch"], "all-day first, cancelled left out");
        let stored: i64 = s.lock().conn.query_row("select count(*) from calendar_events", [], |r| r.get(0)).unwrap();
        assert_eq!(stored, 0, "looking stores nothing");

        let kept = s.keep_calendar_event(None, DAY.into(), standup()).unwrap();
        assert_eq!((kept.state, kept.time_label.as_str(), kept.all_day), (KeptEventState::Scheduled, "09:30–09:45", false));
        let day = s.calendar_day(None, DAY.into(), events).unwrap();
        assert_eq!(day.kept.len(), 1);
        assert_eq!(day.suggestions.iter().map(|e| e.title.as_str()).collect::<Vec<_>>(), ["Holiday", "Lunch"]);
    }

    #[test]
    fn renamed_and_moved_updates_but_keeps_the_note_and_the_day() {
        let s = store();
        let kept = s.keep_calendar_event(None, DAY.into(), standup()).unwrap();
        s.set_calendar_event_note(kept.id.clone(), Some("  Talked about the launch ".into())).unwrap();

        let moved = CalendarEvent {
            title: "Team standup".into(),
            start: "2026-10-18T10:00".into(),
            end: Some("2026-10-18T10:15".into()),
            location: Some("Room 2".into()),
            ..standup()
        };
        assert_eq!(s.refresh_calendar_events(vec![CalendarLookup { key: "apple:abc".into(), event: Some(moved) }]).unwrap(), 1);
        let e = &s.kept_calendar_events(None, DAY.into()).unwrap()[0];
        assert_eq!(e.title, "Team standup");
        assert_eq!(e.location.as_deref(), Some("Room 2"));
        assert_eq!((e.state, e.moved_to_another_day), (KeptEventState::Moved, true));
        assert_eq!(e.note.as_deref(), Some("Talked about the launch"));
        assert_eq!(e.date, DAY);

        // Moved back: scheduled again. Seeing it unchanged writes nothing.
        let back = CalendarEvent { title: "Team standup".into(), location: Some("Room 2".into()), ..standup() };
        let lookup = vec![CalendarLookup { key: "apple:abc".into(), event: Some(back) }];
        assert_eq!(s.refresh_calendar_events(lookup.clone()).unwrap(), 1);
        assert_eq!(s.kept_calendar_events(None, DAY.into()).unwrap()[0].state, KeptEventState::Scheduled);
        let before = s.pending_count().unwrap();
        assert_eq!(s.refresh_calendar_events(lookup).unwrap(), 0);
        assert_eq!(s.pending_count().unwrap(), before, "an unchanged event queues no ops");
    }

    #[test]
    fn cancelled_or_missing_marks_it_cancelled_and_never_deletes() {
        let s = store();
        s.keep_calendar_event(None, DAY.into(), standup()).unwrap();
        let cancelled = CalendarEvent { cancelled: true, ..standup() };
        s.refresh_calendar_events(vec![CalendarLookup { key: "apple:abc".into(), event: Some(cancelled) }]).unwrap();
        assert_eq!(s.kept_calendar_events(None, DAY.into()).unwrap()[0].state, KeptEventState::Cancelled);

        // Reinstated in the calendar.
        s.refresh_calendar_events(vec![CalendarLookup { key: "apple:abc".into(), event: Some(standup()) }]).unwrap();
        assert_eq!(s.kept_calendar_events(None, DAY.into()).unwrap()[0].state, KeptEventState::Scheduled);

        // Gone from the calendar of the device that kept it.
        s.refresh_calendar_events(vec![CalendarLookup { key: "apple:abc".into(), event: None }]).unwrap();
        let e = s.kept_calendar_events(None, DAY.into()).unwrap();
        assert_eq!(e.len(), 1);
        assert_eq!(e[0].state, KeptEventState::Cancelled);
    }

    #[test]
    fn missing_on_another_device_is_not_a_cancellation() {
        let (a, b) = (store(), store());
        let key = a.generate_journal_key().unwrap();
        a.set_journal_key(key.clone()).unwrap();
        b.set_journal_key(key).unwrap();

        let kept = a.keep_calendar_event(None, DAY.into(), standup()).unwrap();
        a.set_calendar_event_note(kept.id.clone(), Some("Mine".into())).unwrap();
        let mut log = Vec::new();
        while let Some(batch) = a.outgoing_batch(100).unwrap() {
            log.push(IncomingOps { seq: log.len() as i64 + 1, device_id: a.device_id(), payload: batch.payload });
            a.push_succeeded(batch.batch_id).unwrap();
        }
        b.apply_incoming(log).unwrap();

        let on_b = b.kept_calendar_events(None, DAY.into()).unwrap();
        assert_eq!(on_b.len(), 1);
        assert_eq!(on_b[0].note.as_deref(), Some("Mine"));
        assert_eq!(b.tracked_calendar_events("apple:".into()).unwrap().len(), 1);
        assert!(b.tracked_calendar_events("android:".into()).unwrap().is_empty());

        assert_eq!(b.refresh_calendar_events(vec![CalendarLookup { key: "apple:abc".into(), event: None }]).unwrap(), 0);
        assert_eq!(b.kept_calendar_events(None, DAY.into()).unwrap()[0].state, KeptEventState::Scheduled);
    }

    #[test]
    fn two_devices_keeping_the_same_event_make_one_row() {
        let (a, b) = (store(), store());
        let x = a.keep_calendar_event(None, DAY.into(), standup()).unwrap();
        let y = b.keep_calendar_event(None, DAY.into(), standup()).unwrap();
        assert_eq!(x.id, y.id);
    }

    #[test]
    fn removed_comes_back_with_its_note_when_kept_again() {
        let s = store();
        let kept = s.keep_calendar_event(None, DAY.into(), standup()).unwrap();
        s.set_calendar_event_note(kept.id.clone(), Some("Notes".into())).unwrap();
        s.remove_calendar_event(kept.id.clone()).unwrap();
        let day = s.calendar_day(None, DAY.into(), vec![standup()]).unwrap();
        assert!(day.kept.is_empty());
        assert_eq!(day.suggestions.len(), 1);
        let again = s.keep_calendar_event(None, DAY.into(), standup()).unwrap();
        assert_eq!(again.note.as_deref(), Some("Notes"));
    }

    #[test]
    fn labels_and_validation() {
        assert_eq!(time_label("2026-10-17T09:30", Some("2026-10-17T10:00")), "09:30–10:00");
        assert_eq!(time_label("2026-10-17T23:00", Some("2026-10-18T01:00")), "23:00");
        assert_eq!(time_label("2026-10-17T09:30", None), "09:30");
        assert_eq!(time_label("2026-10-17", Some("2026-10-19")), "");
        let s = store();
        for bad in [ev("", "x", DAY, None), ev("k", "x", "17/10/2026", None), ev("k", "x", DAY, Some("2026-10-18T10:00"))] {
            assert!(matches!(s.keep_calendar_event(None, DAY.into(), bad), Err(CoreError::Invalid(_))));
        }
        let all_day = s.keep_calendar_event(None, DAY.into(), ev("k", "Trip", DAY, Some("2026-10-19"))).unwrap();
        assert!(all_day.all_day && all_day.time_label.is_empty());
    }
}

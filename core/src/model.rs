//! Records handed to the apps. These are view models: small, flat, and shaped
//! for one screen each, so no UI ever loads more than it shows.

use crate::doc::Block;

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Journal {
    pub id: String,
    pub name: String,
    pub color: Option<String>,
    pub is_default: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Entry {
    pub id: String,
    pub journal_id: String,
    /// Local calendar date, `YYYY-MM-DD`.
    pub date: String,
    /// Local time, `HH:MM`, or none for an untimed entry.
    pub time: Option<String>,
    pub title: Option<String>,
    pub body: Vec<Block>,
    pub word_count: u32,
    pub rating: Option<f64>,
    pub is_favorite: bool,
    pub tags: Vec<Tag>,
    pub has_conflict: bool,
    pub updated_at: String,
}

/// One row in Timeline, Search, On This Day and the day view.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct EntrySummary {
    pub id: String,
    pub journal_id: String,
    pub date: String,
    pub time: Option<String>,
    pub title: Option<String>,
    pub excerpt: String,
    pub word_count: u32,
    pub rating: Option<f64>,
    pub is_favorite: bool,
    pub tag_names: Vec<String>,
}

/// One day cell in the Calendar.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct DayMark {
    pub date: String,
    pub entry_count: u32,
    pub rating: Option<f64>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Tag {
    pub id: String,
    pub name: String,
    pub color: Option<String>,
    pub is_tracker: bool,
    /// The tracker value on this entry, when the tag came from an entry.
    pub value: Option<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SearchHit {
    pub entry: EntrySummary,
    /// Snippet with matches wrapped in U+E000 / U+E001, which the UI swaps
    /// for its own highlight style. Private-use characters can't collide with
    /// anything a user typed.
    pub snippet: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Stats {
    pub entries: u32,
    pub words: u64,
    pub days_written: u32,
    pub current_streak: u32,
    pub longest_streak: u32,
    pub average_rating: Option<f64>,
}

/// Filters for Timeline. Empty / none means "don't filter on this".
#[derive(Debug, Clone, Default, PartialEq, uniffi::Record)]
pub struct TimelineFilter {
    pub journal_id: Option<String>,
    pub tag_ids: Vec<String>,
    pub min_rating: Option<f64>,
    pub favorites_only: bool,
}

/// Where Timeline paging continues from: the last row already shown.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TimelineCursor {
    pub date: String,
    pub time: Option<String>,
    pub id: String,
}

/// A batch of local changes ready for `push_ops`. `payload` is already
/// encrypted and base64-encoded; the app sends it as-is and calls
/// `push_succeeded` with the same `batch_id` once the server accepts it.
/// Retrying after a failure returns the same batch with the same id, so the
/// server's idempotency check makes a retried push harmless.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct OutgoingBatch {
    pub batch_id: String,
    pub payload: String,
    pub op_count: u32,
}

/// One row from `pull_ops`, as the server returned it.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct IncomingOps {
    pub seq: i64,
    pub device_id: String,
    pub payload: String,
}

#[derive(Debug, Clone, Default, PartialEq, uniffi::Record)]
pub struct PullResult {
    /// Rows merged into the local database.
    pub changed: u32,
    /// Entries where a concurrent edit was kept as a conflict revision.
    pub conflicts: Vec<String>,
    /// The cursor to pass as `p_after` next time.
    pub cursor: i64,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Revision {
    pub id: String,
    pub body: Vec<Block>,
    pub device_id: String,
    pub is_conflict: bool,
    pub created_at: String,
}

/// Full sealed state for `put_snapshot`, covering every op up to `seq`.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct OutgoingSnapshot {
    pub seq: i64,
    pub payload: String,
}

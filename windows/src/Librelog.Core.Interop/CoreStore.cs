using System.Text.Json;
using System.Text.Json.Nodes;

namespace Librelog.Core;

/// The journal database (core/src/store.rs). One per app. Calls are
/// synchronous and fast; the UI calls them off the UI thread. Thread-safe:
/// the core serialises access internally.
///
/// Journal keys and pairing public keys are base64 strings here.
public sealed class CoreStore : IDisposable
{
    private nuint handle;

    private CoreStore(nuint handle) => this.handle = handle;

    public static CoreStore Open(string path) =>
        new((nuint)Native.Unwrap(Native.StoreOpen(path)).GetUInt64());

    public void Dispose()
    {
        if (handle != 0) Native.StoreClose(handle);
        handle = 0;
    }

    // --- Journals ---------------------------------------------------------------

    public string DeviceId() => Call("device_id").GetString()!;
    public List<Journal> Journals() => Get("journals", CoreJson.Default.ListJournal)!;
    public Journal CreateJournal(string name, string? color = null) =>
        Get("create_journal", CoreJson.Default.Journal, ("name", name), ("color", color))!;
    public string DefaultJournalId() => Call("default_journal_id").GetString()!;

    // --- Entries ----------------------------------------------------------------

    public Entry CreateEntry(string date, string? time, string? journalId = null) =>
        Get("create_entry", CoreJson.Default.Entry, ("journal_id", journalId), ("date", date), ("time", time))!;
    public Entry Entry(string id) => Get("entry", CoreJson.Default.Entry, ("id", id))!;
    public uint SaveBody(string id, IReadOnlyList<Block> body) =>
        Call("save_body", ("id", id), ("body", Native.Node(body.ToList(), CoreJson.Default.ListBlock))).GetUInt32();
    public void SetTitle(string id, string? title) => Call("set_title", ("id", id), ("title", title));
    public void SetRating(string id, double? rating) => Call("set_rating", ("id", id), ("rating", rating));
    public void SetFavorite(string id, bool favorite) => Call("set_favorite", ("id", id), ("favorite", favorite));
    public void SetDateTime(string id, string date, string? time) => Call("set_date_time", ("id", id), ("date", date), ("time", time));
    /// The last day of a multi-day entry; null (or a day not after the
    /// start) makes it a single day again.
    public void SetEndDate(string id, string? endDate) => Call("set_end_date", ("id", id), ("end_date", endDate));
    public void MoveEntry(string id, string journalId) => Call("move_entry", ("id", id), ("journal_id", journalId));
    public void DeleteEntry(string id) => Call("delete_entry", ("id", id));
    public void RestoreEntry(string id) => Call("restore_entry", ("id", id));

    /// Deletes the entry if nothing was ever put in it (no title, text,
    /// rating, favourite, tags or attachments). True if it went.
    public bool DiscardIfEmpty(string id) => Call("discard_if_empty", ("id", id)).GetBoolean();

    // --- Reading ----------------------------------------------------------------

    public List<EntrySummary> Day(string date, string? journalId = null) =>
        Get("day", CoreJson.Default.ListEntrySummary, ("date", date), ("journal_id", journalId))!;
    public List<EntrySummary> Timeline(TimelineFilter? filter, TimelineCursor? after, uint limit) =>
        Get("timeline", CoreJson.Default.ListEntrySummary,
            ("filter", filter is null ? null : Native.Node(filter, CoreJson.Default.TimelineFilter)),
            ("after", after is null ? null : Native.Node(after, CoreJson.Default.TimelineCursor)),
            ("limit", limit))!;
    public List<DayMark> Month(string yearMonth, string? journalId = null) =>
        Get("month", CoreJson.Default.ListDayMark, ("year_month", yearMonth), ("journal_id", journalId))!;
    public List<EntrySummary> OnThisDay(string date) => Get("on_this_day", CoreJson.Default.ListEntrySummary, ("date", date))!;
    public List<SearchHit> Search(string query, uint limit) =>
        Get("search", CoreJson.Default.ListSearchHit, ("query", query), ("limit", limit))!;
    public Stats Stats(string today) => Get("stats", CoreJson.Default.Stats, ("today", today))!;

    // --- Tags -------------------------------------------------------------------

    public List<Tag> Tags() => Get("tags", CoreJson.Default.ListTag)!;
    public Tag TagNamed(string name) => Get("tag_named", CoreJson.Default.Tag, ("name", name))!;
    public void TagEntry(string entryId, string tagId, string? value = null) =>
        Call("tag_entry", ("entry_id", entryId), ("tag_id", tagId), ("value", value));
    public void UntagEntry(string entryId, string tagId) => Call("untag_entry", ("entry_id", entryId), ("tag_id", tagId));

    // --- Photos of the day (docs/photos.md) --------------------------------------

    public DayPhoto AddDayPhoto(string date, NewPhoto photo, string? journalId = null) =>
        Get("add_day_photo", CoreJson.Default.DayPhoto,
            ("journal_id", journalId), ("date", date), ("photo", Native.Node(photo, CoreJson.Default.NewPhoto)))!;
    public List<DayPhoto> DayPhotos(string date, string? journalId = null) =>
        Get("day_photos", CoreJson.Default.ListDayPhoto, ("journal_id", journalId), ("date", date))!;
    public void RemoveDayPhoto(string id) => Call("remove_day_photo", ("id", id));
    /// Empty or null clears it.
    public void SetPhotoCaption(string id, string? caption) => Call("set_photo_caption", ("id", id), ("caption", caption));
    /// Chooses the photo that stands for a day in the journal, or none.
    public void SetDayPreview(string date, PreviewChoice choice, string? journalId = null) =>
        Call("set_day_preview", ("journal_id", journalId), ("date", date), ("choice", Native.Node(choice, CoreJson.Default.PreviewChoice)));
    public PreviewChoice DayPreviewChoice(string date, string? journalId = null) =>
        Get("day_preview_choice", CoreJson.Default.PreviewChoice, ("journal_id", journalId), ("date", date))!;
    /// The photo standing for each of `dates` that has one: the chosen one,
    /// else the day's first. One call for a page of the journal.
    public List<DayPhoto> DayPreviews(IEnumerable<string> dates, string? journalId = null) =>
        Get("day_previews", CoreJson.Default.ListDayPhoto,
            ("journal_id", journalId), ("dates", new JsonArray(dates.Distinct().Select(d => (JsonNode?)d).ToArray())))!;
    public ThumbImage ThumbhashImage(string thumbhash) =>
        Get("thumbhash_image", CoreJson.Default.ThumbImage, ("thumbhash", thumbhash))!;

    // --- Calendar events kept on a day (docs/calendar.md) --------------------------

    /// The events kept on a day: kept from a calendar on some device, or
    /// added by hand (IsOwn).
    public List<KeptEvent> KeptCalendarEvents(string date, string? journalId = null) =>
        Get("kept_calendar_events", CoreJson.Default.ListKeptEvent, ("journal_id", journalId), ("date", date))!;
    /// "Remove from this day": a synced tombstone.
    public void RemoveCalendarEvent(string id) => Call("remove_calendar_event", ("id", id));
    /// An event of the user's own on any day. No times means all day; an
    /// invalid title or time is an "invalid" error.
    public KeptEvent AddOwnEvent(string date, OwnEvent ev, string? journalId = null) =>
        Get("add_own_event", CoreJson.Default.KeptEvent,
            ("journal_id", journalId), ("date", date), ("event", Native.Node(ev, CoreJson.Default.OwnEvent)))!;
    /// Only events with IsOwn can be edited.
    public KeptEvent UpdateOwnEvent(string id, OwnEvent ev) =>
        Get("update_own_event", CoreJson.Default.KeptEvent, ("id", id), ("event", Native.Node(ev, CoreJson.Default.OwnEvent)))!;

    // --- Locked notes (docs/locked-notes.md) ---------------------------------------

    public LockStatus LockStatus() => Get("lock_status", CoreJson.Default.LockStatus)!;
    /// First set (current null) or change; also turns locked notes on. A
    /// wrong current password is a "crypto" error.
    public void SetLockPassword(string? current, string newPassword) =>
        Call("set_lock_password", ("current", current), ("new_password", newPassword));
    public void SetLockEnabled(bool enabled) => Call("set_lock_enabled", ("enabled", enabled));
    /// Forgot the password: deletes every locked note and the password.
    public void ResetLock() => Call("reset_lock");
    public bool CheckLockPassword(string password) => Call("check_lock_password", ("password", password)).GetBoolean();
    public bool HasLockedNote(string date, string? journalId = null) =>
        Call("has_locked_note", ("journal_id", journalId), ("date", date)).GetBoolean();
    /// Null when the day has none. A wrong password is a "crypto" error.
    public string? LockedNote(string date, string password, string? journalId = null) =>
        Get("locked_note", CoreJson.Default.String, ("journal_id", journalId), ("date", date), ("password", password));
    /// Empty text removes the note.
    public void SetLockedNote(string date, string password, string text, string? journalId = null) =>
        Call("set_locked_note", ("journal_id", journalId), ("date", date), ("password", password), ("text", text));

    // --- Synced settings (docs/appearance.md) -------------------------------------

    /// Null when never set, which means the default.
    public string? Setting(string key) => Get("setting", CoreJson.Default.String, ("key", key));
    /// Null goes back to the default.
    public void SetSetting(string key, string? value) => Call("set_setting", ("key", key), ("value", value));

    // --- Revisions --------------------------------------------------------------

    public List<Revision> Revisions(string entryId) => Get("revisions", CoreJson.Default.ListRevision, ("entry_id", entryId))!;
    public void ResolveConflicts(string entryId) => Call("resolve_conflicts", ("entry_id", entryId));

    // --- Sync: keys -------------------------------------------------------------

    public string GenerateJournalKey() => Call("generate_journal_key").GetString()!;
    public void SetJournalKey(string key) => Call("set_journal_key", ("key", key));
    public void ForgetJournalKey() => Call("forget_journal_key");
    public string RecoveryPhrase() => Call("recovery_phrase").GetString()!;
    public string KeyFromRecoveryPhrase(string phrase) => Call("key_from_recovery_phrase", ("phrase", phrase)).GetString()!;
    public string KeyCheck() => Call("key_check").GetString()!;
    public bool KeyMatches(string key, string keyCheck) => Call("key_matches", ("key", key), ("key_check", keyCheck)).GetBoolean();
    public string SealDeviceName(string name) => Call("seal_device_name", ("name", name)).GetString()!;
    public string OpenDeviceName(string sealedName) => Call("open_device_name", ("sealed", sealedName)).GetString()!;
    public string SealKeyForPairing(string publicKey) => Call("seal_key_for_pairing", ("public_key", publicKey)).GetString()!;

    // --- Sync: ops --------------------------------------------------------------

    public uint PendingCount() => Call("pending_count").GetUInt32();
    public OutgoingBatch? OutgoingBatch(uint maxOps) => Get("outgoing_batch", CoreJson.Default.OutgoingBatch, ("max_ops", maxOps));
    public void PushSucceeded(string batchId) => Call("push_succeeded", ("batch_id", batchId));
    public long SyncCursor() => Call("sync_cursor").GetInt64();
    public PullResult ApplyIncoming(IReadOnlyList<IncomingOps> rows) =>
        Get("apply_incoming", CoreJson.Default.PullResult, ("rows", Native.Node(rows.ToList(), CoreJson.Default.ListIncomingOps)))!;
    public OutgoingSnapshot Snapshot() => Get("snapshot", CoreJson.Default.OutgoingSnapshot)!;
    public PullResult ApplySnapshot(long seq, string payload) =>
        Get("apply_snapshot", CoreJson.Default.PullResult, ("seq", seq), ("payload", payload))!;
    public void ResetSync() => Call("reset_sync");

    // --- Import and export ------------------------------------------------------

    /// Writes the journal (or one journal) to `path`; name it with
    /// format.Extension().
    public ExportSummary Export(ExportFormat format, string path, string? journalId = null) =>
        Get("export", CoreJson.Default.ExportSummary, ("format", format.ToString()), ("path", path), ("journal_id", journalId))!;

    /// Reads a file, folder or zip and says what importing it would do.
    public ImportPreview PreviewImport(string path) =>
        Get("preview_import", CoreJson.Default.ImportPreview, ("path", path))!;

    /// Imports it; entries already imported are skipped. All or nothing.
    public ImportResult ImportFile(string path, string? journalId = null) =>
        Get("import_file", CoreJson.Default.ImportResult, ("path", path), ("journal_id", journalId))!;

    // --- Plumbing ---------------------------------------------------------------

    private JsonElement Call(string method, params (string, JsonNode?)[] args)
    {
        ObjectDisposedException.ThrowIf(handle == 0, this);
        return Native.Unwrap(Native.StoreCall(handle, method, Native.Args(args)));
    }

    private T? Get<T>(string method, System.Text.Json.Serialization.Metadata.JsonTypeInfo<T> type, params (string, JsonNode?)[] args) =>
        Native.As(Call(method, args), type);
}

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
    public ThumbImage ThumbhashImage(string thumbhash) =>
        Get("thumbhash_image", CoreJson.Default.ThumbImage, ("thumbhash", thumbhash))!;

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

using System.Globalization;
using System.Text.Json.Nodes;

namespace Librelog.Sync;

/// The account's sync subscription as `librelog.sync_status()` reports it
/// (supabase/migrations/20261022120000_billing.sql), and the words the Sync
/// page shows for it.
///
/// Windows can't take payment yet: sync is bought in the iPhone, iPad, Mac or
/// Android app (App Store / Google Play) and then works here on the same
/// account. Future work (SPEC.md §15.5): the Microsoft Store lets non-game
/// apps use their own payments with no commission, so a Stripe web checkout
/// (and the `stripe-webhook` Edge Function) can be offered here directly.
public sealed record SubscriptionStatus(
    DateTimeOffset? SyncUntil,
    string? Store,
    string? Status,
    DateTimeOffset? ExpiresAt,
    bool AutoRenew)
{
    /// Pulls keep working this long after sync_until (push_ops/pull_ops).
    public static readonly TimeSpan PullGrace = TimeSpan.FromDays(30);

    public const string SubscribeHint =
        "Sync is a yearly subscription. Subscribe from LibreLog on your iPhone, iPad, Mac or Android phone with the same account, then sync here.";

    /// The first row of `sync_status`, or an empty status if there is none.
    public static SubscriptionStatus Parse(JsonNode? node)
    {
        var row = node switch
        {
            JsonArray a => a.FirstOrDefault() as JsonObject,
            JsonObject o => o,
            _ => null,
        };
        if (row is null) return new SubscriptionStatus(null, null, null, null, false);
        return new SubscriptionStatus(
            Date(row["sync_until"]),
            Text(row["store"]),
            Text(row["status"]),
            Date(row["expires_at"]),
            row["auto_renew"] is JsonValue v && v.TryGetValue<bool>(out var b) && b);
    }

    private static string? Text(JsonNode? n) =>
        n is JsonValue v && v.TryGetValue<string>(out var s) && s.Length > 0 ? s : null;

    private static DateTimeOffset? Date(JsonNode? n) =>
        Text(n) is { } s && DateTimeOffset.TryParse(s, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var d)
            ? d : null;

    public bool IsActive(DateTimeOffset now) => SyncUntil is { } until && until > now;

    /// What the Sync page says: a headline ("Active until 3 Oct 2027"), a
    /// line about where it was bought and whether it renews, and whether to
    /// show <see cref="SubscribeHint"/>.
    public (string Headline, string Detail, bool NeedsSubscription) Describe(DateTimeOffset now, TimeZoneInfo? zone = null)
    {
        var tz = zone ?? TimeZoneInfo.Local;
        string Day(DateTimeOffset d) =>
            TimeZoneInfo.ConvertTime(d, tz).ToString("d MMM yyyy", CultureInfo.InvariantCulture);

        if (SyncUntil is not { } until)
            return ("Not active", "", true);

        if (until <= now)
        {
            var detail = until + PullGrace > now
                ? $"Uploads are paused. This PC can still download changes until {Day(until + PullGrace)}."
                : "";
            return ($"Ended {Day(until)}", detail, true);
        }

        var where = Store switch
        {
            "app_store" => "Subscribed through the App Store",
            "play" => "Subscribed through Google Play",
            "stripe" => "Subscribed on the web",
            _ => null,
        };
        if (where is null) return ($"Active until {Day(until)}", "", false);

        var renewal = Status switch
        {
            "grace" or "billing_retry" => "The last payment didn't go through; the store is retrying.",
            "refunded" or "revoked" or "expired" => "Won't renew.",
            _ => AutoRenew ? "Renews automatically." : "Won't renew.",
        };
        return ($"Active until {Day(until)}", $"{where}. {renewal}", false);
    }
}

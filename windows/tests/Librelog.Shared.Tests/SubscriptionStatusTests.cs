using System.Text.Json.Nodes;
using Librelog.Sync;

namespace Librelog.Tests;

/// What the Windows Sync page says about the account's subscription, from
/// `librelog.sync_status()` rows.
public sealed class SubscriptionStatusTests
{
    private static readonly DateTimeOffset Now = new(2026, 10, 22, 12, 0, 0, TimeSpan.Zero);

    private static SubscriptionStatus Row(string json) => SubscriptionStatus.Parse(JsonNode.Parse(json));

    private static (string Headline, string Detail, bool NeedsSubscription) Shown(string json) =>
        Row(json).Describe(Now, TimeZoneInfo.Utc);

    [Fact]
    public void ParsesTheFirstRow()
    {
        var s = Row("""[{"sync_until":"2027-10-03T09:15:00+00:00","store":"app_store","product_id":"uk.co.maybeitssoftware.librelog.sync.yearly","status":"active","expires_at":"2027-10-03T09:15:00+00:00","auto_renew":true}]""");
        Assert.Equal(new DateTimeOffset(2027, 10, 3, 9, 15, 0, TimeSpan.Zero), s.SyncUntil);
        Assert.Equal("app_store", s.Store);
        Assert.Equal("active", s.Status);
        Assert.True(s.AutoRenew);
        Assert.True(s.IsActive(Now));
    }

    [Fact]
    public void NoRowOrNullsMeanNeverPaid()
    {
        foreach (var json in new[] { "[]", "null", """[{"sync_until":null,"store":null,"product_id":null,"status":null,"expires_at":null,"auto_renew":null}]""" })
        {
            var s = Row(json);
            Assert.Null(s.SyncUntil);
            Assert.False(s.AutoRenew);
            Assert.Equal(("Not active", "", true), s.Describe(Now, TimeZoneInfo.Utc));
        }
    }

    [Fact]
    public void ActiveStoreSubscriptionsSayWhereAndWhetherTheyRenew()
    {
        Assert.Equal(("Active until 3 Oct 2027", "Subscribed through the App Store. Renews automatically.", false),
            Shown("""[{"sync_until":"2027-10-03T00:00:00Z","store":"app_store","status":"active","auto_renew":true}]"""));
        Assert.Equal(("Active until 3 Oct 2027", "Subscribed through Google Play. Won't renew.", false),
            Shown("""[{"sync_until":"2027-10-03T00:00:00Z","store":"play","status":"active","auto_renew":false}]"""));
        Assert.Equal(("Active until 3 Oct 2027", "Subscribed through Google Play. The last payment didn't go through; the store is retrying.", false),
            Shown("""[{"sync_until":"2027-10-03T00:00:00Z","store":"play","status":"grace","auto_renew":true}]"""));
    }

    [Fact]
    public void AWebPurchaseSaysItDoesntRenew()
    {
        Assert.Equal(("Active until 3 Oct 2029", "Paid for on the web. Doesn't renew: buy more time whenever you like.", false),
            Shown("""[{"sync_until":"2029-10-03T00:00:00Z","store":"stripe","status":"active","auto_renew":false}]"""));
        Assert.Equal(("Active until 3 Oct 2027", "Paid for on the web. Won't renew.", false),
            Shown("""[{"sync_until":"2027-10-03T00:00:00Z","store":"stripe","status":"refunded","auto_renew":false}]"""));
    }

    [Fact]
    public void WebPlansAreOfferedUnlessAStoreSubscriptionRenews()
    {
        Assert.True(Row("[]").OffersWebPlans(Now));
        Assert.True(Row("""[{"sync_until":"2029-10-03T00:00:00Z","store":"stripe","status":"active","auto_renew":false}]""").OffersWebPlans(Now));
        Assert.True(Row("""[{"sync_until":"2027-10-03T00:00:00Z","store":"play","status":"active","auto_renew":false}]""").OffersWebPlans(Now));
        Assert.True(Row("""[{"sync_until":"2026-01-01T00:00:00Z","store":"app_store","status":"expired","auto_renew":true}]""").OffersWebPlans(Now));
        Assert.False(Row("""[{"sync_until":"2027-10-03T00:00:00Z","store":"app_store","status":"active","auto_renew":true}]""").OffersWebPlans(Now));
        Assert.False(Row("""[{"sync_until":"2027-10-03T00:00:00Z","store":"play","status":"grace","auto_renew":true}]""").OffersWebPlans(Now));
    }

    [Fact]
    public void AHandGrantShowsOnlyTheDate()
    {
        Assert.Equal(("Active until 1 Jan 2036", "", false),
            Shown("""[{"sync_until":"2036-01-01T00:00:00Z","store":null,"status":null,"auto_renew":null}]"""));
    }

    [Fact]
    public void ALapsedAccountCanStillDownloadForThirtyDays()
    {
        Assert.Equal(("Ended 12 Oct 2026", "Uploads are paused. This PC can still download changes until 11 Nov 2026.", true),
            Shown("""[{"sync_until":"2026-10-12T00:00:00Z","store":"app_store","status":"expired","auto_renew":false}]"""));
        Assert.Equal(("Ended 1 Jan 2026", "", true),
            Shown("""[{"sync_until":"2026-01-01T00:00:00Z","store":"play","status":"expired","auto_renew":false}]"""));
    }

    [Fact]
    public void DatesAreShownInTheGivenTimeZone()
    {
        var tokyo = TimeZoneInfo.CreateCustomTimeZone("UTC+9", TimeSpan.FromHours(9), "UTC+9", "UTC+9");
        var s = Row("""[{"sync_until":"2027-10-02T20:00:00Z","store":null}]""");
        Assert.Equal("Active until 3 Oct 2027", s.Describe(Now, tokyo).Headline);
    }
}

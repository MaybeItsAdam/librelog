using Dailylog.Core;

namespace Dailylog.Tests;

/// The C ABI against the real core library.
public sealed class InteropTests : IDisposable
{
    private readonly string dir = Directory.CreateTempSubdirectory("dailylog-test").FullName;
    private readonly CoreStore store;

    public InteropTests() => store = CoreStore.Open(Path.Combine(dir, "journal.sqlite"));

    public void Dispose()
    {
        store.Dispose();
        Directory.Delete(dir, recursive: true);
    }

    [Fact]
    public void WritesReadsAndSearches()
    {
        var e = store.CreateEntry("2026-10-03", "08:30");
        Assert.Equal(4u, store.SaveBody(e.Id, [new Block(BlockKind.Paragraph, "Coffee by the canal")]));
        store.SetRating(e.Id, 4.3);
        store.TagEntry(e.Id, store.TagNamed("Coffee").Id);

        var back = store.Entry(e.Id);
        Assert.Equal(4.5, back.Rating);
        Assert.Equal("Coffee by the canal", back.Body[0].Text);
        Assert.Equal(["Coffee"], back.Tags.Select(t => t.Name));

        var hit = Assert.Single(store.Search("cana tag:coffee", 10));
        Assert.Contains("canal", hit.Snippet);
        Assert.Single(store.Day("2026-10-03"));
        Assert.Equal(1u, store.Month("2026-10")[0].EntryCount);
        Assert.Single(store.Timeline(null, null, 10));
        Assert.Equal(1u, store.Stats("2026-10-03").CurrentStreak);
    }

    [Fact]
    public void CoreErrorsBecomeTypedExceptions()
    {
        Assert.Equal("not_found", Assert.Throws<CoreException>(() => store.Entry("missing")).Kind);
        Assert.Equal("invalid", Assert.Throws<CoreException>(() => store.CreateEntry("2026-13-01", null)).Kind);
        Assert.Equal("sync", Assert.Throws<CoreException>(() => store.OutgoingBatch(10)).Kind);
    }

    [Fact]
    public void KeysAndPairingCodesRoundTrip()
    {
        var key = store.GenerateJournalKey();
        store.SetJournalKey(key);
        Assert.Equal(key, store.KeyFromRecoveryPhrase(store.RecoveryPhrase()));
        Assert.True(store.KeyMatches(key, store.KeyCheck()));

        using var pairing = new PairingSession();
        var code = CoreFunctions.ParsePairingCode(pairing.Code);
        Assert.Equal(pairing.Id, code.Id);
        Assert.Equal(key, pairing.Receive(store.SealKeyForPairing(code.PublicKey)));
    }

    [Fact]
    public void FreeFunctions()
    {
        Assert.Equal(BlockKind.Heading(2), CoreFunctions.MarkdownShortcut("## ")!.Kind);
        Assert.Null(CoreFunctions.MarkdownShortcut("plain"));
        Assert.Equal("- a", CoreFunctions.BlocksToMarkdown([new Block(BlockKind.Bullet, "a")]));
        Assert.Equal(2u, CoreFunctions.BlocksWordCount([new Block(BlockKind.Paragraph, "two words")]));
    }
}

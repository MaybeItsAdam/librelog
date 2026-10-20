using System.Globalization;
using Librelog.Core;
using Librelog.Days;
using Librelog.Privacy;

namespace Librelog.Tests;

/// Events added by hand, locked notes, and the editor's metadata line.
public sealed class DayExtrasTests : IDisposable
{
    private static readonly CultureInfo En = CultureInfo.GetCultureInfo("en-GB");
    private readonly string dir = Directory.CreateTempSubdirectory("librelog-day").FullName;
    private readonly CoreStore store;

    public DayExtrasTests() => store = CoreStore.Open(Path.Combine(dir, "journal.sqlite"));

    public void Dispose()
    {
        store.Dispose();
        Directory.Delete(dir, recursive: true);
    }

    private static TimeSpan T(int h, int m = 0) => new(h, m, 0);

    // --- The event form -------------------------------------------------------------

    [Fact]
    public void EventFormNeedsATitle()
    {
        Assert.Equal("Give the event a title.", OwnEventForm.Validate("  ", true, null, null, null).Error);
    }

    [Fact]
    public void AllDayIgnoresTimesAndTidiesThePlace()
    {
        var (ev, error) = OwnEventForm.Validate(" Dentist ", true, T(9), T(8), "  ");
        Assert.Null(error);
        Assert.Equal(new OwnEvent("Dentist", null, null, null), ev);
    }

    [Fact]
    public void TimedEventsEndAfterTheyStart()
    {
        Assert.Equal("The end has to be after the start.", OwnEventForm.Validate("Run", false, T(9), T(9), null).Error);
        Assert.Equal("The end has to be after the start.", OwnEventForm.Validate("Run", false, T(9), T(8, 30), null).Error);
        Assert.Equal("Choose a start time, or make it all day.", OwnEventForm.Validate("Run", false, null, T(9), null).Error);

        var (ev, error) = OwnEventForm.Validate("Run", false, T(7, 5), T(8), "Canal");
        Assert.Null(error);
        Assert.Equal(new OwnEvent("Run", "07:05", "08:00", "Canal"), ev);
        Assert.Equal(new OwnEvent("Run", "07:05"), OwnEventForm.Validate("Run", false, T(7, 5), null, null).Event);
    }

    [Fact]
    public void OwnEventsRoundTripThroughTheCore()
    {
        var added = store.AddOwnEvent("2026-10-14", OwnEventForm.Validate("Dinner", false, T(19), T(21), "Home").Event!);
        Assert.True(added.IsOwn);
        Assert.False(added.AllDay);
        Assert.Equal("2026-10-14T19:00", added.Start);
        Assert.Equal((false, T(19), T(21)), OwnEventForm.TimesOf(added));

        var changed = store.UpdateOwnEvent(added.Id, new OwnEvent("Dinner out"));
        Assert.True(changed.AllDay);
        Assert.Equal("", changed.TimeLabel);
        Assert.Equal((true, (TimeSpan?)null, (TimeSpan?)null), OwnEventForm.TimesOf(changed));

        var kept = store.KeptCalendarEvents("2026-10-14");
        Assert.Equal(["Dinner out"], kept.Select(k => k.Title));
        Assert.Equal("scheduled", kept[0].State);

        var bad = Assert.Throws<CoreException>(() => store.AddOwnEvent("2026-10-14", new OwnEvent("x", "10:00", "09:00")));
        Assert.Equal("invalid", bad.Kind);

        store.RemoveCalendarEvent(added.Id);
        Assert.Empty(store.KeptCalendarEvents("2026-10-14"));
    }

    // --- Locked notes ---------------------------------------------------------------

    [Fact]
    public void LockPasswordRules()
    {
        Assert.Equal("Use at least 4 characters.", LockPassword.Problem("abc", "abc"));
        Assert.Equal("The two passwords don't match.", LockPassword.Problem("abcd", "abce"));
        Assert.Null(LockPassword.Problem("abcd", "abcd"));
    }

    [Fact]
    public void LockedNotesOpenOnlyWithThePassword()
    {
        Assert.Equal(new LockStatus(false, false), store.LockStatus());
        store.SetLockPassword(null, "otter");
        Assert.Equal(new LockStatus(true, true), store.LockStatus());
        Assert.True(store.CheckLockPassword("otter"));
        Assert.False(store.CheckLockPassword("beaver"));

        Assert.False(store.HasLockedNote("2026-10-14"));
        Assert.Null(store.LockedNote("2026-10-14", "otter"));
        store.SetLockedNote("2026-10-14", "otter", "Private lines");
        Assert.True(store.HasLockedNote("2026-10-14"));
        Assert.Equal("Private lines", store.LockedNote("2026-10-14", "otter"));

        var wrong = Assert.Throws<CoreException>(() => store.LockedNote("2026-10-14", "beaver"));
        Assert.True(LockPassword.IsWrongPassword(wrong));

        store.SetLockPassword("otter", "badger");
        Assert.Equal("Private lines", store.LockedNote("2026-10-14", "badger"));
        Assert.True(LockPassword.IsWrongPassword(Assert.Throws<CoreException>(() => store.SetLockPassword("otter", "stoat"))));

        store.SetLockEnabled(false);
        Assert.Equal(new LockStatus(false, true), store.LockStatus());
        store.SetLockEnabled(true);

        store.SetLockedNote("2026-10-14", "badger", "   ");
        Assert.False(store.HasLockedNote("2026-10-14"));

        store.SetLockedNote("2026-10-15", "badger", "Again");
        store.ResetLock();
        Assert.False(store.LockStatus().HasPassword);
        Assert.False(store.HasLockedNote("2026-10-15"));
    }

    // --- The metadata line ----------------------------------------------------------

    [Fact]
    public void MetadataLine()
    {
        var today = new DateOnly(2026, 10, 20);
        Assert.Equal("TUE 14 OCT · 09:30 · 3 DAYS", EntryMeta.Line("2026-10-14", "09:30", "2026-10-16", today, En));
        Assert.Equal("TUE 14 OCT", EntryMeta.Line("2026-10-14", null, null, today, En));
        Assert.Equal("MON 13 OCT 2025 · 08:00", EntryMeta.Line("2025-10-13", "08:00", "2025-10-13", today, En));
        Assert.Equal("TUE 14 OCT · WORK", EntryMeta.Line("2026-10-14", "", null, today, En, "Work"));
    }
}

# Calendar

While writing or looking at a day, LibreLog shows that day's calendar
events. Tap one and it's added to the day; from then on LibreLog keeps it in
step with the calendar. Events you don't tap are never stored.

## Where events come from

Each app reads the device's own calendar store, which already includes any
Google, iCloud or Exchange account added to the device:

| Platform | Source | Permission |
|---|---|---|
| iPhone, iPad, Mac | EventKit (`apple/App/Calendar/DeviceCalendar.swift`) | Full calendar access, asked when the user taps "Show this day's calendar" |
| Android | CalendarContract (`android/.../calendar/DeviceCalendar.kt`) | `READ_CALENDAR`, asked the same way |
| Windows | Not built yet | See below |

LibreLog only reads. It never writes to a calendar, and calendar data never
goes anywhere except into the journal, and from there into encrypted sync.

## Kept events

A kept event is one row in the synced `calendar_events` table
(`core/src/calendar.rs`), attached to a journal day rather than to an entry,
as SPEC.md §11 has it for kept integration items. It holds the event's
title, place, calendar name and time, the time it had when kept, a
cancelled flag, and the user's own note.

Its id is derived from the journal and the event's key, so two devices that
keep the same event end up with one row rather than two. Rows sync through
the ordinary encrypted op log with per-field last-writer-wins, so the server
sees nothing new, and a refresh changing the time never overwrites a note
written on another device.

### Event identity

Tracking only works if an event keeps the same key when it's edited:

- **Apple:** `apple:<calendarItemExternalIdentifier>`, plus
  `@<occurrence date, Unix seconds>` for an occurrence of a repeating event.
  `occurrenceDate` stays the original time even when one occurrence is moved
  on its own.
- **Android:** `android:<event id>` for a one-off event;
  `android:<series id>@<original instance time, ms>` for an occurrence, which
  also covers an occurrence edited on its own (its exception row points back
  through `ORIGINAL_ID` and `ORIGINAL_INSTANCE_TIME`).

Keys are per platform. An event kept on an iPhone shows on the Android phone
as it was last seen, but only Apple devices update it, and the same goes the
other way round.

### Staying up to date

The core updates a kept event in two cases:

1. **Showing a day.** The day's events from the calendar go to
   `Store::calendar_day`, which updates any kept ones in the list and returns
   the rest as suggestions.
2. **Refreshing.** When the app launches or comes to the front (at most every
   five minutes), it asks the core for `tracked_calendar_events(prefix)`,
   looks up each one in the calendar, and hands the results to
   `refresh_calendar_events`.

The rules, all in the core:

- Title, place and calendar name follow the calendar.
- A changed time is shown as **Moved**, with the new time. Moving it back
  clears the tag. If it moved to another day it stays on the day where it was
  kept, labelled "Now 21 Oct".
- If the calendar marks it cancelled, it's shown struck through and tagged
  **Cancelled**. If it's simply missing, only the device that kept it marks
  it cancelled, since another device may not have that calendar. If it comes
  back, the tag goes.
- Nothing is deleted automatically. "Remove from this day" is the only way
  a kept event goes, and that's a normal synced tombstone.
- Only fields that actually changed are written, so a refresh with nothing
  new queues no sync ops.

## Windows

SPEC.md lists Microsoft Graph and the Google Calendar API for Windows. Both
need OAuth sign-in, which the Windows app doesn't have yet. The core's C ABI
(`core/src/capi.rs`) already offers every calendar call (`calendar_day`,
`keep_calendar_event`, `refresh_calendar_events` and the rest), so a Windows
source only has to produce `CalendarEvent` records with `windows:` keys.
`Windows.ApplicationModel.Appointments` (the system calendar store) is
another option that needs no OAuth.

TODO(windows): pick a source, then add the day section to `EntryPage` and
`CalendarPage`.

## Not done yet

- Kept events are not included in exports (JSON, Markdown, HTML). They sync,
  but leaving LibreLog through an export loses them for now.
- Choosing which calendars to show. For now every calendar the device can
  read is shown.

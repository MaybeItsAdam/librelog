package uk.co.maybeitssoftware.librelog

import android.app.Application
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.work.Configuration
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.maybeitssoftware.librelog.calendar.DeviceCalendar
import uk.co.maybeitssoftware.librelog.lock.LockedNoteDraft
import uk.co.maybeitssoftware.librelog.sync.KeystoreSecretStore
import uk.co.maybeitssoftware.librelog.sync.RealtimeChannel
import uk.co.maybeitssoftware.librelog.sync.SupabaseClient
import uk.co.maybeitssoftware.librelog.sync.SyncEngine
import uk.co.maybeitssoftware.librelog.sync.SyncWorker
import uk.co.maybeitssoftware.librelog.ui.Appearance
import uniffi.librelog_core.Store

/**
 * The Supabase project the apps sync through (supabase/README.md). The
 * publishable key is public by design: it only identifies the project, and
 * row-level security plus the sync functions decide what any caller can do.
 */
object Backend {
    const val URL = "https://wzkhuilypzapqkrjxeku.supabase.co"
    const val PUBLISHABLE_KEY = "sb_publishable_tNzC2ZUmrDOFk_QTBWYY7A_KDfU-8aY"
}

/**
 * Provides WorkManager's configuration, so WorkManager starts on first use
 * rather than at every launch (the manifest removes its auto-initializer).
 */
class LibrelogApplication : Application(), Configuration.Provider {
    lateinit var model: AppModel
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        model = AppModel.launch(this)
    }
}

/**
 * App-wide state. Owns the core `Store`; every core call runs on the IO
 * dispatcher via [run], so typing and scrolling never wait on SQLite.
 * Mirrors apple/App/AppModel.swift.
 */
class AppModel(val store: Store, val sync: SyncEngine, val scope: CoroutineScope, val calendar: DeviceCalendar) {
    /** Bumped after any write or merged pull, so screens reload. */
    var revision by mutableIntStateOf(0)
        private set
    var lastError by mutableStateOf<String?>(null)

    /** Locked notes open or awaiting a save, by day: memory only (lock/LockedNoteSection.kt). */
    val lockedNotes = mutableStateMapOf<String, LockedNoteDraft>()

    /** Whether the device calendar can be read; asked for on first use only. */
    var calendarAccess by mutableStateOf(calendar.hasAccess)
        private set
    private var calendarRefreshedAt = 0L

    /**
     * Settings → Appearance (docs/appearance.md). Read at launch and again
     * after every pull that merged something, since it syncs.
     */
    var appearance by mutableStateOf(Appearance())
        private set

    init {
        sync.onChange = {
            changed()
            scope.launch { reloadAppearance() }
        }
    }

    suspend fun reloadAppearance() {
        runCatching { run { store -> Appearance.from { store.setting(it) } } }.onSuccess { appearance = it }
    }

    /** Applies one appearance choice at once, then stores and syncs it. */
    fun setAppearance(key: String, value: String) {
        appearance = Appearance.from { if (it == key) value else currentKey(it) }
        scope.launch { write { it.setSetting(key, value) } }
    }

    private fun currentKey(key: String): String = when (key) {
        Appearance.THEME -> appearance.theme.key
        Appearance.ACCENT -> appearance.accent.key
        Appearance.FONT -> appearance.font.key
        else -> appearance.textSize.key
    }

    suspend fun <T> run(work: (Store) -> T): T = withContext(Dispatchers.IO) { work(store) }

    /** Runs a write, reports failure, then refreshes screens and schedules a push. */
    suspend fun write(work: (Store) -> Unit) {
        try {
            run(work)
            changed()
            sync.schedulePush()
        } catch (e: Exception) {
            lastError = e.message
        }
    }

    fun changed() {
        revision++
    }

    /** After the permission dialog answers. */
    fun calendarAccessChanged() {
        calendarAccess = calendar.hasAccess
        changed()
    }

    /**
     * Brings every kept event this phone can see up to date with the
     * calendar: renamed, moved or cancelled since (docs/calendar.md). Runs on
     * coming to the foreground, at most every few minutes, and only once the
     * user has granted access. Events kept more than 90 days back are left.
     */
    suspend fun refreshCalendar(force: Boolean = false) {
        calendarAccess = calendar.hasAccess
        if (!calendarAccess) return
        val now = System.currentTimeMillis()
        if (!force && now - calendarRefreshedAt < 5 * 60_000) return
        calendarRefreshedAt = now
        val since = Day.adding(-90, Day.today)
        try {
            val tracked = run { it.trackedCalendarEvents("android:") }.filter { it.date >= since }
            val lookups = calendar.lookUp(tracked)
            if (lookups.isNotEmpty() && run { it.refreshCalendarEvents(lookups) } > 0u) {
                changed()
                sync.schedulePush()
            }
        } catch (e: Exception) {
            lastError = e.message
        }
    }

    suspend fun newEntry(date: String = Day.today): String? {
        val day = date
        val time = if (day == Day.today) Day.nowTime else null
        return try {
            val entry = run { it.createEntry(null, day, time) }
            changed()
            sync.schedulePush()
            entry.id
        } catch (e: Exception) {
            lastError = e.message
            null
        }
    }

    companion object {
        fun launch(app: Application): AppModel {
            val dir = File(app.filesDir, "Librelog").apply { mkdirs() }
            // The database is ours and private, so this only fails if the disk
            // is full or the file is corrupt.
            val store = Store.open(File(dir, "journal.sqlite").path)
            val secrets = KeystoreSecretStore(app)
            val client = SupabaseClient(Backend.URL, Backend.PUBLISHABLE_KEY, secrets)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val sync = SyncEngine(
                store = store,
                client = client,
                secrets = secrets,
                deviceName = Build.MODEL ?: "Android",
                scope = scope,
                realtimeFor = { user, onPing ->
                    RealtimeChannel(Backend.URL, Backend.PUBLISHABLE_KEY, "user:$user", client, scope, onPing)
                },
            )
            val model = AppModel(store, sync, scope, DeviceCalendar(app))
            // Read before the first frame, so the chosen theme never flashes
            // in after the default one. Four indexed reads.
            runCatching { Appearance.from { store.setting(it) } }.onSuccess { model.appearance = it }
            // Background sync only while sync is on.
            scope.launch {
                snapshotFlow { sync.phase }.collect { phase ->
                    if (phase == SyncEngine.Phase.On) SyncWorker.schedule(app) else SyncWorker.cancel(app)
                }
            }
            return model
        }
    }
}

/** Local calendar dates as the core expects them: `YYYY-MM-DD`. */
object Day {
    private val iso = DateTimeFormatter.ISO_LOCAL_DATE

    val today: String get() = LocalDate.now().format(iso)

    /**
     * `HH:MM` now. Formatted in [Locale.ROOT] so the digits stay ASCII: the
     * default locale's (Persian, Arabic…) are refused by the core.
     */
    val nowTime: String get() = LocalTime.now().let { "%02d:%02d".format(Locale.ROOT, it.hour, it.minute) }

    fun date(s: String): LocalDate? = runCatching { LocalDate.parse(s, iso) }.getOrNull()

    fun string(d: LocalDate): String = d.format(iso)

    fun adding(days: Long, to: String): String = date(to)?.plusDays(days)?.let(::string) ?: to

    fun weekday(s: String): String =
        date(s)?.dayOfWeek?.getDisplayName(TextStyle.FULL, Locale.getDefault()) ?: ""

    /** "Today", "Yesterday", "Tomorrow", or the weekday: a day's name at the top of a screen. */
    fun relative(s: String, from: String = today): String = when (s) {
        from -> "Today"
        adding(-1, from) -> "Yesterday"
        adding(1, from) -> "Tomorrow"
        else -> weekday(s).ifEmpty { s }
    }

    /**
     * The local day [s] as epoch milliseconds, start inclusive and end
     * exclusive, in [zone]. Days around a clock change are 23 or 25 hours.
     */
    fun millis(s: String, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long>? {
        val d = date(s) ?: return null
        return d.atStartOfDay(zone).toInstant().toEpochMilli() to d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** "Saturday 3 October" (or "Today" / "Yesterday"). */
    fun title(s: String): String {
        if (s == today) return "Today"
        if (s == adding(-1, today)) return "Yesterday"
        val d = date(s) ?: return s
        return DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()).format(d)
    }

    /** "3 Oct" this year, "3 Oct 2025" otherwise. */
    fun short(s: String): String = date(s)?.let { shortIn(it, LocalDate.now().year) } ?: s

    /**
     * A multi-day entry's dates: "26 – 27 Aug", "29 Sep – 2 Oct", "26 – 27 Aug
     * 2025", "30 Dec 2025 – 2 Jan 2026". The year is left off when both days
     * fall in [thisYear]. A missing or invalid end is just the start day.
     */
    fun range(start: String, end: String?, thisYear: Int = LocalDate.now().year): String {
        val a = date(start) ?: return start
        val b = end?.let(::date)?.takeIf { it > a } ?: return shortIn(a, thisYear)
        val year = if (a.year == thisYear && b.year == thisYear) "" else " yyyy"
        return when {
            a.year != b.year -> "${fmt("d MMM yyyy", a)} – ${fmt("d MMM yyyy", b)}"
            a.month == b.month -> "${a.dayOfMonth} – ${fmt("d MMM$year", b)}"
            else -> "${fmt("d MMM", a)} – ${fmt("d MMM$year", b)}"
        }
    }

    /** Which day of a multi-day entry [day] is: (2, 4) for "Day 2 of 4". */
    fun spanDay(start: String, end: String?, day: String): Pair<Int, Int>? {
        val a = date(start) ?: return null
        val b = end?.let(::date)?.takeIf { it > a } ?: return null
        val d = date(day)?.takeIf { it in a..b } ?: return null
        return (ChronoUnit.DAYS.between(a, d) + 1).toInt() to (ChronoUnit.DAYS.between(a, b) + 1).toInt()
    }

    private fun shortIn(d: LocalDate, thisYear: Int): String = fmt(if (d.year == thisYear) "d MMM" else "d MMM yyyy", d)

    private fun fmt(pattern: String, d: LocalDate): String = DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(d)

    fun monthTitle(yearMonth: String): String {
        val d = date("$yearMonth-01") ?: return yearMonth
        return DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()).format(d)
    }

    fun yearsAgo(s: String, from: String = today): Int {
        val a = date(s) ?: return 0
        val b = date(from) ?: return 0
        return ChronoUnit.YEARS.between(a, b).toInt()
    }

    fun longDate(s: String): String =
        date(s)?.let { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).format(it) } ?: s
}

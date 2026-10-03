package uk.co.maybeitsadam.dailylog

import android.app.Application
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.work.Configuration
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
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
import uk.co.maybeitsadam.dailylog.sync.KeystoreSecretStore
import uk.co.maybeitsadam.dailylog.sync.RealtimeChannel
import uk.co.maybeitsadam.dailylog.sync.SupabaseClient
import uk.co.maybeitsadam.dailylog.sync.SyncEngine
import uk.co.maybeitsadam.dailylog.sync.SyncWorker
import uniffi.dailylog_core.Store

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
class DailylogApplication : Application(), Configuration.Provider {
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
class AppModel(val store: Store, val sync: SyncEngine, val scope: CoroutineScope) {
    /** Bumped after any write or merged pull, so screens reload. */
    var revision by mutableIntStateOf(0)
        private set
    var selectedDate by mutableStateOf(Day.today)
    var lastError by mutableStateOf<String?>(null)

    init {
        sync.onChange = { changed() }
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

    suspend fun newEntry(date: String? = null): String? {
        val day = date ?: selectedDate
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
            val dir = File(app.filesDir, "Dailylog").apply { mkdirs() }
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
            val model = AppModel(store, sync, scope)
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

    val nowTime: String get() = LocalTime.now().let { "%02d:%02d".format(it.hour, it.minute) }

    fun date(s: String): LocalDate? = runCatching { LocalDate.parse(s, iso) }.getOrNull()

    fun string(d: LocalDate): String = d.format(iso)

    fun adding(days: Long, to: String): String = date(to)?.plusDays(days)?.let(::string) ?: to

    fun weekday(s: String): String =
        date(s)?.dayOfWeek?.getDisplayName(TextStyle.FULL, Locale.getDefault()) ?: ""

    /** "Saturday 3 October" (or "Today" / "Yesterday"). */
    fun title(s: String): String {
        if (s == today) return "Today"
        if (s == adding(-1, today)) return "Yesterday"
        val d = date(s) ?: return s
        return DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()).format(d)
    }

    /** "3 Oct" this year, "3 Oct 2025" otherwise. */
    fun short(s: String): String {
        val d = date(s) ?: return s
        val pattern = if (d.year == LocalDate.now().year) "d MMM" else "d MMM yyyy"
        return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(d)
    }

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

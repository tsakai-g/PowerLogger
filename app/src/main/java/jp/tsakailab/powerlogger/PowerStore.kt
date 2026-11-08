package jp.tsakailab.powerlogger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.BatteryManager
import android.content.Intent
import android.content.IntentFilter
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

object Prefs {
    private const val NAME = "monitor_settings"
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    fun monitoring(ctx: Context) = p(ctx).getBoolean("monitoring", false)
    fun setMonitoring(ctx: Context, value: Boolean) = p(ctx).edit().putBoolean("monitoring", value).apply()
    fun emailEnabled(ctx: Context) = p(ctx).getBoolean("email_enabled", false)
    fun setEmailEnabled(ctx: Context, value: Boolean) = p(ctx).edit().putBoolean("email_enabled", value).apply()
    fun recipient(ctx: Context) = p(ctx).getString("recipient", "") ?: ""
    fun endpoint(ctx: Context) = p(ctx).getString("endpoint", "") ?: ""
    fun secret(ctx: Context) = p(ctx).getString("secret", "") ?: ""
    fun saveEmailConfig(ctx: Context, email: String, endpoint: String, secret: String) {
        p(ctx).edit().putString("recipient", email.trim().lowercase(Locale.ROOT))
            .putString("endpoint", endpoint.trim()).putString("secret", secret.trim()).apply()
    }
}

object PowerState {
    fun isPlugged(ctx: Context): Boolean {
        val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val mode = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return mode != 0
    }
}

data class PowerEvent(
    val id: String,
    val kind: String,
    val millis: Long,
    val plugged: Boolean,
    val source: String,
    val email: String,
    val state: String,
    val tries: Int,
    val error: String,
    val durationMs: Long? = null
)

data class Outage(
    val startMs: Long,
    val endMs: Long?,
    val startSource: String,
    val endSource: String?
)

class PowerStore private constructor(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "powerlogger.db", null, 1) {
    companion object {
        @Volatile private var instance: PowerStore? = null
        fun get(ctx: Context): PowerStore = instance ?: synchronized(this) {
            instance ?: PowerStore(ctx).also { instance = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE events (
              id TEXT PRIMARY KEY NOT NULL,
              millis INTEGER NOT NULL,
              kind TEXT NOT NULL,
              plugged INTEGER NOT NULL,
              source TEXT NOT NULL,
              email TEXT NOT NULL DEFAULT '',
              send_state TEXT NOT NULL DEFAULT 'OFF',
              tries INTEGER NOT NULL DEFAULT 0,
              last_error TEXT NOT NULL DEFAULT '',
              duration_ms INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_events_time ON events(millis)")
        db.execSQL("""
            CREATE TABLE outages (
              start_id TEXT PRIMARY KEY NOT NULL,
              start_ms INTEGER NOT NULL,
              end_ms INTEGER,
              start_source TEXT NOT NULL,
              end_source TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_outages_start ON outages(start_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 schema; future migrations must preserve events and outages.
    }

    private fun latestPlugged(db: SQLiteDatabase): Boolean? {
        db.rawQuery("SELECT plugged FROM events WHERE kind IN ('BASELINE','DOWN','UP') ORDER BY rowid DESC LIMIT 1", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) != 0 else null
        }
    }

    private fun insertEvent(db: SQLiteDatabase, kind: String, plugged: Boolean,
                            source: String, ctx: Context, notify: Boolean): PowerEvent {
        val email = if (notify && Prefs.emailEnabled(ctx)) Prefs.recipient(ctx) else ""
        val state = if (notify && email.isNotEmpty()) "PENDING" else "OFF"
        val event = PowerEvent(UUID.randomUUID().toString(), kind, System.currentTimeMillis(),
            plugged, source, email, state, 0, "")
        val cv = ContentValues().apply {
            put("id", event.id); put("millis", event.millis); put("kind", event.kind)
            put("plugged", if (event.plugged) 1 else 0); put("source", event.source)
            put("email", event.email); put("send_state", event.state)
        }
        db.insertOrThrow("events", null, cv)
        return event
    }

    /** Called only when the user explicitly starts a new monitoring session. */
    @Synchronized fun beginSession(ctx: Context, plugged: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // A previously stopped monitoring session must not be joined across the gap.
            closeOpenOutages(db, System.currentTimeMillis(), "MONITOR_GAP")
            insertEvent(db, "BASELINE", plugged, "START", ctx, false)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Do not infer an exact restoration time when monitoring was stopped. */
    @Synchronized fun endSession(ctx: Context) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            closeOpenOutages(db, System.currentTimeMillis(), "MONITOR_GAP")
            insertEvent(db, "PAUSE", latestPlugged(db) ?: false, "USER", ctx, false)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun closeOpenOutages(db: SQLiteDatabase, timeMs: Long, reason: String) {
        val cv = ContentValues().apply { put("end_ms", timeMs); put("end_source", reason) }
        db.update("outages", cv, "end_ms IS NULL", null)
    }

    /** Returns null for duplicate/same-state observations and baseline observations. */
    @Synchronized fun observe(ctx: Context, plugged: Boolean, source: String): PowerEvent? {
        if (!Prefs.monitoring(ctx)) return null
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previous = latestPlugged(db)
            val event = when {
                previous == null -> insertEvent(db, "BASELINE", plugged, source, ctx, false)
                previous == plugged -> null
                else -> insertEvent(db, if (plugged) "UP" else "DOWN", plugged, source, ctx, true)
            }
            if (event != null && event.kind == "DOWN") {
                db.insertOrThrow("outages", null, ContentValues().apply {
                    put("start_id", event.id)
                    put("start_ms", event.millis)
                    put("start_source", event.source)
                })
            } else if (event != null && event.kind == "UP") {
                db.rawQuery("SELECT start_ms FROM outages WHERE end_ms IS NULL ORDER BY start_ms DESC LIMIT 1", null).use { c ->
                    if (c.moveToFirst()) {
                        val duration = event.millis - c.getLong(0)
                        if (duration >= 0) {
                            db.execSQL("UPDATE events SET duration_ms=? WHERE id=?", arrayOf<Any>(duration, event.id))
                        }
                    }
                }
                closeOpenOutages(db, event.millis, event.source)
            }
            db.setTransactionSuccessful()
            return event?.takeIf { it.kind == "DOWN" || it.kind == "UP" }
        } finally { db.endTransaction() }
    }

    @Synchronized fun queueTest(ctx: Context): PowerEvent? {
        if (!Prefs.emailEnabled(ctx)) return null
        val db = writableDatabase
        return insertEvent(db, "TEST", PowerState.isPlugged(ctx), "MANUAL", ctx, true)
    }

    @Synchronized fun cancelPending() {
        writableDatabase.execSQL("UPDATE events SET send_state='CANCELLED' WHERE send_state='PENDING'")
    }

    @Synchronized fun pending(limit: Int = 15): List<PowerEvent> {
        val result = mutableListOf<PowerEvent>()
        readableDatabase.rawQuery("""
            SELECT id,kind,millis,plugged,source,email,send_state,tries,last_error,duration_ms
            FROM events WHERE send_state='PENDING' ORDER BY rowid ASC LIMIT ?
        """.trimIndent(), arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) result += PowerEvent(c.getString(0), c.getString(1), c.getLong(2),
                c.getInt(3) != 0, c.getString(4), c.getString(5), c.getString(6), c.getInt(7), c.getString(8),
                if (c.isNull(9)) null else c.getLong(9))
        }
        return result
    }

    @Synchronized fun pendingCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM events WHERE send_state='PENDING'", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    @Synchronized fun markSent(eventId: String) {
        writableDatabase.execSQL("UPDATE events SET send_state='SENT', last_error='' WHERE id=? AND send_state='PENDING'", arrayOf(eventId))
    }

    @Synchronized fun markFailed(eventId: String, error: String) {
        val cv = ContentValues().apply { put("last_error", error.take(250)) }
        val db = writableDatabase
        db.execSQL("UPDATE events SET tries=tries+1 WHERE id=? AND send_state='PENDING'", arrayOf(eventId))
        db.update("events", cv, "id=? AND send_state='PENDING'", arrayOf(eventId))
    }

    @Synchronized fun latestFailure(): String {
        readableDatabase.rawQuery("SELECT last_error FROM events WHERE last_error<>'' ORDER BY rowid DESC LIMIT 1", null).use { c ->
            return if (c.moveToFirst()) c.getString(0) else ""
        }
    }

    @Synchronized fun outages(limit: Int = 50000): List<Outage> {
        val result = mutableListOf<Outage>()
        readableDatabase.rawQuery("""
            SELECT start_ms,end_ms,start_source,end_source
            FROM outages ORDER BY start_ms DESC LIMIT ?
        """.trimIndent(), arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) result += Outage(
                c.getLong(0), if (c.isNull(1)) null else c.getLong(1),
                c.getString(2), if (c.isNull(3)) null else c.getString(3)
            )
        }
        return result
    }

    @Synchronized fun totalOutages(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM outages", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun exportCsv(output: OutputStream, context: Context) {
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", Locale.ROOT)
        iso.timeZone = TimeZone.getDefault()
        output.writer(Charsets.UTF_8).use { writer ->
            writer.write("\uFEFF")
            writer.write(context.getString(R.string.csv_header) + "\r\n")
            outages().asReversed().forEach { o ->
                val duration = o.endMs?.let { it - o.startMs }
                val certainty = if (o.endSource == "MONITOR_GAP")
                    context.getString(R.string.csv_quality_monitor_gap) else if (
                    o.startSource == "BROADCAST" && o.endSource == "BROADCAST")
                    context.getString(R.string.csv_quality_broadcast) else context.getString(R.string.csv_quality_estimated)
                val fields = listOf(iso.format(Date(o.startMs)),
                    if (o.endSource == "MONITOR_GAP") context.getString(R.string.csv_monitor_gap)
                    else o.endMs?.let { iso.format(Date(it)) } ?: context.getString(R.string.csv_ongoing),
                    duration?.takeIf { it >= 0 && o.endSource != "MONITOR_GAP" }?.div(1000)?.toString() ?: "",
                    o.startSource, o.endSource ?: "", certainty)
                writer.write(fields.joinToString(",") { csvEscape(it) } + "\r\n")
            }
        }
    }

    private fun csvEscape(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}

object DateFormats {
    fun moment(ms: Long): String = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
    fun elapsed(ctx: Context, ms: Long): String {
        if (ms < 0) return ctx.getString(R.string.duration_clock_change)
        val seconds = ms / 1000
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        val remainder = seconds % 60
        val parts = mutableListOf<String>()
        if (days > 0) parts.add(ctx.getString(R.string.duration_day, days))
        if (hours > 0 || days > 0) parts.add(ctx.getString(R.string.duration_hour, hours))
        if (minutes > 0 || hours > 0 || days > 0) parts.add(ctx.getString(R.string.duration_minute, minutes))
        parts.add(ctx.getString(R.string.duration_second, remainder))
        val separator = if (ctx.resources.configuration.locales.get(0).language == "ja") "" else " "
        return parts.joinToString(separator)
    }
}

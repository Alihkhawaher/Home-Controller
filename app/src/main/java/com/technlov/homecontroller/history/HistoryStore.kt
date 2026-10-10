package com.technlov.homecontroller.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.technlov.homecontroller.history.HistoryMath.DAY
import com.technlov.homecontroller.history.HistoryMath.MAX_GAP_MS

/**
 * Power history on the phone: one SQLite file in the app's private storage. Minute samples are kept for
 * [MINUTE_RETENTION_MS] (the 24-hour chart); hourly summaries for [HOUR_RETENTION_MS] (the 7- and 30-day charts and
 * energy per day). A roll-up recomputes the recent hours from the minute samples and is safe to run any number of
 * times. Readings only: no keys, no names.
 */
class HistoryStore(context: Context) : SQLiteOpenHelper(context, "history.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE minute (device TEXT NOT NULL, metric TEXT NOT NULL, t INTEGER NOT NULL, v REAL NOT NULL, PRIMARY KEY (device, metric, t)) WITHOUT ROWID")
        db.execSQL("CREATE TABLE hour (device TEXT NOT NULL, metric TEXT NOT NULL, h INTEGER NOT NULL, avg REAL NOT NULL, min REAL NOT NULL, max REAL NOT NULL, n INTEGER NOT NULL, kwh REAL, PRIMARY KEY (device, metric, h)) WITHOUT ROWID")
        db.execSQL("CREATE TABLE unit (device TEXT NOT NULL, metric TEXT NOT NULL, unit TEXT NOT NULL, PRIMARY KEY (device, metric)) WITHOUT ROWID")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** One reading per metric for the minute [minute]; a later reading in the same minute replaces the earlier. */
    @Synchronized
    fun record(device: String, units: Map<String, String>, values: Map<String, Double>, minute: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((metric, v) in values) {
                db.insertWithOnConflict("minute", null, ContentValues().apply {
                    put("device", device); put("metric", metric); put("t", minute); put("v", v)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                units[metric]?.let { u ->
                    db.insertWithOnConflict("unit", null, ContentValues().apply {
                        put("device", device); put("metric", metric); put("unit", u)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Summarises the complete hours still covered by minute samples (power metrics also get their energy), then
     * drops minute samples and hours past their retention.
     */
    @Synchronized
    fun rollUp(now: Long) {
        val db = writableDatabase
        val thisHour = HistoryMath.hourOf(now)
        val from = thisHour - MINUTE_RETENTION_MS
        db.beginTransaction()
        try {
            val series = db.rawQuery(
                "SELECT DISTINCT m.device, m.metric, COALESCE(u.unit, '') FROM minute m LEFT JOIN unit u " +
                    "ON u.device = m.device AND u.metric = m.metric WHERE m.t >= ?",
                arrayOf(from.toString()),
            ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2))) } }
            for ((device, metric, unit) in series) {
                // Up to the first sample after the last complete hour, so that hour's final step is counted.
                val samples = minutes(device, metric, from, thisHour + MAX_GAP_MS)
                val integrate = unit.equals("W", ignoreCase = true)
                for (h in HistoryMath.hours(samples, integrate)) {
                    if (h.hourMillis >= thisHour) continue          // the current hour is still filling
                    db.insertWithOnConflict("hour", null, ContentValues().apply {
                        put("device", device); put("metric", metric); put("h", h.hourMillis)
                        put("avg", h.avg); put("min", h.min); put("max", h.max); put("n", h.count)
                        if (h.kwh != null) put("kwh", h.kwh) else putNull("kwh")
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            db.delete("minute", "t < ?", arrayOf((now - MINUTE_RETENTION_MS).toString()))
            db.delete("hour", "h < ?", arrayOf((now - HOUR_RETENTION_MS).toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun minutes(device: String, metric: String, from: Long, to: Long): List<Sample> =
        readableDatabase.rawQuery(
            "SELECT t, v FROM minute WHERE device = ? AND metric = ? AND t >= ? AND t < ? ORDER BY t",
            arrayOf(device, metric, from.toString(), to.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(Sample(c.getLong(0), c.getDouble(1))) } }

    fun hours(device: String, metric: String, from: Long, to: Long): List<HourStat> =
        readableDatabase.rawQuery(
            "SELECT h, avg, min, max, n, kwh FROM hour WHERE device = ? AND metric = ? AND h >= ? AND h < ? ORDER BY h",
            arrayOf(device, metric, from.toString(), to.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(HourStat(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3), c.getInt(4),
                        if (c.isNull(5)) null else c.getDouble(5)))
                }
            }
        }

    /** Forgets a device's history (when it is removed). */
    @Synchronized
    fun forget(device: String) {
        val db = writableDatabase
        for (table in listOf("minute", "hour", "unit")) db.delete(table, "device = ?", arrayOf(device))
    }

    companion object {
        const val MINUTE_RETENTION_MS = 2 * DAY
        const val HOUR_RETENTION_MS = 400 * DAY
    }
}

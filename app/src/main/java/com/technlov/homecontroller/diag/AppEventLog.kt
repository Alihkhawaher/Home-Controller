package com.technlov.homecontroller.diag

import com.technlov.home.core.EventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Where log entries come from (connectors use their own id, e.g. "gree"). */
object LogSource {
    const val APP = "app"
    const val CRASH = "crash"
    const val HUB = "hub"
    const val SERVICE = "service"
    const val NETWORK = "network"
    const val DISCOVERY = "discovery"
    const val PAIRING = "pairing"
}

data class LogEntry(
    val timeMillis: Long,
    val level: EventLog.Level,
    val source: String,
    val message: String,
    val stackTrace: String? = null,
)

/**
 * The log's text form: one line per entry ("2026-10-10 18:00:00.123 I/hub: message"), then the error's stack trace
 * on tab-indented lines. Pure; read back when the app starts, so the log survives restarts (and crashes).
 */
object LogFormat {
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val LINE = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) ([DIWE])/([^:]*): ?(.*)$""")

    fun time(millis: Long, zone: ZoneId): String = TIME.format(Instant.ofEpochMilli(millis).atZone(zone))

    fun letter(level: EventLog.Level): Char = level.name.first()

    /** A source never contains ':' (it ends the source in the line format). */
    fun cleanSource(source: String): String = source.replace(':', '_').replace('\n', ' ').trim().ifEmpty { "?" }

    fun format(entry: LogEntry, zone: ZoneId): String = buildString {
        append(time(entry.timeMillis, zone)).append(' ').append(letter(entry.level)).append('/')
        append(cleanSource(entry.source)).append(": ")
        append(entry.message.replace("\r", "").replace("\n", " | "))
        append('\n')
        entry.stackTrace?.lineSequence()?.filter { it.isNotBlank() }?.forEach { append('\t').append(it.trim()).append('\n') }
    }

    fun parse(text: String, zone: ZoneId): List<LogEntry> {
        val out = ArrayList<LogEntry>()
        var current: LogEntry? = null
        val stack = StringBuilder()
        fun finish() {
            current?.let { out += if (stack.isEmpty()) it else it.copy(stackTrace = stack.toString().trimEnd()) }
            current = null
            stack.setLength(0)
        }
        for (line in text.lineSequence()) {
            if (line.startsWith('\t')) {
                if (current != null) stack.append(line.substring(1)).append('\n')
                continue
            }
            val match = LINE.matchEntire(line) ?: continue
            finish()
            val (time, letter, source, message) = match.destructured
            val millis = runCatching { LocalDateTime.parse(time, TIME).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
                ?: continue
            val level = EventLog.Level.entries.firstOrNull { it.name.first() == letter.first() } ?: EventLog.Level.INFO
            current = LogEntry(millis, level, source, message)
        }
        finish()
        return out
    }

    fun stackTraceOf(error: Throwable): String = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
}

/**
 * The log on disk: [current] grows to about [maxBytes], then becomes [previous] (replacing the older one), so the
 * log never takes more than about twice [maxBytes]. Thread-safe.
 */
class RollingLogFile(private val dir: File, private val maxBytes: Long = DEFAULT_MAX_BYTES, name: String = "events") {
    val current = File(dir, "$name.log")
    val previous = File(dir, "$name.1.log")
    private var out: FileOutputStream? = null

    @Synchronized
    fun append(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (current.exists() && current.length() > 0 && current.length() + bytes.size > maxBytes) rotate()
        val stream = out ?: run {
            dir.mkdirs()
            FileOutputStream(current, true).also { out = it }
        }
        stream.write(bytes)
    }

    @Synchronized
    fun rotate() {
        closeStream()
        previous.delete()
        if (current.exists()) current.renameTo(previous)
    }

    /** Both files, oldest first. */
    @Synchronized
    fun readAll(): String = buildString {
        if (previous.exists()) append(previous.readText(Charsets.UTF_8))
        if (current.exists()) append(current.readText(Charsets.UTF_8))
    }

    @Synchronized
    fun clear() {
        closeStream()
        previous.delete()
        current.delete()
    }

    @Synchronized
    fun close() = closeStream()

    private fun closeStream() {
        runCatching { out?.close() }
        out = null
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 1_000_000L
    }
}

/**
 * The app's [EventLog]: the last [capacity] entries in memory for the Diagnostics screen, and every entry appended to
 * a [RollingLogFile] on a background thread (logging never blocks the caller). Saved entries are read back at start.
 *
 * Callers must never pass secrets (Wi-Fi passwords, device keys); this class writes what it is given.
 */
class AppEventLog(
    private val file: RollingLogFile?,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "event-log").apply { isDaemon = true }
    },
) : EventLog {

    @Volatile
    override var verbose: Boolean = false

    private val lock = Any()
    private val ring = ArrayDeque<LogEntry>()
    private val _version = MutableStateFlow(0L)

    /** Changes whenever entries are added or cleared; read [snapshot] for the entries. */
    val version: StateFlow<Long> = _version.asStateFlow()

    override fun log(level: EventLog.Level, source: String, message: String, error: Throwable?) {
        val entry = LogEntry(clock(), level, LogFormat.cleanSource(source), message, error?.let(LogFormat::stackTraceOf))
        remember(entry)
        val target = file ?: return
        val text = LogFormat.format(entry, zone)
        try {
            writer.execute { runCatching { target.append(text) } }
        } catch (e: RuntimeException) {
            // The writer is shut down (the app is dying): keep the entry in memory only.
        }
    }

    /** Writes straight to the file, after anything still queued: for the last words of a crashing app. */
    fun logNow(level: EventLog.Level, source: String, message: String, error: Throwable?) {
        val entry = LogEntry(clock(), level, LogFormat.cleanSource(source), message, error?.let(LogFormat::stackTraceOf))
        remember(entry)
        flush(CRASH_FLUSH_MS)
        file?.let { runCatching { it.append(LogFormat.format(entry, zone)) } }
    }

    fun snapshot(): List<LogEntry> = synchronized(lock) { ring.toList() }

    /** Reads the saved log back into memory (oldest dropped beyond [capacity]); entries logged meanwhile stay last. */
    fun loadSaved() {
        val target = file ?: return
        writer.execute {
            val saved = runCatching { LogFormat.parse(target.readAll(), zone) }.getOrDefault(emptyList())
            if (saved.isEmpty()) return@execute
            synchronized(lock) {
                val room = capacity - ring.size
                if (room > 0) ring.addAll(0, saved.takeLast(room))
            }
            _version.update { it + 1 }
        }
    }

    fun clear() {
        synchronized(lock) { ring.clear() }
        _version.update { it + 1 }
        file?.let { target -> runCatching { writer.execute { target.clear() } } }
    }

    /** The whole saved log as text after [header] (what "Share log" sends), once queued writes have landed. */
    fun export(header: String): String {
        flush(EXPORT_FLUSH_MS)
        val body = file?.let { runCatching { it.readAll() }.getOrNull() }
            ?: snapshot().joinToString("") { LogFormat.format(it, zone) }
        return header + "\n" + body
    }

    /** Waits (up to [timeoutMs]) for queued writes. */
    fun flush(timeoutMs: Long = EXPORT_FLUSH_MS) {
        try {
            writer.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // Shut down or slow: carry on with what is on disk.
        }
    }

    private fun remember(entry: LogEntry) {
        synchronized(lock) {
            if (ring.size >= capacity) ring.removeFirst()
            ring.addLast(entry)
        }
        _version.update { it + 1 }
    }

    companion object {
        const val DEFAULT_CAPACITY = 3_000
        private const val CRASH_FLUSH_MS = 500L
        private const val EXPORT_FLUSH_MS = 3_000L
    }
}

package com.technlov.homecontroller.diag

import com.technlov.home.core.ConnectorInfo
import com.technlov.home.core.DeviceCategory
import com.technlov.home.core.EventLog
import com.technlov.home.core.info
import com.technlov.home.core.trace
import com.technlov.home.core.warn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId
import java.time.ZoneOffset

class AppEventLogTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val zone: ZoneId = ZoneOffset.UTC
    private var now = 1_760_000_000_000L
    private val opened = mutableListOf<AppEventLog>()

    private fun newLog(file: RollingLogFile?, capacity: Int = 3_000) =
        AppEventLog(file, capacity, clock = { now++ }, zone = zone).also { opened += it }

    @After
    fun tearDown() = opened.forEach { it.flush() }

    @Test
    fun ringKeepsTheNewestEntries() {
        val log = newLog(null, capacity = 3)
        (1..5).forEach { log.info("hub", "entry $it") }
        assertEquals(listOf("entry 3", "entry 4", "entry 5"), log.snapshot().map { it.message })
    }

    @Test
    fun versionChangesOnEveryEntryAndClear() {
        val log = newLog(null)
        val before = log.version.value
        log.info("hub", "a")
        log.clear()
        assertEquals(before + 2, log.version.value)
        assertTrue(log.snapshot().isEmpty())
    }

    @Test
    fun formatAndParseRoundTripWithStackTraces() {
        val error = IllegalStateException("boom")
        val entries = listOf(
            LogEntry(now, EventLog.Level.INFO, "hub", "Poll: 2 device(s)"),
            LogEntry(now + 5, EventLog.Level.ERROR, "crash", "Uncaught", LogFormat.stackTraceOf(error).trim()),
            LogEntry(now + 9, EventLog.Level.DEBUG, "gree", "-> 192.0.2.10 {\"t\":\"scan\"}"),
        )
        val text = entries.joinToString("") { LogFormat.format(it, zone) }
        val parsed = LogFormat.parse(text, zone)
        assertEquals(entries.map { it.copy(stackTrace = it.stackTrace?.lines()?.joinToString("\n") { l -> l.trim() }) }, parsed)
        assertTrue(parsed[1].stackTrace!!.startsWith("java.lang.IllegalStateException: boom"))
    }

    @Test
    fun messagesStayOnOneLineAndSourcesCannotBreakTheFormat() {
        val text = LogFormat.format(LogEntry(now, EventLog.Level.WARN, "gree:v2", "line one\nline two"), zone)
        assertEquals(1, text.trimEnd().lines().size)
        val parsed = LogFormat.parse(text, zone).single()
        assertEquals("gree_v2", parsed.source)
        assertEquals("line one | line two", parsed.message)
        assertEquals(EventLog.Level.WARN, parsed.level)
    }

    @Test
    fun parseSkipsGarbage() {
        val text = "not a log line\n\tstray stack\n" +
            LogFormat.format(LogEntry(now, EventLog.Level.INFO, "app", "ok"), zone) + "half a li"
        assertEquals(listOf("ok"), LogFormat.parse(text, zone).map { it.message })
    }

    @Test
    fun theFileRollsOverAndKeepsOnePreviousFile() {
        val dir = temp.newFolder("logs")
        val file = RollingLogFile(dir, maxBytes = 300)
        repeat(30) { file.append("line ${it.toString().padStart(3, '0')} ........................\n") }
        assertTrue(file.previous.exists())
        assertTrue(file.current.length() <= 300)
        assertTrue(file.previous.length() <= 300)
        val all = file.readAll()
        assertTrue(all.endsWith("line 029 ........................\n"))
        assertFalse("the oldest lines rolled away", all.contains("line 000"))
        assertEquals(2, dir.listFiles()!!.size)
        file.close()
    }

    @Test
    fun entriesReachTheFileAndComeBackAfterARestart() {
        val dir = temp.newFolder("logs")
        val first = newLog(RollingLogFile(dir))
        first.info("app", "App started")
        first.warn("hub", "Bedroom AC is offline", RuntimeException("no reply"))
        first.flush()

        val second = newLog(RollingLogFile(dir))
        second.loadSaved()
        second.info("app", "App started again")
        second.flush()
        val messages = second.snapshot().map { it.message }
        assertEquals(listOf("App started", "Bedroom AC is offline", "App started again"), messages)
        assertTrue(second.snapshot()[1].stackTrace!!.contains("no reply"))
    }

    @Test
    fun crashEntriesAreWrittenAtOnce() {
        val dir = temp.newFolder("logs")
        val file = RollingLogFile(dir)
        val log = newLog(file)
        log.info("app", "before")
        log.logNow(EventLog.Level.ERROR, LogSource.CRASH, "Uncaught exception", IllegalStateException("bad"))
        val text = file.readAll()
        assertTrue(text.contains("I/app: before"))
        assertTrue(text.contains("E/crash: Uncaught exception"))
        assertTrue(text.contains("\tjava.lang.IllegalStateException: bad"))
    }

    @Test
    fun exportPutsTheHeaderFirstAndClearEmptiesEverything() {
        val dir = temp.newFolder("logs")
        val file = RollingLogFile(dir)
        val log = newLog(file)
        log.info("hub", "Hub started")
        val exported = log.export("HEADER")
        assertTrue(exported.startsWith("HEADER\n"))
        assertTrue(exported.contains("I/hub: Hub started"))

        log.clear()
        log.flush()
        assertTrue(log.snapshot().isEmpty())
        assertFalse(file.current.exists())
        assertEquals("H\n", log.export("H"))
    }

    @Test
    fun traceBuildsItsMessageOnlyWhenVerbose() {
        val log = newLog(null)
        var built = 0
        log.trace("gree") { built++; "frame" }
        assertEquals(0, built)
        log.verbose = true
        log.trace("gree") { built++; "frame" }
        assertEquals(1, built)
        assertEquals(EventLog.Level.DEBUG, log.snapshot().single().level)
    }

    @Test
    fun headerSaysWhichAppPhoneAndPlugins() {
        val header = LogHeader.build(
            appVersion = "0.2.0",
            versionCode = 2,
            buildType = "debug",
            androidRelease = "14",
            sdkInt = 34,
            device = LogHeader.deviceName("google", "Pixel 8"),
            connectors = listOf(
                ConnectorInfo("gree", "Gree air conditioners", "d", setOf(DeviceCategory.CLIMATE), "0.2.0", "Local UDP 7000"),
            ),
            verbose = true,
            createdAt = "2026-10-10 18:00:00.000",
        )
        assertTrue(header.contains("App: 0.2.0 (2, debug)"))
        assertTrue(header.contains("Android: 14 (API 34)"))
        assertTrue(header.contains("Device: Google Pixel 8"))
        assertTrue(header.contains("Gree air conditioners (gree) 0.2.0, Local UDP 7000"))
        assertTrue(header.contains("Verbose logging: on"))
        assertEquals("Samsung SM-X200", LogHeader.deviceName("samsung", "SM-X200"))
        assertEquals("Pixel 8", LogHeader.deviceName("Pixel", "Pixel 8"))
    }
}

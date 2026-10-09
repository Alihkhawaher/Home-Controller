package com.technlov.homecontroller.hub

import com.technlov.home.core.DeviceState
import com.technlov.home.core.SetValue
import com.technlov.home.core.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimisticOverlayTest {
    private val base = DeviceState(
        values = mapOf("power" to Value.Bool(false), "target" to Value.Num(24.0)),
        updatedAtMillis = 1L,
    )

    @Test
    fun nothingPendingReturnsTheBaseItself() {
        assertSame(base, mergeOptimistic(base, emptyList()))
        assertNull(mergeOptimistic(null, emptyList()))
    }

    @Test
    fun pendingChangesWinAndTheNewestWinsLast() {
        val merged = mergeOptimistic(
            base,
            listOf(
                listOf(SetValue("power", Value.Bool(true)), SetValue("target", Value.Num(22.0))),
                listOf(SetValue("target", Value.Num(21.0))),
            ),
        )!!
        assertEquals(Value.Bool(true), merged.values["power"])
        assertEquals(Value.Num(21.0), merged.values["target"])
        assertEquals(base.updatedAtMillis, merged.updatedAtMillis)
    }

    @Test
    fun pendingOnUnknownStateBuildsOne() {
        val merged = mergeOptimistic(null, listOf(listOf(SetValue("power", Value.Bool(true)))))!!
        assertEquals(mapOf("power" to Value.Bool(true)), merged.values)
        assertFalse(merged.online)
    }

    @Test
    fun overlayShowsChangesUntilEachEnds() {
        val overlay = OptimisticOverlay()
        val first = overlay.begin("k", listOf(SetValue("target", Value.Num(22.0))))
        val second = overlay.begin("k", listOf(SetValue("target", Value.Num(20.0))))
        assertTrue(overlay.hasPending("k"))
        assertEquals(Value.Num(20.0), overlay.merge("k", base)!!.values["target"])

        overlay.end("k", second) // the newer one failed: the older one still shows
        assertEquals(Value.Num(22.0), overlay.merge("k", base)!!.values["target"])

        overlay.end("k", first)
        assertFalse(overlay.hasPending("k"))
        assertSame(base, overlay.merge("k", base))
        assertSame(base, overlay.merge("other", base))
    }

    @Test
    fun backoffDoublesAndCaps() {
        assertEquals(0L, Backoff.delayMs(0))
        assertEquals(5_000L, Backoff.delayMs(1))
        assertEquals(10_000L, Backoff.delayMs(2))
        assertEquals(20_000L, Backoff.delayMs(3))
        assertEquals(160_000L, Backoff.delayMs(6))
        assertEquals(300_000L, Backoff.delayMs(7))
        assertEquals(300_000L, Backoff.delayMs(1_000))
    }

    @Test
    fun issuesComeFromErrors() {
        assertEquals(DeviceIssue.Timeout, DeviceIssue.from(DeviceTimeoutException()))
        assertEquals(DeviceIssue.NoConnector("tv"), DeviceIssue.from(MissingConnectorException("tv")))
        assertEquals(DeviceIssue.Failed("boom"), DeviceIssue.from(IllegalStateException("boom")))
        assertEquals(DeviceIssue.Failed("IllegalStateException"), DeviceIssue.from(IllegalStateException()))
    }
}

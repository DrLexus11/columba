package network.columba.app.service.tak

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Several versions of one event, and what they would cost without this.
 *
 * One version of a drawing costs about 7.7 s of channel for a team of seven,
 * and an operator who edits and re-sends produces several. The Kotlin
 * half of `tests/test_cot_coalesce.py`.
 */
class CotCoalesceTest {
    private val gate = LatestWins(windowMs = 10_000)

    private fun frames(tag: Int) = listOf(byteArrayOf(tag.toByte()))

    @Test
    fun `the first version goes at once`() {
        assertEquals(LatestWins.Decision.SEND, gate.decide("D", frames(1), "1", 0).decision)
    }

    @Test
    fun `a change inside the window is held until it closes`() {
        gate.decide("D", frames(1), "1", 0)
        assertEquals(LatestWins.Offer(LatestWins.Decision.HOLD, 10_000), gate.decide("D", frames(2), "2", 3_000))
    }

    @Test
    fun `a drag costs one transfer per window and the last shape goes`() {
        gate.decide("D", frames(0), "0", 0)
        val decisions = (1..20).map { gate.decide("D", frames(it), "$it", it * 100L).decision }

        assertEquals(1, decisions.count { it == LatestWins.Decision.HOLD })
        assertEquals(19, gate.coalesced)
        assertEquals(20, gate.flush("D", 10_000)!!.single()[0].toInt())
    }

    @Test
    fun `an unchanged version is dropped`() {
        gate.decide("D", frames(1), "1", 0)
        assertEquals(LatestWins.Decision.DROP, gate.decide("D", frames(1), "1", 30_000).decision)
    }

    @Test
    fun `a drag back to the sent shape sends nothing more`() {
        gate.decide("D", frames(1), "1", 0)
        gate.decide("D", frames(2), "2", 2_000)
        gate.decide("D", frames(1), "1", 4_000)
        assertNull(gate.flush("D", 10_000))
    }

    @Test
    fun `two drawings do not throttle each other`() {
        gate.decide("A", frames(1), "a", 0)
        assertEquals(LatestWins.Decision.SEND, gate.decide("B", frames(2), "b", 1_000).decision)
    }

    @Test
    fun `the same drawing cut up twice has one digest`() {
        // Every transfer draws a random transfer id into its headers, so
        // hashing the fragments never matched. The first gate had this bug.
        val payload = ByteArray(700) { (it * 31).toByte() }
        val first = CotFragment.fragments(payload, transferId = 1)
        val second = CotFragment.fragments(payload, transferId = 2)

        assertEquals(CotCoalesce.contentDigest(first), CotCoalesce.contentDigest(second))
        assertNotEquals(
            CotCoalesce.contentDigest(first),
            CotCoalesce.contentDigest(CotFragment.fragments(ByteArray(700) { 1 }, transferId = 1)),
        )
    }

    @Test
    fun `attributes are read from the event tag, not its children`() {
        val xml =
            """<?xml version="1.0"?><event version="2.0" uid="DRAW-1" time="2026-09-21T07:00:00Z">""" +
                """<detail><link uid="SOMEONE-ELSE"/><remarks time="2020-01-01T00:00:00Z"/></detail></event>"""

        assertEquals("DRAW-1", CotCoalesce.eventAttribute(xml, "uid"))
        assertEquals("2026-09-21T07:00:00Z", CotCoalesce.eventAttribute(xml, "time"))
    }

    private fun event(time: String) = """<event version="2.0" uid="DRAW-1" type="u-d-f" time="$time"/>"""

    @Test
    fun `an older version arriving late is not drawn`() {
        val fresh = Freshness()
        assertTrue(fresh.admit(event("2026-09-21T07:00:05Z")))
        assertFalse(fresh.admit(event("2026-09-21T07:00:00Z")))
        assertEquals(1, fresh.stale)
    }

    @Test
    fun `the same version again is drawn, and a newer one is`() {
        val fresh = Freshness()
        fresh.admit(event("2026-09-21T07:00:00Z"))
        assertTrue(fresh.admit(event("2026-09-21T07:00:00Z")))
        assertTrue(fresh.admit(event("2026-09-21T07:00:05Z")))
    }

    @Test
    fun `times with different precision still order`() {
        val fresh = Freshness()
        fresh.admit(event("2026-09-21T07:00:00.5Z"))
        assertFalse(fresh.admit(event("2026-09-21T07:00:00.123Z")))
    }

    @Test
    fun `an unplaceable event is admitted`() {
        val fresh = Freshness()
        assertTrue(fresh.admit("<event/>"))
        assertTrue(fresh.admit(event("not a time")))
    }

    @Test
    fun `a held version waits for the window, then goes`() =
        runTest {
            val versions = CotVersions(LatestWins(windowMs = 10_000), clock = { testScheduler.currentTime })
            versions.attach(this)
            val sent = mutableListOf<Int>()
            val xml = event("2026-09-21T07:00:00Z")

            versions.submit(xml, frames(1)) { sent += it.single()[0].toInt() }
            versions.submit(xml, frames(2)) { sent += it.single()[0].toInt() }
            versions.submit(xml, frames(3)) { sent += it.single()[0].toInt() }
            assertEquals("the first version goes at once", listOf(1), sent)

            advanceTimeBy(9_000)
            assertEquals("held until the window closes", listOf(1), sent)

            advanceTimeBy(2_000)
            assertEquals("then only the latest", listOf(1, 3), sent)
        }

    @Test
    fun `nothing is sent for an empty version`() =
        runTest {
            var sent = false
            CotVersions().submit(event("2026-09-21T07:00:00Z"), emptyList()) { sent = true }
            assertFalse(sent)
        }
}

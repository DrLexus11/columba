package network.columba.app.service.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long a silence from ATAK must last before Columba reports in its place.
 *
 * Overnight 2026-09-22 a stationary ATAK reported every three minutes against
 * a fixed two-minute window, so Columba stepped in every three minutes and both
 * went on the air, all night.
 */
class AtakCadenceTest {
    private var now = 1_000_000L
    private val cadence = AtakCadence { now }
    private val minute = 60_000L

    @Test
    fun `a stationary atak reporting every three minutes is left alone`() {
        cadence.connected()
        cadence.reported()
        now += 3 * minute
        cadence.reported()

        now += 2 * minute + 30_000
        assertTrue("half a minute before its next report, it is still reporting", cadence.isReporting(true))
        now += minute
        cadence.reported()
        assertTrue(cadence.isReporting(true))
    }

    @Test
    fun `an atak that stops reporting is replaced after half again its cadence`() {
        cadence.reported()
        now += 3 * minute
        cadence.reported()

        now += 4 * minute + 31_000
        assertFalse(cadence.isReporting(true))
    }

    @Test
    fun `an atak with no fix, which never reports, is replaced after the minimum`() {
        cadence.connected()
        now += AtakCadence.MIN_QUIET_MS - 1
        assertTrue(cadence.isReporting(true))
        now += 2
        assertFalse(cadence.isReporting(true))
    }

    @Test
    fun `a moving atak keeps the minimum window`() {
        cadence.reported()
        now += 5_000
        cadence.reported()
        assertEquals(AtakCadence.MIN_QUIET_MS, cadence.quietWindowMs())
    }

    @Test
    fun `however slow atak has been, ten minutes silent is not reporting`() {
        cadence.reported()
        now += 30 * minute
        cadence.reported()
        assertEquals(AtakCadence.MAX_QUIET_MS, cadence.quietWindowMs())
    }

    @Test
    fun `a disconnected atak is not reporting`() {
        cadence.reported()
        assertFalse(cadence.isReporting(false))
    }
}

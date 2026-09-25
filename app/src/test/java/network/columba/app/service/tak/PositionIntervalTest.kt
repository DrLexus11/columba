package network.columba.app.service.tak

import network.columba.app.service.PositionCodec
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * A handset that reports every few minutes stays current between reports.
 *
 * Columba reports its own position while ATAK is closed, every few minutes on
 * purpose, and states the interval in the report. Drawn with the one-minute
 * default, that track went grey between every pair of reports. The Kotlin half
 * of `tests/test_cot_position_interval.py`.
 */
class PositionIntervalTest {
    private val team = "Cyan"
    private val secret = "a fleet secret long enough to be used".toByteArray(Charsets.UTF_8)
    private val ours = ByteArray(16) { 0x44 }
    private val peer = ByteArray(16) { 0x33 }
    private val defaultStaleMs = 120_000L

    private val registry =
        TakMembership.Registry(team, secret, ownHash = ours).also {
            it.remember(peer, TakMembership.memberPayload(team, secret, "PEER"), 1_000)
        }
    private val renderer = CotRenderer(registry, team, ours, positionStaleMs = defaultStaleMs, chatStaleMs = 600_000)

    private fun believedForMs(intervalMin: Int): Long {
        val fix =
            PositionCodec.Fix(
                latE7 = 410000000, lonE7 = 290000000,
                senderId = TakMembership.senderIdFor(peer), intervalMin = intervalMin,
            )
        val xml = (renderer.render(PositionCodec.encode(fix), 2_000) as CotRenderer.Rendered.Cot).xml
        val stamp = { name: String -> Regex("""\b$name="([^"]+)"""").find(xml)!!.groupValues[1] }
        val format =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return format.parse(stamp("stale").take(19))!!.time - format.parse(stamp("time").take(19))!!.time
    }

    @Test
    fun `a report that states no interval keeps the default`() {
        assertNear(defaultStaleMs, believedForMs(0), 1_000L)
    }

    @Test
    fun `a five minute reporter is current for two reports`() {
        assertNear(10 * 60_000L, believedForMs(5), 1_000L)
    }

    @Test
    fun `a stated interval never shortens the default`() {
        assertNear(defaultStaleMs, believedForMs(1), 1_000L)
    }

    private fun assertNear(expected: Long, actual: Long, toleranceMs: Long) {
        assertEquals(expected.toDouble(), actual.toDouble(), toleranceMs.toDouble())
    }
}

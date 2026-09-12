package network.columba.app.service.tak

import network.columba.app.service.PositionCodec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Position as twenty-one bytes, not as seven hundred.
 *
 * The Kotlin half of `tools/cot_position.py`. 794 of the 853 events ATAK
 * authored in this lab are position reports -- 94% of everything it emits. PR A
 * put all of them through tier 2 as compressed CoT, and pivot 5 then addressed
 * every event to every member, which multiplies. Measured with the firmware's
 * own airtime model at SF7/BW250, ten nodes over four hops, one report each per
 * minute:
 *
 *     as tier 2 CoT     85% of the channel
 *     as this codec     32% of the channel
 *
 * Neither is survivable, which is the part worth keeping: the codec is
 * necessary and not sufficient. What makes position affordable is sending fewer
 * of them, so [PositionGate] matters as much as the encoding and applies the
 * same two rules the firmware applies -- a floor between reports, and a
 * movement threshold so a node that has actually moved does not sit behind that
 * floor, because that is exactly when its position matters.
 *
 * The wire format is [PositionCodec], unchanged: the firmware, the deck bridge
 * and this all speak the same nineteen-to-twenty-four bytes. A second dialect
 * of the same thing is how two implementations start disagreeing about where
 * somebody is.
 */
object CotPosition {
    /**
     * ATAK's own position types. The tail varies with affiliation and battle
     * dimension -- a-f-G-U-C is a friendly ground unit, a-h-G-U-C a hostile one
     * -- and all of them are somebody reporting where a unit is.
     */
    private const val PLI_TYPE_PREFIX = "a-"
    private const val PLI_TYPE_INFIX = "-U-"

    /** What ATAK writes when it has no value. Not a measurement. */
    private const val UNKNOWN = 9999999.0

    /**
     * Matches POSITION_MOVE_THRESHOLD_M in PositionReport.h, and for the reason
     * given there: 1e-7 degrees of latitude is about 1.11 cm, the same scale is
     * applied to longitude, and away from the equator that makes the gate fire
     * early rather than late. Reporting movement that did not happen is the
     * safe direction; missing movement that did is not.
     */
    const val MOVE_THRESHOLD_M = 25
    const val MOVE_THRESHOLD_E7 = (MOVE_THRESHOLD_M * 10_000_000L / 111_320L).toInt()

    /**
     * The floor between two reports from this node.
     *
     * A floor rather than a schedule: nothing here generates reports, it only
     * declines to forward the ones ATAK produces faster than this.
     */
    const val DEFAULT_INTERVAL_MS = 60_000L

    /** Whether this event is somebody reporting a unit's position. */
    fun isPosition(cotXml: String): Boolean {
        val event = try {
            CotEvent.parse(cotXml)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val kind = event.getAttribute("type")
        return kind.startsWith(PLI_TYPE_PREFIX) && kind.contains(PLI_TYPE_INFIX)
    }

    /**
     * A [PositionCodec.Fix] from a CoT event, or null if it carries none.
     *
     * Only the fields the wire format has room for are taken; everything else
     * in the event is rendered locally at the far end and never travels.
     */
    fun fixFromCot(cotXml: String, senderId: Int): PositionCodec.Fix? {
        val event = try {
            CotEvent.parse(cotXml)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val point = childElement(event, "point") ?: return null
        val latitude = point.getAttribute("lat").toDoubleOrNull() ?: return null
        val longitude = point.getAttribute("lon").toDoubleOrNull() ?: return null
        val altitude = measured(point.getAttribute("hae"))
        val track = childElement(event, "detail")?.let { childElement(it, "track") }
        val course = track?.let { measured(it.getAttribute("course")) }
        val speed = track?.let { measured(it.getAttribute("speed")) }
        return PositionCodec.Fix(
            latE7 = Math.round(latitude * 1e7).toInt(),
            lonE7 = Math.round(longitude * 1e7).toInt(),
            senderId = senderId,
            fixUnixSeconds = 0,
            // 0 is the format's own word for unreported. ATAK's 9999999 as
            // metres of accuracy would be a claim about the whole planet.
            accuracyM = measured(point.getAttribute("ce"))
                ?.let { Math.round(it).toInt().coerceIn(1, 254) } ?: 0,
            altKnown = altitude != null && Math.abs(altitude) < 32_000,
            altM = altitude?.let { Math.round(it).toInt() } ?: 0,
            courseKnown = course != null,
            // CoT states a heading in degrees; the wire format carries tenths
            // (PositionReport.h scales by 3600 per turn, not 360, and
            // PositionCodec.fromLocation already writes bearing * 10). Storing
            // degrees unscaled made 180 encode and decode as 18, and small
            // headings round to 0 -- a silent error in the one field where a
            // wrong direction is unrecoverable by the person reading the map.
            courseDdeg = course?.let { (Math.round(it * 10).toInt() % 3600 + 3600) % 3600 } ?: 0,
            speedCms = speed?.takeIf { it > 0 }?.let { Math.round(it * 100).toInt() } ?: 0,
        )
    }

    /**
     * One CoT event from one decoded report, for the local ATAK.
     *
     * `stale` is the field that matters most: it is what makes ATAK drop a
     * track it can no longer trust, so it follows the reporting cadence. Too
     * short and every marker flickers out between reports; too long and a node
     * that went off the air an hour ago is still on the map looking current.
     */
    fun buildCot(
        fix: PositionCodec.Fix,
        uid: String,
        callsign: String,
        staleMs: Long,
        receivedAtMs: Long = System.currentTimeMillis(),
        team: String = "Cyan",
    ): String {
        // Prefer the time the fix was taken. A node with no clock sends zero,
        // and then the only honest stamp is when we received it -- later than
        // the truth, never earlier, so a marker errs toward looking older
        // rather than fresher than it is.
        val takenMs = if (fix.fixUnixSeconds > 0) fix.fixUnixSeconds * 1000 else receivedAtMs
        val detail = StringBuilder()
        // The endpoint is what makes a peer *addressable* in ATAK rather than
        // just visible. Without it a track appears on the map and the same peer
        // is absent from the contact list -- the list an operator picks from to
        // start a chat, send a marker, or dispatch a CASEVAC.
        detail.append("<contact callsign=\"").append(escape(callsign))
            .append("\" endpoint=\"*:-1:stcp\"/>")
        // The team this node is on, not a constant. Hard-coding "Cyan" put
        // every peer in the wrong group on any other team, and group colour is
        // how an operator tells their own people apart at a glance.
        detail.append("<__group name=\"").append(escape(team)).append("\" role=\"Team Member\"/>")
        if (fix.altKnown) detail.append("<precisionlocation altsrc=\"GPS\"/>")
        // Track goes in only when there is something to say. An absent track
        // reads as "not reported"; a track of zero reads as stationary and
        // facing north, which is a different claim.
        if (fix.courseKnown || fix.speedCms > 0) {
            detail.append("<track course=\"")
                .append("%.1f".format(Locale.US, fix.courseDdeg / 10.0))
                .append("\" speed=\"").append("%.2f".format(Locale.US, fix.speedCms / 100.0))
                .append("\"/>")
        }
        return "<event version=\"2.0\" uid=\"${escape(uid)}\" type=\"a-f-G-U-C\" how=\"m-g\"" +
            " time=\"${stamp(receivedAtMs)}\" start=\"${stamp(takenMs)}\"" +
            " stale=\"${stamp(receivedAtMs + staleMs)}\">" +
            "<point lat=\"${"%.7f".format(Locale.US, fix.latE7 / 1e7)}\"" +
            " lon=\"${"%.7f".format(Locale.US, fix.lonE7 / 1e7)}\"" +
            " hae=\"${"%.1f".format(Locale.US, if (fix.altKnown) fix.altM.toDouble() else UNKNOWN)}\"" +
            // 255 on the wire means "worse than 254 m" rather than exactly 255,
            // but CoT has no way to say that, so the number passes through and
            // the operator sees a large error rather than a precise one.
            " ce=\"${"%.1f".format(Locale.US, if (fix.accuracyM > 0) fix.accuracyM.toDouble() else UNKNOWN)}\"" +
            " le=\"${"%.1f".format(Locale.US, UNKNOWN)}\"/>" +
            "<detail>$detail</detail></event>"
    }

    private fun measured(text: String?): Double? {
        val value = text?.toDoubleOrNull() ?: return null
        // ATAK's sentinel for "not known". Treating it as a measurement is how
        // a track ends up nine thousand kilometres in the air.
        return if (Math.abs(value) >= UNKNOWN) null else value
    }

    private fun childElement(parent: org.w3c.dom.Element, name: String): org.w3c.dom.Element? {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is org.w3c.dom.Element && child.tagName == name) return child
        }
        return null
    }

    private fun stamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))

    private fun escape(value: String) =
        value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;")

    /**
     * Decides whether a position is worth its airtime yet.
     *
     * The same two rules the firmware applies, for the same reasons: a floor
     * between reports so a chatty client cannot spend the channel, and a
     * movement threshold so a node that has actually moved does not sit behind
     * that floor.
     */
    class PositionGate(
        private val intervalMs: Long = DEFAULT_INTERVAL_MS,
        private val moveThresholdE7: Int = MOVE_THRESHOLD_E7,
    ) {
        private var lastSent: Long? = null
        private var lastLatE7: Int? = null
        private var lastLonE7: Int? = null

        var suppressed: Long = 0L
            private set

        /** True if this fix should go out, and records it if so. */
        fun allows(fix: PositionCodec.Fix, now: Long): Boolean {
            val lastLat = lastLatE7
            val lastLon = lastLonE7
            val moved = lastLat != null && lastLon != null &&
                (Math.abs(fix.latE7.toLong() - lastLat) >= moveThresholdE7 ||
                    Math.abs(fix.lonE7.toLong() - lastLon) >= moveThresholdE7)
            val due = lastSent?.let { now - it >= intervalMs } ?: true
            if (!due && !moved) {
                suppressed++
                return false
            }
            lastSent = now
            lastLatE7 = fix.latE7
            lastLonE7 = fix.lonE7
            return true
        }
    }
}

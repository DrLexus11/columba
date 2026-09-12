package network.columba.app.service.tak

import org.w3c.dom.Element
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.UUID

/**
 * Point markers as tens of bytes: the last of the 850 typed events.
 *
 * The Kotlin half of `tools/cot_marker.py`. Measured against real events from
 * this lab:
 *
 *     a-h-G        759 B -> 57 B     b-m-p-s-m    799 B -> 53 B
 *     b-m-p-c-cp   714 B -> 65 B     b-m-p-s-p-i  561 B -> 45 B
 *
 * Almost all of a marker is rebuildable at the far end. `creator` and `link`
 * both name the sender, which the receiver already knows from the packet;
 * `usericon` is a path derived from the type; `status`, `archive` and an empty
 * `remarks` say nothing. What is irreducible is where it is, what it is, what
 * it is called, and how long it lives.
 *
 * **SPI is the volume case and the one that leaks.** ATAK names it
 * `ANDROID-<device id>.SPI1`, so forwarding the uid verbatim would put the
 * sender's device identifier on the air -- exactly what pivot 1 removed from
 * everything else. A uid that is not a UUID travels as its suffix alone and is
 * rebuilt against the sender's Reticulum-rooted UID.
 */
object CotMarker {
    const val VERSION = TakPayload.MARKER_V1

    const val FLAG_ALT = 0x01
    const val FLAG_COLOR = 0x02
    const val FLAG_REMARKS = 0x04

    /** Set when the uid is a UUID in sixteen raw bytes; clear when it is a suffix. */
    const val FLAG_UUID = 0x08

    /**
     * Set when the stale field counts minutes rather than seconds.
     *
     * Sixteen bits of seconds is eighteen hours, and ATAK writes a stale a year
     * out for a spot marker -- which clamped, quietly turning a permanent
     * marker into one that vanished overnight. Seconds where they fit, because
     * an SPI lives twenty of them; minutes where they do not, which reaches
     * forty-five years.
     */
    const val FLAG_STALE_MINUTES = 0x10

    const val MAX_TYPE = 32
    const val MAX_CALLSIGN = 64
    const val MAX_UID_SUFFIX = 32
    const val MAX_REMARKS = 200
    const val MAX_STALE_UNITS = 0xFFFF
    const val HEADER_BYTES = 1 + 1 + 4 + 4 + 4 + 2 + 1 + 1 + 1

    private const val UNKNOWN = 9999999.0
    private const val SECONDS_PER_MINUTE = 60

    /**
     * The pointer ATAK drags across the map. Named once because the caller
     * rate limits on it, and a second copy of the string is how the two drift.
     */
    const val SPI_TYPE = "b-m-p-s-p-i"

    data class Marker(
        val senderId: Int,
        /** Null when the uid is a suffix to be rebuilt against the sender. */
        val uid: String?,
        val uidSuffix: String?,
        val type: String,
        val callsign: String,
        val latE7: Int,
        val lonE7: Int,
        val staleSeconds: Int,
        val altM: Int?,
        val argb: Int?,
        val remarks: String,
    )

    /** Whether this event is a single point somebody put on the map. */
    fun isMarker(cotXml: String): Boolean {
        val event = try {
            CotEvent.parse(cotXml)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val kind = event.getAttribute("type")
        if (childElement(event, "point") == null) return false
        // A unit self-report belongs to the position codec, which has its own
        // cadence; sending it both ways would double the commonest event there is.
        if (kind.startsWith("a-") && kind.contains("-U-")) return false
        // Drawings and routes are more than a point, and are PR C's problem.
        if (kind.startsWith("u-d-") || kind.startsWith("b-m-r") || kind.startsWith("u-r-")) {
            return false
        }
        return kind.startsWith("a-") || kind.startsWith("b-m-p-")
    }

    /** Pack a marker, or null if this event is not one. */
    fun markerFromCot(cotXml: String, senderId: Int): ByteArray? {
        if (!isMarker(cotXml)) return null
        val event = try {
            CotEvent.parse(cotXml)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val point = childElement(event, "point") ?: return null
        val latitude = point.getAttribute("lat").toDoubleOrNull() ?: return null
        val longitude = point.getAttribute("lon").toDoubleOrNull() ?: return null
        val detail = childElement(event, "detail")
        val callsign = detail?.let { childElement(it, "contact") }?.getAttribute("callsign").orEmpty()
        val remarks = detail?.let { childElement(it, "remarks") }?.textContent.orEmpty()
        val argb = detail?.let { childElement(it, "color") }?.getAttribute("argb")?.toIntOrNull()
        val altitude = measured(point.getAttribute("hae"))

        var flags = 0
        val uid = event.getAttribute("uid")
        var uuidBytes: ByteArray? = null
        var suffix = ByteArray(0)
        val parsed = runCatching { UUID.fromString(uid) }.getOrNull()
        if (parsed != null) {
            flags = flags or FLAG_UUID
            uuidBytes = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                .putLong(parsed.mostSignificantBits).putLong(parsed.leastSignificantBits).array()
        } else {
            // Not a UUID. Carry only the part after the last dot -- "SPI1" --
            // and leave the sender's device identifier behind.
            val tail = if (uid.contains('.')) uid.substringAfterLast('.') else uid
            suffix = tail.toByteArray(Charsets.UTF_8)
            if (suffix.isEmpty() || suffix.size > MAX_UID_SUFFIX) return null
        }

        if (altitude != null && Math.abs(altitude) < 32_000) flags = flags or FLAG_ALT
        if (argb != null) flags = flags or FLAG_COLOR
        val remarksBytes = fitUtf8(remarks, MAX_REMARKS)
        if (remarksBytes.isNotEmpty()) flags = flags or FLAG_REMARKS

        val typeBytes = event.getAttribute("type").toByteArray(Charsets.UTF_8)
        val callsignBytes = callsign.toByteArray(Charsets.UTF_8)
        if (typeBytes.isEmpty() || typeBytes.size > MAX_TYPE) return null
        if (callsignBytes.size > MAX_CALLSIGN) return null

        val (staleValue, inMinutes) = staleField(staleSeconds(event))
        if (inMinutes) flags = flags or FLAG_STALE_MINUTES

        val extras = (if (flags and FLAG_ALT != 0) 2 else 0) +
            (if (flags and FLAG_COLOR != 0) 4 else 0) +
            (if (flags and FLAG_REMARKS != 0) 1 + remarksBytes.size else 0)
        val buffer = ByteBuffer.allocate(
            HEADER_BYTES + (uuidBytes?.size ?: suffix.size) +
                typeBytes.size + callsignBytes.size + extras,
        ).order(ByteOrder.BIG_ENDIAN)
        buffer.put(VERSION.toByte()).put(flags.toByte()).putInt(senderId)
            .putInt(Math.round(latitude * 1e7).toInt())
            .putInt(Math.round(longitude * 1e7).toInt())
            .putShort(staleValue.toShort())
            .put(typeBytes.size.toByte()).put(callsignBytes.size.toByte())
            .put(suffix.size.toByte())
        buffer.put(uuidBytes ?: suffix).put(typeBytes).put(callsignBytes)
        if (flags and FLAG_ALT != 0) buffer.putShort(Math.round(altitude!!).toInt().toShort())
        if (flags and FLAG_COLOR != 0) buffer.putInt(argb!!)
        if (flags and FLAG_REMARKS != 0) {
            buffer.put(remarksBytes.size.toByte()).put(remarksBytes)
        }
        return buffer.array()
    }

    /** Unpack a marker, or null for anything that is not one. */
    @Suppress("ReturnCount")
    fun decode(frame: ByteArray?): Marker? {
        if (frame == null || frame.size < HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        if ((buffer.get().toInt() and 0xFF) != VERSION) return null
        val flags = buffer.get().toInt() and 0xFF
        val senderId = buffer.int
        val latE7 = buffer.int
        val lonE7 = buffer.int
        val stale = buffer.short.toInt() and 0xFFFF
        val typeLength = buffer.get().toInt() and 0xFF
        val callsignLength = buffer.get().toInt() and 0xFF
        val suffixLength = buffer.get().toInt() and 0xFF
        if (typeLength !in 1..MAX_TYPE || callsignLength > MAX_CALLSIGN) return null

        var uid: String? = null
        var suffixText: String? = null
        if (flags and FLAG_UUID != 0) {
            // A frame claiming both a UUID and a suffix is not one we produced.
            if (suffixLength != 0 || buffer.remaining() < 16) return null
            uid = UUID(buffer.long, buffer.long).toString()
        } else {
            if (suffixLength !in 1..MAX_UID_SUFFIX || buffer.remaining() < suffixLength) return null
            val raw = ByteArray(suffixLength).also { buffer.get(it) }
            suffixText = decodeStrict(raw) ?: return null
        }
        if (buffer.remaining() < typeLength + callsignLength) return null
        val type = decodeStrict(ByteArray(typeLength).also { buffer.get(it) }) ?: return null
        val callsign = decodeStrict(ByteArray(callsignLength).also { buffer.get(it) }) ?: return null

        var altitude: Int? = null
        if (flags and FLAG_ALT != 0) {
            if (buffer.remaining() < 2) return null
            altitude = buffer.short.toInt()
        }
        var argb: Int? = null
        if (flags and FLAG_COLOR != 0) {
            if (buffer.remaining() < 4) return null
            argb = buffer.int
        }
        var remarks = ""
        if (flags and FLAG_REMARKS != 0) {
            if (buffer.remaining() < 1) return null
            val length = buffer.get().toInt() and 0xFF
            if (buffer.remaining() < length) return null
            remarks = decodeStrict(ByteArray(length).also { buffer.get(it) }) ?: return null
        }
        // Trailing bytes mean this is not the frame it claims to be.
        if (buffer.remaining() != 0) return null
        return Marker(
            senderId = senderId, uid = uid, uidSuffix = suffixText, type = type,
            callsign = callsign, latE7 = latE7, lonE7 = lonE7,
            staleSeconds = if (flags and FLAG_STALE_MINUTES != 0) {
                stale * SECONDS_PER_MINUTE
            } else {
                stale
            },
            altM = altitude, argb = argb, remarks = remarks,
        )
    }

    /**
     * Rebuild an event ATAK accepts.
     *
     * `creator` and `link` are rebuilt from the sender rather than carried,
     * because the receiver already knows who sent it -- and rebuilding them
     * against the Reticulum-rooted UID is what keeps a device identifier off
     * the map at this end too.
     */
    fun buildMarkerCot(
        marker: Marker,
        senderUid: String,
        senderCallsign: String,
        whenIso: String,
        staleIso: String,
    ): String {
        val uid = marker.uid ?: "$senderUid.${marker.uidSuffix}"
        val detail = StringBuilder()
        detail.append(
            "<contact callsign=\"${escape(marker.callsign.ifEmpty { senderCallsign })}\"/>",
        )
        detail.append(
            "<creator callsign=\"${escape(senderCallsign)}\" type=\"a-f-G-U-C\" " +
                "uid=\"${escape(senderUid)}\"/>",
        )
        detail.append(
            "<link parent_callsign=\"${escape(senderCallsign)}\" relation=\"p-p\" " +
                "type=\"a-f-G-U-C\" uid=\"${escape(senderUid)}\"/>",
        )
        marker.argb?.let { detail.append("<color argb=\"$it\"/>") }
        if (marker.remarks.isNotEmpty()) {
            detail.append("<remarks>${escape(marker.remarks)}</remarks>")
        }
        if (marker.altM != null) detail.append("<precisionlocation altsrc=\"GPS\"/>")
        detail.append("<archive/>")
        val altitude = marker.altM?.toDouble() ?: UNKNOWN
        return "<event version=\"2.0\" uid=\"${escape(uid)}\" type=\"${escape(marker.type)}\"" +
            " how=\"h-g-i-g-o\" time=\"$whenIso\" start=\"$whenIso\" stale=\"$staleIso\">" +
            "<point lat=\"${"%.7f".format(java.util.Locale.US, marker.latE7 / 1e7)}\"" +
            " lon=\"${"%.7f".format(java.util.Locale.US, marker.lonE7 / 1e7)}\"" +
            " hae=\"${"%.1f".format(java.util.Locale.US, altitude)}\"" +
            " ce=\"${"%.1f".format(java.util.Locale.US, UNKNOWN)}\"" +
            " le=\"${"%.1f".format(java.util.Locale.US, UNKNOWN)}\"/>" +
            "<detail>$detail</detail></event>"
    }

    /**
     * The two-byte stale value and whether it counts minutes.
     *
     * Seconds while they fit, so an SPI's twenty seconds survives exactly.
     * Minutes beyond, which is the only way a year-long marker crosses in two
     * bytes without becoming eighteen hours.
     */
    fun staleField(seconds: Int): Pair<Int, Boolean> {
        // Clamped at one rather than trusted. Python's ints are arbitrary
        // precision and Kotlin's are not, so an absurd duration that merely
        // looked large on one side arrives here negative -- and a negative
        // would sail through the seconds branch and encode as a huge unsigned
        // stale, which is a marker that never expires.
        if (seconds < 1) return 1 to false
        if (seconds <= MAX_STALE_UNITS) return seconds to false
        val minutes = (seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE
        return minOf(minutes, MAX_STALE_UNITS) to true
    }

    /**
     * How long the author said this marker is good for.
     *
     * Taken from the event rather than invented: a spot marker is good for a
     * year and an SPI for twenty seconds, and giving them the same life would
     * either clutter a map permanently or blink a pointer out of existence.
     */
    private fun staleSeconds(event: Element): Int {
        val start = event.getAttribute("start")
        val stale = event.getAttribute("stale")
        if (start.isEmpty() || stale.isEmpty()) return 300
        return try {
            val begin = Instant.parse(start)
            val end = Instant.parse(stale)
            maxOf(1, (end.epochSecond - begin.epochSecond).toInt())
        } catch (_: Exception) {
            300
        }
    }

    private fun measured(text: String?): Double? {
        val value = text?.toDoubleOrNull() ?: return null
        // ATAK's sentinel for "not known". Treating it as a measurement is how
        // a marker ends up nine thousand kilometres in the air.
        return if (Math.abs(value) >= UNKNOWN) null else value
    }

    private fun decodeStrict(raw: ByteArray): String? {
        val text = raw.toString(Charsets.UTF_8)
        // Kotlin substitutes U+FFFD for invalid UTF-8 rather than throwing.
        return if (text.toByteArray(Charsets.UTF_8).contentEquals(raw)) text else null
    }

    /**
     * Shown when a note was cut, so a reader can tell a truncated remark from
     * one that simply ended. Three bytes, reserved from the limit rather than
     * added to it.
     */
    private val ELLIPSIS = "\u2026".toByteArray(Charsets.UTF_8)

    /**
     * The note as UTF-8, cut at a character boundary if it is too long.
     *
     * Slicing encoded bytes at a fixed index splits multibyte characters, and
     * the far end decodes strictly -- so one Turkish character landing on the
     * boundary made decode() reject the frame and the marker vanished with no
     * error anywhere. An operator loses the marker, not the tail of a
     * sentence, and never learns why.
     *
     * Remarks truncate where chat text refuses (see [CotChat.encode]) because
     * they are different things: a chat line *is* its text, so cutting it
     * destroys the message, while a note annotates a marker whose position and
     * type are the payload. Losing the marker to save the note is the wrong
     * trade.
     */
    private fun fitUtf8(text: String, limit: Int): ByteArray {
        val raw = text.toByteArray(Charsets.UTF_8)
        if (raw.size <= limit) return raw
        var cut = limit - ELLIPSIS.size
        // A UTF-8 continuation byte is 0b10xxxxxx. While the first excluded
        // byte is one, the cut is inside a character; step back until it is not.
        while (cut > 0 && (raw[cut].toInt() and 0xC0) == 0x80) cut--
        return raw.copyOf(cut) + ELLIPSIS
    }

    private fun childElement(parent: Element, name: String): Element? {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element && child.tagName == name) return child
        }
        return null
    }

    private fun escape(value: String) =
        value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;")
}

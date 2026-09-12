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
        val event =
            try {
                CotEvent.parse(cotXml)
            } catch (_: IllegalArgumentException) {
                null
            }
        return event != null &&
            childElement(event, "point") != null &&
            isMarkerType(event.getAttribute("type"))
    }

    /**
     * Whether a CoT type is a single point somebody put on the map.
     *
     * The exclusions come first and each has its own reason, so they are named
     * separately rather than folded into one condition -- a new exclusion is a
     * line here instead of another early return in [isMarker].
     */
    private fun isMarkerType(kind: String): Boolean {
        // A unit self-report belongs to the position codec, which has its own
        // cadence; sending it both ways would double the commonest event there is.
        val selfReport = kind.startsWith("a-") && kind.contains("-U-")
        // Drawings and routes are more than a point, and are PR C's problem.
        val drawing =
            kind.startsWith("u-d-") || kind.startsWith("b-m-r") || kind.startsWith("u-r-")
        val point = kind.startsWith("a-") || kind.startsWith("b-m-p-")
        return point && !selfReport && !drawing
    }

    /**
     * Pack a marker, or null if this event is not one.
     *
     * The packing lives in [Packer], which refuses with [require] rather than
     * by returning: a binary format has a dozen ways to not fit, and one catch
     * at this boundary reads better than a dozen early returns threaded through
     * the writer. Same shape as [CotEvent.parse], which this file already
     * leans on.
     */
    fun markerFromCot(cotXml: String, senderId: Int): ByteArray? {
        if (!isMarker(cotXml)) return null
        return try {
            Packer(CotEvent.parse(cotXml), senderId).pack()
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Builds one frame from one event.
     *
     * A class rather than a chain of functions so the fields are read once and
     * then referred to, and so the flag byte and the buffer size -- which have
     * to agree exactly or the frame is malformed -- are computed from the same
     * values in one place.
     */
    private class Packer(private val event: org.w3c.dom.Element, private val senderId: Int) {
        private val point = requireNotNull(childElement(event, "point")) { "no point" }
        private val detail = childElement(event, "detail")

        private val latitude =
            requireNotNull(point.getAttribute("lat").toDoubleOrNull()) { "no latitude" }
        private val longitude =
            requireNotNull(point.getAttribute("lon").toDoubleOrNull()) { "no longitude" }
        private val altitude = measured(point.getAttribute("hae"))
        private val argb =
            detail?.let { childElement(it, "color") }?.getAttribute("argb")?.toIntOrNull()

        private val type = event.getAttribute("type").toByteArray(Charsets.UTF_8)
        private val callsign =
            detail?.let { childElement(it, "contact") }?.getAttribute("callsign")
                .orEmpty().toByteArray(Charsets.UTF_8)
        private val remarks =
            fitUtf8(
                detail?.let { childElement(it, "remarks") }?.textContent.orEmpty(),
                MAX_REMARKS,
            )

        /**
         * How the uid travels: sixteen raw bytes for a UUID, else the tail.
         *
         * ATAK's marker uids are UUIDs, which cost sixteen bytes rather than
         * the thirty-six the text form does. Anything else keeps only the part
         * after the last dot -- "SPI1" -- which leaves the sender's device
         * identifier behind rather than putting it on the air.
         */
        private val uuid: ByteArray? =
            runCatching { UUID.fromString(event.getAttribute("uid")) }.getOrNull()?.let {
                ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                    .putLong(it.mostSignificantBits)
                    .putLong(it.leastSignificantBits)
                    .array()
            }
        private val suffix: ByteArray =
            if (uuid != null) {
                ByteArray(0)
            } else {
                val uid = event.getAttribute("uid")
                val tail = if (uid.contains('.')) uid.substringAfterLast('.') else uid
                tail.toByteArray(Charsets.UTF_8)
            }

        private val stale = staleField(staleSeconds(event))

        private val flags: Int = run {
            var value = 0
            if (uuid != null) value = value or FLAG_UUID
            if (altitude != null && Math.abs(altitude) < 32_000) value = value or FLAG_ALT
            if (argb != null) value = value or FLAG_COLOR
            if (remarks.isNotEmpty()) value = value or FLAG_REMARKS
            if (stale.second) value = value or FLAG_STALE_MINUTES
            value
        }

        /** Every limit the format imposes, checked before a byte is written. */
        private fun requireFits() {
            require(type.isNotEmpty() && type.size <= MAX_TYPE) { "type does not fit" }
            require(callsign.size <= MAX_CALLSIGN) { "callsign does not fit" }
            if (uuid == null) {
                require(suffix.isNotEmpty() && suffix.size <= MAX_UID_SUFFIX) {
                    "uid suffix does not fit"
                }
            }
        }

        fun pack(): ByteArray {
            requireFits()
            val extras = (if (flags and FLAG_ALT != 0) 2 else 0) +
                (if (flags and FLAG_COLOR != 0) 4 else 0) +
                (if (flags and FLAG_REMARKS != 0) 1 + remarks.size else 0)
            val buffer =
                ByteBuffer.allocate(
                    HEADER_BYTES + (uuid?.size ?: suffix.size) +
                        type.size + callsign.size + extras,
                ).order(ByteOrder.BIG_ENDIAN)
            buffer.put(VERSION.toByte()).put(flags.toByte()).putInt(senderId)
                .putInt(Math.round(latitude * 1e7).toInt())
                .putInt(Math.round(longitude * 1e7).toInt())
                .putShort(stale.first.toShort())
                .put(type.size.toByte()).put(callsign.size.toByte())
                .put(suffix.size.toByte())
            buffer.put(uuid ?: suffix).put(type).put(callsign)
            if (flags and FLAG_ALT != 0) {
                buffer.putShort(Math.round(altitude!!).toInt().toShort())
            }
            if (flags and FLAG_COLOR != 0) buffer.putInt(argb!!)
            if (flags and FLAG_REMARKS != 0) {
                buffer.put(remarks.size.toByte()).put(remarks)
            }
            return buffer.array()
        }
    }

    /**
     * Unpack a marker, or null for anything that is not one.
     *
     * The reading lives in [FrameReader] and refuses with [require], so a frame
     * that stops short is caught by the stage that wanted the bytes and says
     * which one that was -- rather than by a length check far from the read.
     */
    fun decode(frame: ByteArray?): Marker? {
        if (frame == null || frame.size < HEADER_BYTES) return null
        return try {
            FrameReader(frame).read()
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Reads one frame in the order the format lays it out.
     *
     * Fixed header, then the uid, then the two strings, then whatever the flags
     * say follows. Each step consumes from the same buffer, which is why this
     * is a class: the position is the state.
     */
    private class FrameReader(frame: ByteArray) {
        private val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)

        private fun u8(): Int = buffer.get().toInt() and 0xFF

        private fun take(length: Int): ByteArray {
            require(buffer.remaining() >= length) { "frame stops short" }
            return ByteArray(length).also { buffer.get(it) }
        }

        fun read(): Marker {
            require(u8() == VERSION) { "not our version" }
            val flags = u8()
            val senderId = buffer.int
            val latE7 = buffer.int
            val lonE7 = buffer.int
            val stale = buffer.short.toInt() and 0xFFFF
            val typeLength = u8()
            val callsignLength = u8()
            val suffixLength = u8()
            require(typeLength in 1..MAX_TYPE) { "type length does not fit" }
            require(callsignLength <= MAX_CALLSIGN) { "callsign length does not fit" }

            val uuid: String?
            val suffixText: String?
            if (flags and FLAG_UUID != 0) {
                // A frame claiming both a UUID and a suffix is not one we produced.
                require(suffixLength == 0) { "a uuid frame carries no suffix" }
                require(buffer.remaining() >= 16) { "frame stops short" }
                uuid = UUID(buffer.long, buffer.long).toString()
                suffixText = null
            } else {
                require(suffixLength in 1..MAX_UID_SUFFIX) { "uid suffix length does not fit" }
                uuid = null
                suffixText = requireUtf8(take(suffixLength))
            }

            val type = requireUtf8(take(typeLength))
            val callsign = requireUtf8(take(callsignLength))
            val altitude = if (flags and FLAG_ALT != 0) shortValue() else null
            val argb = if (flags and FLAG_COLOR != 0) intValue() else null
            val remarks = if (flags and FLAG_REMARKS != 0) requireUtf8(take(u8())) else ""
            // Trailing bytes mean this is not the frame it claims to be.
            require(buffer.remaining() == 0) { "frame has trailing data" }
            return Marker(
                senderId = senderId,
                uid = uuid,
                uidSuffix = suffixText,
                type = type,
                callsign = callsign,
                latE7 = latE7,
                lonE7 = lonE7,
                staleSeconds =
                    if (flags and FLAG_STALE_MINUTES != 0) stale * SECONDS_PER_MINUTE else stale,
                altM = altitude,
                argb = argb,
                remarks = remarks,
            )
        }

        private fun shortValue(): Int {
            require(buffer.remaining() >= 2) { "frame stops short" }
            return buffer.short.toInt()
        }

        private fun intValue(): Int {
            require(buffer.remaining() >= 4) { "frame stops short" }
            return buffer.int
        }
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

    /**
     * Bytes as text, refusing anything that is not valid UTF-8.
     *
     * Kotlin substitutes U+FFFD for invalid input rather than throwing, so a
     * malformed frame would otherwise arrive as plausible-looking text and be
     * drawn on a map as somebody's callsign.
     */
    private fun requireUtf8(raw: ByteArray): String {
        val text = raw.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(raw)) { "not valid UTF-8" }
        return text
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

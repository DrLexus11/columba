package network.columba.app.service.tak

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Tier 2: a CoT event small enough to carry, without knowing what it means.
 *
 * The Kotlin half of `tools/cot_tier2.py`. See docs/TAKNative.md in the firmware
 * repository for why the tiering exists and how the compression was chosen.
 *
 * The dictionary here must stay byte-identical to the Python one or nothing
 * decompresses; `tests/.../tak_native_v1.json` carries its SHA-256 and a test
 * asserts it. The compressed *frames* deliberately are not compared: Java's
 * Deflater and zlib may emit different, equally valid deflate streams for the
 * same input, so requiring identical bytes would fail for no reason. What has
 * to hold is that each side decodes the other's frame.
 */
object CotTier2 {
    const val VERSION: Byte = 1

    /** Encoding doubles as the dictionary version, so a dictionary change can never be silent. */
    const val ENCODING_RAW: Byte = 0
    const val ENCODING_DEFLATE_DICT_V1: Byte = 1

    /**
     * A peer can send a small payload that expands enormously. CoT that large is
     * tier 3 traffic, fetched deliberately, so refusing it here is correct.
     */
    const val MAX_DECOMPRESSED = 64 * 1024

    /**
     * One Reticulum packet, which is the whole promise of tier 2.
     *
     * RNS's ENCRYPTED_MDU. Hard-coded on both sides and pinned to the real
     * value by a test on the Python side, where RNS is importable.
     */
    const val MAX_FRAME_BYTES = 383

    private const val ATTRIBUTES =
        "version uid type time start stale how access qos opex lat lon hae ce le " +
            "callsign endpoint device os platform battery course speed altsrc geopointsrc " +
            "name role abbr exrole argb iconsetpath parent_callsign production_time relation " +
            "readiness remarks archive precisionlocation usericon creator link status track " +
            "contact __group takv detail event point marti dest chat chatgrp senderCallsign"
    private const val HOW = "m-g m-g-n m-g-e h-g-i-g-o h-e h-t-l-f m-p"
    private const val ROLES = "Team Member Team Lead HQ Sniper Medic Forward Observer RTO K9"
    private const val TEAMS =
        "White Yellow Orange Magenta Red Maroon Purple Dark Blue Blue Cyan Teal " +
            "Green Dark Green Brown"
    private const val TYPES =
        "a-f-G-U-C a-f-G-U-C-I a-f-G-U-C-V a-f-G-E-V a-f-G a-f-A a-f-S a-h-G a-h-A a-h-S " +
            "a-n-G a-n-A a-u-G a-u-A b-m-p-s-p-i b-m-p-s-m b-m-p-c-cp b-m-p-w b-m-r b-t-f " +
            "b-t-f-r b-t-f-d b-a-o-tbl u-d-f u-d-f-m u-d-r u-d-c-c t-x-c-t t-x-takp-q"

    /** Deflate reads a dictionary as preceding bytes, so the likeliest matches go last. */
    val DICTIONARY: ByteArray = (
        listOf(ATTRIBUTES, HOW, ROLES, TEAMS, TYPES).joinToString(" ") +
            "COT_MAPPING_2525C/ 9999999.0 ATAK-CIV Team Member Undefined \"/><" +
            "<event version=\"2.0\" uid=\"\" type=\"\" time=\"\" start=\"\" stale=\"\" how=\"\"><point " +
            "lat=\"\" lon=\"\" hae=\"\" ce=\"9999999.0\" le=\"9999999.0\"/><detail>" +
            "<contact callsign=\"\"/><__group name=\"\" role=\"Team Member\"/>" +
            "<precisionlocation altsrc=\"GPS\" geopointsrc=\"GPS\"/><status battery=\"\"/>" +
            "<track course=\"\" speed=\"\"/><takv device=\"\" os=\"\" platform=\"ATAK-CIV\" version=\"\"/>" +
            "<uid Droid=\"\"/><remarks/><archive/><color argb=\"-1\"/>" +
            "<usericon iconsetpath=\"COT_MAPPING_2525C/\"/>" +
            "<link parent_callsign=\"\" production_time=\"\" relation=\"p-p\" type=\"\" uid=\"\"/>" +
            "<creator callsign=\"\" time=\"\" type=\"\" uid=\"\"/></detail></event>"
        ).toByteArray(Charsets.UTF_8)

    /**
     * Frame a CoT event, compressed only when that is actually smaller.
     *
     * A short event can deflate to more than it started as, and paying airtime
     * for compression that made the packet bigger is the kind of thing that
     * never shows up until somebody measures.
     */
    fun encode(cotXml: String): ByteArray {
        val raw = cotXml.toByteArray(Charsets.UTF_8)
        require(raw.isNotEmpty()) { "CoT must not be empty" }
        require(raw.size <= MAX_DECOMPRESSED) { "CoT is too large for tier 2; it belongs in tier 3" }
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        val deflated = try {
            deflater.setDictionary(DICTIONARY)
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream(raw.size)
            val buffer = ByteArray(4096)
            while (!deflater.finished()) {
                val written = deflater.deflate(buffer)
                if (written == 0) break
                out.write(buffer, 0, written)
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
        val frame = if (deflated.size < raw.size) {
            byteArrayOf(VERSION, ENCODING_DEFLATE_DICT_V1) + deflated
        } else {
            byteArrayOf(VERSION, ENCODING_RAW) + raw
        }
        // Tier 2 is one packet by definition, and this is the bound that makes
        // it one. MAX_DECOMPRESSED bounds the *input* at 64 KB, which a real
        // 5 KB ATAK drawing is comfortably inside -- it compresses to about
        // 700 bytes, nearly twice the MDU. Nothing caught that, so the frame
        // reached Reticulum, which refuses it at the packet layer.
        require(frame.size <= MAX_FRAME_BYTES) {
            "tier 2 frame is ${frame.size} bytes, over the $MAX_FRAME_BYTES-byte bound; " +
                "it belongs in tier 3"
        }
        return frame
    }

    /** Recover the CoT event, refusing anything that is not plainly ours. */
    fun decode(frame: ByteArray): String {
        require(frame.size >= 3) { "tier 2 frame is too short" }
        require(frame[0] == VERSION) { "unsupported tier 2 version ${frame[0]}" }
        val body = frame.copyOfRange(2, frame.size)
        val payload = when (frame[1]) {
            ENCODING_RAW -> body
            ENCODING_DEFLATE_DICT_V1 -> inflate(body)
            else -> throw IllegalArgumentException("unknown tier 2 encoding ${frame[1]}")
        }
        require(payload.isNotEmpty()) { "tier 2 payload is empty" }
        // After the switch, not inside inflate(). The bound was enforced only
        // on the deflate path, so a raw frame walked straight past it -- and a
        // raw frame is the easy one to forge, since it needs no compressor.
        // encode() refuses to *produce* one this large, which says nothing
        // about what a peer may send.
        require(payload.size <= MAX_DECOMPRESSED) {
            "tier 2 payload is ${payload.size} bytes, beyond the tier 2 bound of $MAX_DECOMPRESSED"
        }
        // Strictly, like the Python side. toString(UTF_8) substitutes U+FFFD
        // for malformed bytes, so a frame carrying invalid UTF-8 would arrive
        // on the map as text nobody sent rather than being refused.
        val text = payload.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(payload)) {
            "tier 2 payload is not valid UTF-8"
        }
        return text
    }

    private fun inflate(body: ByteArray): ByteArray {
        val inflater = Inflater(true)
        try {
            // Set before any input, and not in response to needsDictionary().
            // A raw deflate stream carries no zlib header, so the inflater has
            // no way to discover that a dictionary is required and never
            // reports needsDictionary() at all -- it simply fails with
            // DataFormatException on the first call. zlib's own API hides this
            // because Python's decompressobj takes zdict up front.
            inflater.setDictionary(DICTIONARY)
            inflater.setInput(body)
            val out = ByteArrayOutputStream(body.size * 3)
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val written = try {
                    inflater.inflate(buffer)
                } catch (error: DataFormatException) {
                    throw IllegalArgumentException("tier 2 payload did not decompress", error)
                }
                if (written == 0) {
                    if (inflater.needsInput()) {
                        throw IllegalArgumentException("tier 2 payload is truncated")
                    }
                    break
                }
                out.write(buffer, 0, written)
                if (out.size() > MAX_DECOMPRESSED) {
                    throw IllegalArgumentException("tier 2 payload expands beyond the tier 2 bound")
                }
            }
            // Trailing bytes after the deflate stream ends are not part of
            // this event, and a frame carrying them is not one we produced.
            require(inflater.remaining == 0) {
                "tier 2 frame has trailing data after the stream"
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}

package network.columba.app.service

import android.location.Location
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The compact position report, as the firmware expects to decode it.
 *
 * Fixed by `PositionReport.h` in the RAD firmware, with a Python twin in
 * `tools/position_codec.py` for the CoT gateway. Three implementations of one
 * format, so keep them in step: a disagreement about a single byte does not
 * fail loudly here, it puts a marker somewhere else on a map and lets somebody
 * act on it.
 *
 * Why so small. A TAK position report is normally CoT XML, roughly 700 bytes,
 * which is 538 ms on air at the mesh's working point and sixty-seven reports an
 * hour across the whole channel — one person reporting once a minute while
 * nobody else transmits anything at all. Packed like this it is twenty bytes at
 * full extent, and the gateway expands it back to XML on the far side where
 * bandwidth is free.
 *
 *     off  size  field
 *     0    1     version
 *     1    1     flags
 *     2    4     sender_id    uint32 BE  four bytes of the sender's identity hash
 *     6    4     lat_e7       int32 BE   degrees x 1e7, positive north
 *     10   4     lon_e7       int32 BE   degrees x 1e7, positive east
 *     14   4     fix_unix_s   uint32 BE  seconds; 0 when the source had no clock
 *     18   1     accuracy_m   uint8      0 unreported, 1..254 m, 255 = over
 *     -- then, in flag order, only what is present --
 *     +2         alt_m        int16 BE   metres HAE          FLAG_ALT
 *     +1         course       uint8      2-degree units      FLAG_COURSE
 *     +1         speed        uint8      half-metre/s units  FLAG_SPEED
 *     +1         sats         uint8                          FLAG_SATS
 *
 * Fixed width and no msgpack. Msgpack is the right answer for the time
 * assertion next door, where the payload is structured and a few key bytes cost
 * nothing; here a map of these fields runs to forty or fifty bytes against
 * eighteen, and that difference is the entire reason this feature can exist on
 * a LoRa mesh.
 */
object PositionCodec {
    const val WIRE_VERSION = 2
    const val WIRE_BASE_LEN = 19
    const val WIRE_MAX_LEN = 24

    const val FLAG_ALT = 0x01
    const val FLAG_COURSE = 0x02
    const val FLAG_SPEED = 0x04
    const val FLAG_SATS = 0x08

    /** Above this the receiver is told "worse than 254 m" rather than a wrapped value. */
    private const val ACCURACY_OVER = 255
    private const val ACCURACY_MAX_EXACT = 254

    /**
     * A fix, in the units that go on the wire.
     *
     * Scaled integers rather than floats, so the wire format has no endianness
     * or representation question to answer. Each optional field carries its own
     * "known" flag, because zero is a real value for every one of them: zero
     * metres is sea level, zero degrees is due north, and a receiver reporting
     * neither must not be read as reporting both.
     */
    data class Fix(
        val latE7: Int,
        val lonE7: Int,
        /**
         * Four bytes of this device's identity hash, so a receiver can tell one
         * reporter from another.
         *
         * It has to be in the payload because nothing else carries it: a
         * Reticulum packet to a SINGLE destination is anonymous by
         * construction. Without it every report is a new track and a map fills
         * with one person's ghosts, which is precisely what the first two live
         * reports did.
         *
         * An identifier, not an authentication. These packets are unsigned.
         */
        val senderId: Int = 0,
        val fixUnixSeconds: Long = 0,
        val accuracyM: Int = 0,
        val altKnown: Boolean = false,
        val altM: Int = 0,
        val courseKnown: Boolean = false,
        val courseDdeg: Int = 0,
        val speedCms: Int = 0,
        val sats: Int = 0,
    )

    /**
     * Convert an Android [Location].
     *
     * Android reports altitude as height above the WGS84 ellipsoid, which is
     * what the wire format wants, so it passes through unconverted. Satellite
     * count is deliberately left at zero: the only ways to get it are the
     * deprecated extras bundle or a GnssStatus callback this does not run, and
     * a fabricated count would be worse than an absent one.
     */
    fun fromLocation(
        location: Location,
        senderId: Int = 0,
    ): Fix =
        Fix(
            latE7 = Math.round(location.latitude * 1e7).toInt(),
            lonE7 = Math.round(location.longitude * 1e7).toInt(),
            senderId = senderId,
            // Location.time is UTC epoch milliseconds from the provider, not
            // from this phone's clock reading of when the callback arrived.
            // Those are different measurements and the firmware treats them
            // differently, so pass the one that was asked for.
            fixUnixSeconds = location.time / 1000L,
            accuracyM = if (location.hasAccuracy()) Math.round(location.accuracy) else 0,
            altKnown = location.hasAltitude(),
            altM = if (location.hasAltitude()) Math.round(location.altitude).toInt() else 0,
            courseKnown = location.hasBearing(),
            courseDdeg = if (location.hasBearing()) Math.round(location.bearing * 10f) else 0,
            speedCms = if (location.hasSpeed()) Math.round(location.speed * 100f) else 0,
        )

    /** Pack a fix into the wire format. */
    fun encode(fix: Fix): ByteArray {
        var flags = 0
        if (fix.altKnown) flags = flags or FLAG_ALT
        if (fix.courseKnown) flags = flags or FLAG_COURSE
        if (fix.speedCms > 0) flags = flags or FLAG_SPEED
        if (fix.sats > 0) flags = flags or FLAG_SATS

        val optionalBytes =
            (if (flags and FLAG_ALT != 0) 2 else 0) +
                (if (flags and FLAG_COURSE != 0) 1 else 0) +
                (if (flags and FLAG_SPEED != 0) 1 else 0) +
                (if (flags and FLAG_SATS != 0) 1 else 0)

        val buffer =
            ByteBuffer
                .allocate(WIRE_BASE_LEN + optionalBytes)
                .order(ByteOrder.BIG_ENDIAN)
                .put(WIRE_VERSION.toByte())
                .put(flags.toByte())
                .putInt(fix.senderId)
                .putInt(fix.latE7)
                .putInt(fix.lonE7)
                .putInt(fix.fixUnixSeconds.toInt())
                .put(encodeAccuracy(fix.accuracyM))

        if (flags and FLAG_ALT != 0) buffer.putShort(fix.altM.toShort())
        // 0..179. Bearing arrives 0..359.9 and 360 would wrap the byte to a
        // course of due north, which is a different direction from due north
        // only if you are reading it, so normalise before scaling.
        if (flags and FLAG_COURSE != 0) {
            buffer.put(((fix.courseDdeg % 3600) / 20).toByte())
        }
        if (flags and FLAG_SPEED != 0) {
            buffer.put(minOf(fix.speedCms / 50, 255).toByte())
        }
        if (flags and FLAG_SATS != 0) buffer.put(fix.sats.toByte())
        return buffer.array()
    }

    /**
     * Saturate rather than wrap. Four hundred metres of error arriving as 144
     * is a marker an operator trusts far more than it deserves, and a position
     * believed more than it should be is the specific harm this whole feature
     * has to avoid.
     */
    private fun encodeAccuracy(metres: Int): Byte =
        when {
            metres <= 0 -> 0
            metres > ACCURACY_MAX_EXACT -> ACCURACY_OVER.toByte()
            else -> metres.toByte()
        }

    /**
     * The length a report with these flags must be, so the size check happens
     * once up front rather than before each optional field. Cheaper to read,
     * and it makes a truncated frame a single decision instead of four.
     */
    private fun expectedLength(flags: Int): Int =
        WIRE_BASE_LEN +
            (if (flags and FLAG_ALT != 0) 2 else 0) +
            (if (flags and FLAG_COURSE != 0) 1 else 0) +
            (if (flags and FLAG_SPEED != 0) 1 else 0) +
            (if (flags and FLAG_SATS != 0) 1 else 0)

    /**
     * Unpack a report. Returns null for anything unrecognised rather than
     * throwing: these bytes come off a radio, and a short or malformed frame is
     * an ordinary event rather than an exceptional one.
     */
    fun decode(data: ByteArray?): Fix? {
        if (data == null || data.size < WIRE_BASE_LEN) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get().toInt() and 0xFF
        val flags = buffer.get().toInt() and 0xFF
        if (version != WIRE_VERSION || data.size < expectedLength(flags)) return null

        val senderId = buffer.int
        val latE7 = buffer.int
        val lonE7 = buffer.int
        // Unsigned on the wire. Read as a signed Int this goes negative in
        // 2038, and a marker stamped in 1901 is stale for ever.
        val seconds = buffer.int.toLong() and 0xFFFFFFFFL
        val accuracy = buffer.get().toInt() and 0xFF

        val altKnown = flags and FLAG_ALT != 0
        val altM = if (altKnown) buffer.short.toInt() else 0
        val courseKnown = flags and FLAG_COURSE != 0
        val courseDdeg = if (courseKnown) (buffer.get().toInt() and 0xFF) * 20 else 0
        val speedCms =
            if (flags and FLAG_SPEED != 0) (buffer.get().toInt() and 0xFF) * 50 else 0
        val sats = if (flags and FLAG_SATS != 0) buffer.get().toInt() and 0xFF else 0

        return Fix(
            latE7 = latE7,
            lonE7 = lonE7,
            senderId = senderId,
            fixUnixSeconds = seconds,
            accuracyM = accuracy,
            altKnown = altKnown,
            altM = altM,
            courseKnown = courseKnown,
            courseDdeg = courseDdeg,
            speedCms = speedCms,
            sats = sats,
        )
    }
}

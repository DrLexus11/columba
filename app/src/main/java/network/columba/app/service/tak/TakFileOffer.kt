package network.columba.app.service.tak

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * What crosses the mesh in place of ATAK's file notice.
 *
 * The Kotlin half of the offer in `tools/tak_files.py`, byte-identical and
 * asserted against `tak_native_v1.json`. ATAK's `b-f-t-r` is about 390 bytes
 * compressed -- two fragments before a thumbnail. The offer carries what a
 * receiver needs to rebuild it, plus for a QuickPic where the picture is and a
 * thumbnail, in at most three fragments: the cost of a drawing, proven on the
 * radios.
 *
 *     FILE_OFFER_V1   kind(1) sender_id(4) sha256(32) size(4) flags(1)
 *                     name_len(1) filename(utf-8)
 *       FLAG_POINT    lat_e7(4) lon_e7(4) marker_uuid(16)
 *       FLAG_THUMB    thumb_len(2) thumbnail(webp)
 */
object TakFileOffer {
    const val FLAG_POINT = 0x01
    const val FLAG_THUMB = 0x02
    private const val MAX_NAME_BYTES = 60
    private const val HEAD_BYTES = 1 + 4 + TakFiles.HASH_BYTES + 4 + 1 + 1
    private const val POINT_BYTES = 4 + 4 + 16

    /** Three fragments of [CotFragment.MAX_FRAGMENT_BYTES]. */
    const val MAX_OFFER_BYTES = 3 * CotFragment.MAX_FRAGMENT_BYTES

    /** Where a QuickPic is: its marker's position and uid. */
    data class Point(val latE7: Int, val lonE7: Int, val markerUid: UUID)

    class Offer(
        val senderId: Int,
        val hash: String,
        val size: Long,
        val filename: String,
        val point: Point? = null,
        val thumbnail: ByteArray? = null,
    )

    fun encode(offer: Offer): ByteArray {
        val name = offer.filename.toByteArray(Charsets.UTF_8).let { it.copyOf(minOf(it.size, MAX_NAME_BYTES)) }
        val thumb = offer.thumbnail
        val flags = (if (offer.point != null) FLAG_POINT else 0) or (if (thumb != null) FLAG_THUMB else 0)
        val size = HEAD_BYTES + name.size + (if (offer.point != null) POINT_BYTES else 0) + (thumb?.let { 2 + it.size } ?: 0)
        val out =
            ByteBuffer.allocate(size)
                .put(TakPayload.FILE_OFFER_V1.toByte())
                .putInt(offer.senderId)
                .put(hexToBytes(offer.hash))
                .putInt(offer.size.toInt())
                .put(flags.toByte())
                .put(name.size.toByte())
                .put(name)
        offer.point?.let {
            out.putInt(it.latE7).putInt(it.lonE7)
                .putLong(it.markerUid.mostSignificantBits).putLong(it.markerUid.leastSignificantBits)
        }
        thumb?.let { out.putShort(it.size.toShort()).put(it) }
        return out.array()
    }

    /** An offer, or null for anything that is not a well-formed one. */
    fun decode(frame: ByteArray?): Offer? {
        if (frame == null || frame.size !in HEAD_BYTES..MAX_OFFER_BYTES || frame[0].toInt() != TakPayload.FILE_OFFER_V1) return null
        val buffer = ByteBuffer.wrap(frame)
        buffer.get()
        val senderId = buffer.int
        val hash = ByteArray(TakFiles.HASH_BYTES).also { buffer.get(it) }.joinToString("") { "%02x".format(it) }
        val size = buffer.int.toLong() and 0xFFFFFFFFL
        val flags = buffer.get().toInt() and 0xFF
        val nameLength = buffer.get().toInt() and 0xFF
        // A layout this decoder does not know is refused, not guessed at; so
        // is a size nothing here would store.
        if (flags and (FLAG_POINT or FLAG_THUMB).inv() != 0 || size !in 1..TakFiles.MAX_FILE_BYTES.toLong()) return null
        return runCatching {
            val name = String(ByteArray(nameLength).also { buffer.get(it) }, Charsets.UTF_8)
            val point =
                if (flags and FLAG_POINT != 0) Point(buffer.int, buffer.int, UUID(buffer.long, buffer.long)) else null
            val thumb =
                if (flags and FLAG_THUMB != 0) {
                    ByteArray(buffer.short.toInt() and 0xFFFF).also { buffer.get(it) }
                } else {
                    null
                }
            Offer(senderId, hash, size, name, point, thumb).takeIf { !buffer.hasRemaining() }
        }.getOrNull()
    }

    /**
     * The offer for a stored file: with its point and a thumbnail if it is a
     * QuickPic, and never over [MAX_OFFER_BYTES].
     */
    fun offerFor(
        senderId: Int,
        hash: String,
        filename: String,
        data: ByteArray,
        thumbnailer: (ByteArray, Int) -> ByteArray? = ::thumbnail,
    ): Offer {
        val (image, marker) = packageParts(data)
        val point = marker?.let(::quickpicPoint)
        val bare = Offer(senderId, hash, data.size.toLong(), filename, point)
        val thumb = image?.let { thumbnailer(it, MAX_OFFER_BYTES - encode(bare).size - 2) }
        return Offer(senderId, hash, data.size.toLong(), filename, point, thumb)
    }

    /** The image and the QuickPic marker in a data package, where present. */
    private fun packageParts(data: ByteArray): Pair<ByteArray?, String?> {
        var image: ByteArray? = null
        var marker: String? = null
        runCatching {
            ZipInputStream(ByteArrayInputStream(data)).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    val lower = entry.name.lowercase()
                    when {
                        image == null && IMAGE_SUFFIXES.any { lower.endsWith(it) } -> image = zip.readBytes()
                        marker == null && lower.endsWith(".cot") ->
                            zip.readBytes().toString(Charsets.UTF_8).takeIf { "b-i-x-i" in it }?.let { marker = it }
                    }
                }
            }
        }
        return image to marker
    }

    private val IMAGE_SUFFIXES = listOf(".jpg", ".jpeg", ".png", ".webp")

    /** Where a QuickPic marker is, or null. */
    fun quickpicPoint(markerXml: String): Point? {
        val uid = Regex("""\buid=['"]([0-9a-fA-F-]{36})['"]""").find(markerXml)?.groupValues?.get(1)
        val point = Regex("""<point[^>]*\blat=['"]([-0-9.]+)['"][^>]*\blon=['"]([-0-9.]+)['"]""").find(markerXml)
        return runCatching {
            Point(
                Math.round(point!!.groupValues[1].toDouble() * 1e7).toInt(),
                Math.round(point.groupValues[2].toDouble() * 1e7).toInt(),
                UUID.fromString(uid!!),
            )
        }.getOrNull()
    }

    /** Tried best first; the first that fits wins. The same steps as the deck. */
    private val THUMB_STEPS = listOf(112 to 20, 96 to 20, 96 to 12, 80 to 20, 64 to 20, 64 to 10, 48 to 10)

    /** A WebP thumbnail of at most [budget] bytes, or null if none fits. */
    fun thumbnail(image: ByteArray, budget: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 256) sample *= 2
        val source =
            BitmapFactory.decodeByteArray(image, 0, image.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        return THUMB_STEPS.firstNotNullOfOrNull { (side, quality) ->
            val scale = side.toFloat() / maxOf(source.width, source.height)
            val small = Bitmap.createScaledBitmap(source, maxOf(1, (source.width * scale).toInt()), maxOf(1, (source.height * scale).toInt()), true)
            ByteArrayOutputStream().also { small.compress(format, quality, it) }.toByteArray().takeIf { it.size <= budget }
        }
    }

    /** ATAK's `b-f-t-r`, rebuilt here. The URL is filled in when the file is. */
    fun notice(offer: Offer, senderUid: String, senderCallsign: String, nowMs: Long): String {
        val stamp = stamp(nowMs)
        val lat = offer.point?.let { it.latE7 / 1e7 } ?: 0.0
        val lon = offer.point?.let { it.lonE7 / 1e7 } ?: 0.0
        val name = offer.filename.removeSuffix(".zip")
        return "<event version=\"2.0\" uid=\"${UUID.nameUUIDFromBytes(hexToBytes(offer.hash))}\" type=\"${TakFiles.FILESHARE_TYPE}\" " +
            "how=\"h-e\" time=\"$stamp\" start=\"$stamp\" stale=\"$stamp\">" +
            "<point lat=\"${"%.7f".format(Locale.US, lat)}\" lon=\"${"%.7f".format(Locale.US, lon)}\" hae=\"9999999.0\" " +
            "ce=\"9999999.0\" le=\"9999999.0\"/><detail><fileshare filename=\"${esc(offer.filename)}\" senderUrl=\"\" " +
            "sizeInBytes=\"${offer.size}\" sha256=\"${offer.hash}\" senderUid=\"${esc(senderUid)}\" " +
            "senderCallsign=\"${esc(senderCallsign)}\" name=\"${esc(name)}\"/></detail></event>"
    }

    /**
     * A data package ATAK imports as the QuickPic's marker with the thumbnail
     * attached, under the marker's own uid so the full package replaces it.
     * Returns the package and its filename.
     */
    fun preview(offer: Offer, senderCallsign: String, nowMs: Long): Pair<ByteArray, String> {
        val point = requireNotNull(offer.point)
        val uid = point.markerUid.toString()
        val name = offer.filename.removeSuffix(".zip")
        val base = name.substringBeforeLast('.')
        val imageEntry = "${offer.hash.take(32)}/${base}_preview.webp"
        val stamp = stamp(nowMs)
        val marker =
            "<?xml version='1.0' encoding='UTF-8' standalone='yes'?><event version='2.0' uid='$uid' type='b-i-x-i' " +
                "time='$stamp' start='$stamp' stale='2099-01-01T00:00:00.000Z' how='h-g-i-g-o'>" +
                "<point lat='${"%.7f".format(Locale.US, point.latE7 / 1e7)}' lon='${"%.7f".format(Locale.US, point.lonE7 / 1e7)}' " +
                "hae='9999999.0' ce='9999999.0' le='9999999.0'/><detail><contact callsign='${esc(senderCallsign)} preview'/>" +
                "<remarks>Preview over LoRa; the full picture follows on a fast path.</remarks></detail></event>"
        val manifest =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><MissionPackageManifest version=\"2\"><Configuration>" +
                "<Parameter name=\"uid\" value=\"$uid\"/><Parameter name=\"name\" value=\"${esc(name)} (preview)\"/>" +
                "<Parameter name=\"onReceiveImport\" value=\"true\"/><Parameter name=\"onReceiveDelete\" value=\"true\"/>" +
                "</Configuration><Contents><Content ignore=\"false\" zipEntry=\"$uid/$uid.cot\"><Parameter name=\"uid\" value=\"$uid\"/>" +
                "</Content><Content ignore=\"false\" zipEntry=\"${esc(imageEntry)}\"><Parameter name=\"uid\" value=\"$uid\"/></Content>" +
                "</Contents></MissionPackageManifest>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("MANIFEST/manifest.xml"))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.putNextEntry(ZipEntry("$uid/$uid.cot"))
            zip.write(marker.toByteArray(Charsets.UTF_8))
            zip.putNextEntry(ZipEntry(imageEntry))
            zip.write(requireNotNull(offer.thumbnail))
        }
        return out.toByteArray() to "${base}_preview.zip"
    }

    private fun stamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun esc(text: String) = CotAttributes.escape(text)

    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

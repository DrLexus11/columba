package network.columba.app.service.tak

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cutting an oversized event into packets that each stand on their own.
 *
 * Tier 2 refuses above 383 B, which is why a drawing or a nine-line MEDEVAC
 * does not cross. The vectors come from `tak_native_v1.json`, copied between
 * the repositories, so the two halves cannot drift: a fragment written here
 * and read there has to be the same bytes, or a drawing arrives as pieces
 * nobody can rejoin.
 */
class CotFragmentTest {
    private val fixture: JSONObject =
        JSONObject(
            checkNotNull(javaClass.classLoader.getResourceAsStream("tak_native_v1.json"))
                .bufferedReader().readText(),
        ).getJSONObject("fragment")

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun hexOf(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private val sender = ByteArray(16) { 0x11 }

    @Test
    fun `the constants match the other half`() {
        assertEquals(fixture.getInt("kind"), TakPayload.FRAGMENT_V1)
        assertEquals(fixture.getInt("header_bytes"), CotFragment.HEADER_BYTES)
        assertEquals(fixture.getInt("max_fragment_bytes"), CotFragment.MAX_FRAGMENT_BYTES)
        assertEquals(
            fixture.getInt("max_fragment_frame_bytes"),
            CotFragment.MAX_FRAGMENT_FRAME_BYTES,
        )
        // The bound that matters: a fragment travels as an LXMF message, and
        // the envelope costs a measured 107 bytes on the air. Sized against
        // the 383 B bare-packet MDU instead, every fragment became a Resource
        // over its own Link.
        assertTrue(
            CotFragment.MAX_FRAGMENT_FRAME_BYTES + CotFragment.LXMF_ENVELOPE_BYTES <=
                CotTier2.MAX_FRAME_BYTES,
        )
        assertEquals(fixture.getInt("max_fragments"), CotFragment.MAX_FRAGMENTS)
        assertEquals(
            fixture.getLong("reassembly_timeout_seconds") * 1000,
            CotFragment.REASSEMBLY_TIMEOUT_MS,
        )
    }

    @Test
    fun `the same payload produces the same bytes on both sides`() {
        val payload = hex(fixture.getString("payload"))
        val expected = fixture.getJSONArray("frames")
        val frames = CotFragment.fragments(payload, fixture.getLong("transfer_id"))
        assertEquals(expected.length(), frames.size)
        for (index in frames.indices) {
            assertEquals(expected.getString(index), hexOf(frames[index]))
        }
    }

    @Test
    fun `the other half's frames reassemble here`() {
        // The direction that matters in the field: a drawing sent from the
        // bridge and put back together on the handset.
        val reassembler = CotReassembler()
        val frames = fixture.getJSONArray("frames")
        var whole: ByteArray? = null
        for (index in 0 until frames.length()) {
            whole = reassembler.feed(sender, hex(frames.getString(index)), now = 1_000)
        }
        assertEquals(fixture.getString("payload"), hexOf(checkNotNull(whole)))
    }

    @Test
    fun `every fragment fits one packet`() {
        CotFragment.fragments(ByteArray(2000) { it.toByte() }).forEach {
            assertTrue(it.size <= CotTier2.MAX_FRAME_BYTES)
        }
    }

    @Test
    fun `fragments may arrive in any order`() {
        // Separate LXMF messages with separate retries, so order is not
        // something this can assume.
        val payload = ByteArray(900) { (it * 7).toByte() }
        val frames = CotFragment.fragments(payload).reversed()
        val reassembler = CotReassembler()
        var whole: ByteArray? = null
        frames.forEach { whole = reassembler.feed(sender, it, now = 1_000) }
        assertTrue(payload.contentEquals(checkNotNull(whole)))
    }

    @Test
    fun `two peers may pick the same transfer id`() {
        // Merging them would produce a frame that decodes to something neither
        // of them sent, which is worse than dropping both.
        val other = ByteArray(16) { 0x22 }
        val mine = CotFragment.fragments(ByteArray(700) { 0x41 }, transferId = 7)
        val theirs = CotFragment.fragments(ByteArray(700) { 0x42 }, transferId = 7)
        val reassembler = CotReassembler()
        // Interleaved, every fragment but the last. How many that is depends
        // on the fragment size, which is set by the LXMF envelope -- so the
        // test does not hard-code it.
        for (index in 0 until mine.size - 1) {
            assertNull(reassembler.feed(sender, mine[index], now = 1_000))
            assertNull(reassembler.feed(other, theirs[index], now = 1_000))
        }
        assertTrue(
            ByteArray(700) { 0x41 }
                .contentEquals(reassembler.feed(sender, mine.last(), now = 1_000)),
        )
        assertTrue(
            ByteArray(700) { 0x42 }
                .contentEquals(reassembler.feed(other, theirs.last(), now = 1_000)),
        )
    }

    @Test
    fun `a stale transfer is dropped`() {
        val frames = CotFragment.fragments(ByteArray(900) { 1 })
        val reassembler = CotReassembler()
        reassembler.feed(sender, frames[0], now = 1_000)
        assertNull(
            reassembler.feed(sender, frames[1],
                now = 1_000 + CotFragment.REASSEMBLY_TIMEOUT_MS + 1),
        )
    }

    @Test
    fun `a peer that never finishes cannot grow the buffer`() {
        val reassembler = CotReassembler(maxTransfers = 4)
        for (transfer in 0 until 20L) {
            reassembler.feed(sender, CotFragment.fragments(ByteArray(900) { 1 }, transfer)[0],
                now = 1_000)
        }
        assertTrue(reassembler.pending() <= 4)
    }

    @Test
    fun `another codec is not ours`() {
        assertNull(CotFragment.decode(ByteArray(40).also { it[0] = TakPayload.CHAT_V1.toByte() }))
    }

    @Test
    fun `a count of zero can never be satisfied`() {
        val frame = ByteArray(20)
        frame[0] = TakPayload.FRAGMENT_V1.toByte()
        assertNull(CotFragment.decode(frame))
    }
    @Test
    fun `the observer reports every fragment, measured from the first`() {
        // The seam that lets a slow transfer be watched while it is still
        // slow. Elapsed is from the first fragment, not the previous one:
        // the timeout is measured against the first, so reporting gaps would
        // answer a question nobody asked.
        val seen = mutableListOf<List<Long>>()
        val reassembler =
            CotReassembler(
                observer = { index, count, held, elapsedMs ->
                    seen += listOf(index.toLong(), count.toLong(), held.toLong(), elapsedMs)
                },
            )
        val frames = CotFragment.fragments(ByteArray(900) { 7 }, transferId = 11)
        frames.forEachIndexed { index, frame ->
            reassembler.feed(sender, frame, 1_000L + index * 10_000L)
        }

        // Every fragment reported, the last one included -- it closes the
        // transfer and carries the elapsed time worth reading.
        assertEquals(frames.size, seen.size)
        assertEquals(0L, seen[0][3])
        assertEquals(10_000L, seen[1][3])
        assertEquals(frames.size.toLong(), seen.last()[2])
    }

    @Test
    fun `a reassembler without an observer still works`() {
        // The default path is the one that runs in the field.
        val frames = CotFragment.fragments(ByteArray(900) { 7 }, transferId = 12)
        val plain = CotReassembler()
        var whole: ByteArray? = null
        frames.forEach { whole = plain.feed(sender, it, 0L) ?: whole }

        assertEquals(900, whole?.size)
    }

    /**
     * A fragment larger than the wire format allows is refused before anything
     * is copied. A header that decodes perfectly with a slice of any size was
     * otherwise copied and held for up to sixteen transfers at once.
     */
    @Test
    fun `a fragment above the wire maximum is refused`() {
        val oversized =
            ByteArray(CotFragment.MAX_FRAGMENT_FRAME_BYTES + 1).also {
                it[0] = TakPayload.FRAGMENT_V1.toByte()
                it[6] = 1 // count 1, index 0: a header that is otherwise valid
            }

        assertNull(CotFragment.decode(oversized))
    }

    @Test
    fun `a fragment at the wire maximum is accepted`() {
        val atBound =
            ByteArray(CotFragment.MAX_FRAGMENT_FRAME_BYTES).also {
                it[0] = TakPayload.FRAGMENT_V1.toByte()
                it[6] = 1
            }

        assertTrue(CotFragment.decode(atBound) != null)
    }

    /**
     * Why the sender has to be the real one.
     *
     * The mesh path passed this node's own destination hash as "the sender",
     * which is the same value for every peer -- so two peers whose random
     * transfer ids met were merged into an event neither of them sent. This
     * pins that shared key does exactly that; the fix is to never give the
     * reassembler one.
     */
    @Test
    fun `one shared key merges two peers into something neither sent`() {
        val sharedKey = ByteArray(16) { 0x44 }
        val mine = CotFragment.fragments(ByteArray(700) { 0x41 }, transferId = 7)
        val theirs = CotFragment.fragments(ByteArray(700) { 0x42 }, transferId = 7)
        val reassembler = CotReassembler()

        for (index in 0 until mine.size - 1) {
            reassembler.feed(sharedKey, mine[index], now = 1_000)
            reassembler.feed(sharedKey, theirs[index], now = 1_000)
        }
        val merged = reassembler.feed(sharedKey, mine.last(), now = 1_000)

        assertTrue(
            "a shared key produced one peer's payload intact -- it should corrupt",
            merged == null || !merged.contentEquals(ByteArray(700) { 0x41 }),
        )
    }

    /** No proved sender, no reassembly: an empty key is that shared key. */
    @Test
    fun `a fragment with no sender is refused`() {
        val reassembler = CotReassembler()
        val only = CotFragment.fragments(ByteArray(10) { 0x41 }, transferId = 9)

        assertNull(reassembler.feed(null, only.single(), now = 1_000))
        assertNull(reassembler.feed(ByteArray(0), only.single(), now = 1_000))
        assertEquals(0, reassembler.pending())
    }
}

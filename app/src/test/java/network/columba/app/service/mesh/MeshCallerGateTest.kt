package network.columba.app.service.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshCallerGateTest {
    private val gate = MeshCallerGate()
    private val atak = MeshCallerGate.ATAK_CIV

    @Test
    fun `the store ATAK and the SDK's developer ATAK are allowed`() {
        assertTrue(gate.allows(listOf(atak)) { listOf(MeshCallerGate.ATAK_STORE_CERT) })
        assertTrue(gate.allows(listOf(atak)) { listOf(MeshCallerGate.ATAK_SDK_CERT) })
    }

    @Test
    fun `a digest in capitals is the same digest`() {
        assertTrue(gate.allows(listOf(atak)) { listOf(MeshCallerGate.ATAK_STORE_CERT.uppercase()) })
    }

    @Test
    fun `ATAK's package name signed by anyone else is refused`() {
        // The package name alone proves nothing: anyone can build an APK called this.
        assertFalse(gate.allows(listOf(atak)) { listOf("00".repeat(32)) })
    }

    @Test
    fun `another app signed with an allowed key is refused`() {
        assertFalse(gate.allows(listOf("com.example.other")) { listOf(MeshCallerGate.ATAK_STORE_CERT) })
    }

    @Test
    fun `nothing known about the caller is refused`() {
        assertFalse(gate.allows(emptyList()) { listOf(MeshCallerGate.ATAK_STORE_CERT) })
        assertFalse(gate.allows(listOf(atak)) { emptyList() })
    }

    @Test
    fun `a shared uid must be allowed in full`() {
        val digests = mapOf(atak to listOf(MeshCallerGate.ATAK_STORE_CERT), "com.example.other" to listOf("ab".repeat(32)))
        assertFalse(gate.allows(listOf(atak, "com.example.other")) { digests.getValue(it) })
    }

    @Test
    fun `an extra signer that is not allowed refuses the package`() {
        assertFalse(gate.allows(listOf(atak)) { listOf(MeshCallerGate.ATAK_STORE_CERT, "cd".repeat(32)) })
    }

    @Test
    fun `the announce floor holds for a minute after an announce`() {
        val floor = MeshAnnounceFloor()
        assertEquals(0L, floor.remaining(1_000L))
        floor.sent(1_000L)
        assertEquals(60_000L, floor.remaining(1_000L))
        assertEquals(1_000L, floor.remaining(60_000L))
        assertEquals(0L, floor.remaining(61_000L))
    }
}

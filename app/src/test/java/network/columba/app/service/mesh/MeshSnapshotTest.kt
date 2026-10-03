package network.columba.app.service.mesh

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Agreement with the plugin, against the shared fixture columba_mesh_v1.json. */
class MeshSnapshotTest {
    private val fixture: JSONObject =
        JSONObject(
            checkNotNull(javaClass.classLoader.getResourceAsStream("columba_mesh_v1.json"))
                .bufferedReader().use { it.readText() },
        )
    private val cases = fixture.getJSONArray("snapshots")

    private fun fixture(name: String): JSONArray =
        JSONObject(
            checkNotNull(javaClass.classLoader.getResourceAsStream(name)).bufferedReader().use { it.readText() },
        ).getJSONArray("snapshots")

    @Test
    fun `every interfaces fixture snapshot survives a round trip unchanged`() {
        val v2 = fixture("columba_mesh_interfaces.json")
        for (i in 0 until v2.length()) {
            val case = v2.getJSONObject(i)
            val expected = case.getJSONObject("snapshot")
            val parsed = MeshSnapshot.fromJson(expected)
            assertNotNull(case.getString("name"), parsed)
            assertTrue(
                "${case.getString("name")}: ${parsed!!.toJson()} != $expected",
                sameJson(parsed.toJson(), expected),
            )
            assertEquals(case.getJSONObject("expect").getInt("interfaces"), parsed.interfaces.size)
        }
    }

    @Test
    fun `a snapshot without interface fields is read, and loses nothing written back`() {
        for (i in 0 until cases.length()) {
            val parsed = MeshSnapshot.fromJson(cases.getJSONObject(i).getJSONObject("snapshot"))
            assertNotNull(parsed)
            assertTrue(parsed!!.interfaces.isEmpty())
            assertEquals(parsed, MeshSnapshot.fromJson(parsed.toJson()))
        }
    }

    @Test
    fun `configured interface types map to carriers`() {
        assertEquals(MeshCarrier.TCP, MeshCarrier.ofConfigType("TCPClient"))
        assertEquals(MeshCarrier.BLE, MeshCarrier.ofConfigType("AndroidBLE"))
        assertEquals(MeshCarrier.LORA, MeshCarrier.ofConfigType("RNode"))
        assertEquals(MeshCarrier.AUTO, MeshCarrier.ofConfigType("AutoInterface"))
        assertEquals(MeshCarrier.UDP, MeshCarrier.ofConfigType("UDP"))
        assertEquals(MeshCarrier.UNKNOWN, MeshCarrier.ofConfigType("I2P"))
    }

    /**
     * Structural equality, null included. Android's org.json, which the test
     * compiles against, has no similar(); numbers compare by value, so 2 and
     * 2L are the same field.
     */
    private fun sameJson(a: Any?, b: Any?): Boolean =
        when {
            a is JSONObject && b is JSONObject ->
                a.keys().asSequence().toSet() == b.keys().asSequence().toSet() &&
                    a.keys().asSequence().all { sameJson(a.get(it), b.get(it)) }
            a is JSONArray && b is JSONArray ->
                a.length() == b.length() && (0 until a.length()).all { sameJson(a.get(it), b.get(it)) }
            a is Number && b is Number -> a.toLong() == b.toLong() && a.toDouble() == b.toDouble()
            else -> a == b
        }

    @Test
    fun `unknown fields are written as null, not left out`() {
        val snapshot =
            MeshSnapshot(
                at = 1L,
                node = MeshSnapshot.Node(uid = null, callsign = null, control = false, running = false),
                propagation = null,
                peers =
                    listOf(
                        MeshSnapshot.Peer("urtn-00", null, null, 2L, false, null, null, null),
                    ),
            ).toJson()
        assertTrue(snapshot.has("propagation") && snapshot.isNull("propagation"))
        assertTrue(snapshot.getJSONObject("node").isNull("uid"))
        val peer = snapshot.getJSONArray("peers").getJSONObject(0)
        for (key in listOf("callsign", "role", "hops", "carrier", "interface")) {
            assertTrue(key, peer.has(key) && peer.isNull(key))
        }
    }

    @Test
    fun `the fixture's expectations match what it holds`() {
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val snapshot = MeshSnapshot.fromJson(case.getJSONObject("snapshot"))!!
            val expect = case.getJSONObject("expect")
            assertEquals(expect.getInt("peers"), snapshot.peers.size)
            val commandPost = snapshot.peers.filter { it.role == MeshSnapshot.COMMAND_POST_ROLE && it.path }
            assertEquals(expect.getBoolean("command_post_reachable"), commandPost.isNotEmpty())
        }
    }

    @Test
    fun `another version is refused`() {
        val other = JSONObject(cases.getJSONObject(0).getJSONObject("snapshot").toString()).put("v", 2)
        assertNull(MeshSnapshot.fromJson(other))
    }

    @Test
    fun `carriers come from the interface type`() {
        assertEquals(MeshCarrier.LORA, MeshCarrier.of("RNodeInterface[RNode LoRa]"))
        assertEquals(MeshCarrier.LORA, MeshCarrier.of("ColumbaRNodeInterface[RNode 1A2B]"))
        assertEquals(MeshCarrier.LORA, MeshCarrier.of("KISSInterface[TNC]"))
        assertEquals(MeshCarrier.BLE, MeshCarrier.of("AndroidBLEInterface[BLE]"))
        assertEquals(MeshCarrier.BLE, MeshCarrier.of("BLEPeerInterface[peer]"))
        assertEquals(MeshCarrier.TCP, MeshCarrier.of("TCPInterface[Mesh/192.168.1.37:4242]"))
        assertEquals(MeshCarrier.TCP, MeshCarrier.of("TCPClientInterface[x]"))
        assertEquals(MeshCarrier.TCP, MeshCarrier.of("BackboneClientInterface[x]"))
        assertEquals(MeshCarrier.UDP, MeshCarrier.of("UDPInterface[RAD-01/0.0.0.0:4244]"))
        assertEquals(MeshCarrier.AUTO, MeshCarrier.of("AutoInterface[Default Interface]"))
        assertEquals(MeshCarrier.LOCAL, MeshCarrier.of("LocalClientInterface[shared]"))
        assertEquals(MeshCarrier.UNKNOWN, MeshCarrier.of("WeaveInterface[x]"))
        assertNull(MeshCarrier.of(null))
        assertNull(MeshCarrier.of(""))
    }

    @Test
    fun `a peer whose name merely mentions BLE is not called BLE`() {
        // The type is what counts: a TCP interface somebody named "BLE bridge".
        assertEquals(MeshCarrier.TCP, MeshCarrier.of("TCPInterface[BLE bridge]"))
        assertFalse(MeshCarrier.of("TCPInterface[RNode via TCP]") == MeshCarrier.LORA)
    }
}

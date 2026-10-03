package network.columba.app.service.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextHopTest {
    private val configured =
        listOf(
            NextHop.Configured("Auto Discovery", "AutoInterface"),
            NextHop.Configured("Board TCP", "TCPClient"),
            NextHop.Configured("Bluetooth LE", "AndroidBLE"),
        )

    @Test
    fun `a bare configured name -- what both backends report -- resolves`() {
        assertEquals("Board TCP", NextHop.configured("Board TCP", configured)?.name)
    }

    @Test
    fun `the string form resolves by the name inside it`() {
        assertEquals("Board TCP", NextHop.configured("TCPInterface[Board TCP/10.0.0.2:4242]", configured)?.name)
    }

    @Test
    fun `an AutoInterface peer resolves to the one configured AutoInterface`() {
        assertEquals("Auto Discovery", NextHop.configured("AutoInterfacePeer[wlan0/fe80::1]", configured)?.name)
        val two = configured + NextHop.Configured("Auto 2", "AutoInterface")
        assertNull(NextHop.configured("AutoInterfacePeer[wlan0/fe80::1]", two))
    }

    @Test
    fun `an unknown or absent name resolves to nothing`() {
        assertNull(NextHop.configured("Elsewhere", configured))
        assertNull(NextHop.configured(null, configured))
        assertNull(NextHop.configured("", configured))
    }

    @Test
    fun `the carrier comes from the configured type, not from how it was named`() {
        // "Board TCP" does not begin with "TCP": read from the name, it was unknown.
        assertEquals(MeshCarrier.TCP, NextHop.carrier("Board TCP", configured))
        assertEquals(MeshCarrier.BLE, NextHop.carrier("Bluetooth LE", configured))
        assertEquals(MeshCarrier.AUTO, NextHop.carrier("AutoInterfacePeer[wlan0/fe80::1]", configured))
        // Not configured here (a shared instance's own interface): read from the name.
        assertEquals(MeshCarrier.LORA, NextHop.carrier("RNodeInterface[LoRa]", configured))
    }
}

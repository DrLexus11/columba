package network.columba.app.service.mesh

import network.columba.app.service.mesh.InterfaceGuard.Iface
import network.columba.app.service.mesh.InterfaceGuard.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class InterfaceGuardTest {
    private val tcp = Iface(1, enabled = true, online = true, carriesCommandPost = true)
    private val ble = Iface(2, enabled = true, online = true, carriesCommandPost = false)
    private val auto = Iface(3, enabled = true, online = false, carriesCommandPost = false)

    @Test
    fun `the interface carrying the command post's path cannot be switched off`() {
        assertEquals(Verdict.WOULD_ISOLATE, InterfaceGuard.check(listOf(tcp, ble), 1, enable = false))
    }

    @Test
    fun `another interface can, while one stays online`() {
        assertEquals(Verdict.OK, InterfaceGuard.check(listOf(tcp, ble), 2, enable = false))
    }

    @Test
    fun `the last online interface cannot be switched off, even with others enabled but down`() {
        val onlyBle = Iface(2, enabled = true, online = true, carriesCommandPost = false)
        assertEquals(Verdict.WOULD_ISOLATE, InterfaceGuard.check(listOf(onlyBle, auto), 2, enable = false))
    }

    @Test
    fun `switching on is always allowed, and an unknown id is said so`() {
        val off = Iface(4, enabled = false, online = false, carriesCommandPost = false)
        assertEquals(Verdict.OK, InterfaceGuard.check(listOf(tcp, off), 4, enable = true))
        assertEquals(Verdict.UNKNOWN_INTERFACE, InterfaceGuard.check(listOf(tcp), 99, enable = false))
    }

    @Test
    fun `apply refuses a staged set that cuts the command post or leaves nothing online`() {
        assertEquals(Verdict.WOULD_ISOLATE, InterfaceGuard.checkApply(listOf(tcp.copy(enabled = false), ble)))
        assertEquals(Verdict.WOULD_ISOLATE, InterfaceGuard.checkApply(listOf(ble.copy(enabled = false), auto)))
        assertEquals(Verdict.OK, InterfaceGuard.checkApply(listOf(tcp, ble.copy(enabled = false))))
    }
}

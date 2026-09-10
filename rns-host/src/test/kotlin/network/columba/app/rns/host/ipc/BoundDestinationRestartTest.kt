package network.columba.app.rns.host.ipc

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.model.Destination
import network.columba.app.rns.api.model.DestinationType
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.NetworkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BoundDestinationRestartTest {
    @Test
    fun `fresh service binding restores registrations before ready and drops previous account`() =
        runTest {
            val identity = Identity(ByteArray(16), ByteArray(64), null)
            val destination = Destination(ByteArray(16), "00".repeat(16), identity, Direction.IN, DestinationType.SINGLE, "rnstransport", listOf("tak", "task"))

            fun backend(owner: Identity = identity): RnsBackend =
                mockk<RnsBackend>().also {
                    every { it.core.networkStatus } returns MutableStateFlow(NetworkStatus.READY)
                    coEvery { it.lxmf.getLxmfIdentity() } returns Result.success(owner)
                    coEvery { it.core.createDestination(any(), any(), any(), any(), any()) } returns Result.success(destination)
                }
            val first = backend()
            val flow = MutableStateFlow<RnsBackend?>(first)
            val core = BoundRnsCore(flow, backgroundScope)
            runCurrent()
            core.createDestination(identity, destination.direction, destination.type, destination.appName, destination.aspects).getOrThrow()
            flow.value = null
            runCurrent()
            assertEquals(NetworkStatus.SHUTDOWN, core.networkStatus.value)
            val replacement = backend()
            flow.value = replacement
            runCurrent()
            assertEquals(NetworkStatus.READY, core.networkStatus.value)
            val replacementCore = replacement.core
            coVerify(
                exactly = 1,
            ) { replacementCore.createDestination(identity, destination.direction, destination.type, destination.appName, destination.aspects) }
            val failed = backend()
            coEvery { failed.core.createDestination(any(), any(), any(), any(), any()) } returns Result.failure(IllegalStateException("registration refused"))
            flow.value = failed
            runCurrent()
            assertTrue(core.networkStatus.value is NetworkStatus.ERROR)
            val switched = backend(identity.copy(hash = ByteArray(16) { 1 }))
            flow.value = switched
            runCurrent()
            assertEquals(NetworkStatus.READY, core.networkStatus.value)
            val switchedCore = switched.core
            coVerify(exactly = 0) { switchedCore.createDestination(any(), any(), any(), any(), any()) }
        }
}

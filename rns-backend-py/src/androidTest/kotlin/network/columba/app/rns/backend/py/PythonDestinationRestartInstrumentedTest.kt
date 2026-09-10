package network.columba.app.rns.backend.py

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.columba.app.rns.api.model.DestinationType
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.model.NetworkStatus
import network.columba.app.rns.api.model.ReticulumConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PythonDestinationRestartInstrumentedTest {
    @Test
    fun restartRebuildsDestinationAndCallbackBeforeReady() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val directory = java.io.File(context.cacheDir, "destination-restart-${System.nanoTime()}")
            val runtime = PythonRnsRuntime(context)
            val events = PythonEventBridge()
            val core = PythonRnsCore(runtime, events)
            val identity = core.createIdentity().getOrThrow()
            val config =
                ReticulumConfig(
                    storagePath = directory.absolutePath,
                    enabledInterfaces = emptyList(),
                    deliveryIdentityKey = identity.privateKey,
                    preferOwnInstance = true,
                    enableTransport = false,
                )
            try {
                core.initialize(config).getOrThrow()
                val destination = core.createDestination(identity, Direction.IN, DestinationType.SINGLE, "rnstransport", listOf("tak", "task")).getOrThrow()
                val original = runtime.destinations.getValue(destination.hexHash)
                repeat(2) {
                    core.shutdown().getOrThrow()
                    assertTrue(runtime.destinations.isEmpty())
                    core.initialize(config).getOrThrow()
                    assertEquals(NetworkStatus.READY, core.networkStatus.value)
                    val restored = runtime.destinations.getValue(destination.hexHash)
                    assertNotSame(original, restored)
                    core.announceDestination(destination, null).getOrThrow()
                    val packet = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5000) { core.observePackets().first() } }
                    restored["callbacks"]!!["packet"]!!.call(byteArrayOf(7, 8).toPyBytes(), null)
                    assertArrayEquals(byteArrayOf(7, 8), packet.await().data)
                    assertEquals(destination, packet.await().destination)
                }
                core.shutdown().getOrThrow()
                val other = core.createIdentity().getOrThrow()
                core.initialize(config.copy(deliveryIdentityKey = other.privateKey)).getOrThrow()
                assertTrue(!runtime.destinations.containsKey(destination.hexHash))
            } finally {
                core.shutdown().getOrThrow()
                directory.deleteRecursively()
            }
        }
}

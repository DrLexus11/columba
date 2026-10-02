package network.columba.app.rooms

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import network.reticulum.Reticulum
import network.reticulum.interfaces.Interface
import network.reticulum.interfaces.InterfaceAdapter
import network.reticulum.interfaces.local.LocalClientInterface
import network.reticulum.transport.Transport
import network.reticulum.transport.TransportConstants
import tech.torlando.eridanus.rns.RnsBackend
import tech.torlando.eridanus.rns.RnsBackendConfig
import tech.torlando.eridanus.rns.kt.KtRnsDestinationFactory
import tech.torlando.eridanus.rns.kt.KtRnsIdentityFactory
import tech.torlando.eridanus.rns.kt.KtRnsLinkFactory
import tech.torlando.eridanus.rns.kt.KtRnsResourceFactory
import tech.torlando.eridanus.rns.kt.KtRnsTransport
import java.io.File

/**
 * Eridanus's Reticulum backend inside Columba (docs/EridanusMerge.md, step 4).
 *
 * Eridanus's own KtRnsBackend starts rns-android's ReticulumService -- a second
 * foreground service with a second persistent notification beside Columba's.
 * This one runs the same reticulum-kt stack as a plain client of Columba's own
 * shared instance, in the main process, which Columba's single foreground
 * service protects (rns-host MainProcessAnchor). One service, one notification.
 *
 * Everything but start and stop is Eridanus's own: the identity, destination,
 * link, resource and transport factories are reused unchanged.
 *
 * Caches (known identities, paths) are kept in memory and relearned from
 * announces after a restart. rns-android would put them in a second Room
 * database; the shared-instance host, Columba, already persists the paths.
 *
 * Status lines Eridanus pushes ("Connected to hub ...") go to [statusSink],
 * which Columba points at its one notification.
 */
class ColumbaRrcBackend(
    private val statusSink: (String) -> Unit = {},
) : RnsBackend {
    override val identifier: String = "columba"

    // reticulum-kt has no runtime version constant; keep in sync with
    // gradle/libs.versions.toml (reticulumKt).
    override val reticulumVersion: String = "v0.0.22 (reticulum-kt, via Columba)"

    private var scope: CoroutineScope? = null

    @Synchronized
    override fun start(context: Context, config: RnsBackendConfig) {
        if (current() != null) return
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = jobs
        val configDir = File(context.filesDir, "eridanus-rns").apply { mkdirs() }.absolutePath
        // The two hooks rns-android's service sets before starting: how to
        // build the client interface to the shared instance, and how a new
        // interface reaches Transport.
        Reticulum.setLocalClientFactory { port, host ->
            LocalClientInterface(name = "Columba shared instance", tcpPort = port, tcpHost = host)
        }
        Reticulum.setInterfaceRegistrar { iface ->
            if (iface is Interface) Transport.registerInterface(InterfaceAdapter.getOrCreate(iface))
        }
        // Without the job loop nothing in Transport runs -- no announces
        // processed, no link keepalives.
        Transport.configureCoroutineJobLoop(scope = jobs, intervalMs = TransportConstants.JOB_INTERVAL)
        val attached = Reticulum.isSharedInstanceRunning(config.sharedInstancePort)
        if (!attached) {
            // Loud, in one line: rooms need Columba's shared instance on.
            Log.w(TAG, "Columba's shared instance is not running on ${config.sharedInstancePort}; rooms will not connect")
        }
        Reticulum.start(
            configDir = configDir,
            enableTransport = false,
            // Never host: Columba is the host. A client only, or nothing.
            shareInstance = false,
            sharedInstancePort = config.sharedInstancePort,
            connectToSharedInstance = true,
        )
        Log.i(TAG, "Rooms client started; attached to Columba's shared instance: $attached")
    }

    @Synchronized
    override fun stop(context: Context) {
        if (current() != null) runCatching { Reticulum.stop() }
        scope?.cancel()
        scope = null
    }

    override val isRunning: Boolean
        get() = current() != null

    override val connectedToSharedInstance: Boolean
        // The runtime result, not the intent: a client whose interface never
        // came up must not be reported attached (as in KtRnsBackend).
        get() = current()?.isConnectedToSharedInstance == true

    override fun isSharedInstanceRunning(port: Int): Boolean = Reticulum.isSharedInstanceRunning(port)

    override suspend fun restart(context: Context, config: RnsBackendConfig) {
        stop(context)
        // Let the old client's socket close, so the next attach sees the host
        // and not a half-closed connection.
        delay(RESTART_GRACE_MS)
        start(context, config)
    }

    override fun setForegroundStatus(text: String) = statusSink(text)

    // Columba's foreground service holds the locks the stack needs.
    override fun setKeepAliveWakeLock(held: Boolean) = Unit

    override val identities = KtRnsIdentityFactory
    override val destinations = KtRnsDestinationFactory
    override val links = KtRnsLinkFactory
    override val resources = KtRnsResourceFactory
    override val transport = KtRnsTransport

    private fun current(): Reticulum? = runCatching { Reticulum.getInstance() }.getOrNull()

    private companion object {
        const val TAG = "ColumbaRrcBackend"
        const val RESTART_GRACE_MS = 300L
    }
}

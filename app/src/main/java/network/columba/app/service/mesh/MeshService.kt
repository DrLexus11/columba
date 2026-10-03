package network.columba.app.service.mesh

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import network.columba.app.data.database.entity.InterfaceEntity
import network.columba.app.mesh.IColumbaMesh
import network.columba.app.mesh.IColumbaMeshWatcher
import network.columba.app.repository.InterfaceRepository
import network.columba.app.repository.SettingsRepository
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.RnsTransportAdmin
import network.columba.app.rns.host.manager.filterByTransport
import network.columba.app.rns.host.persistence.ReticulumConfigSnapshot
import network.columba.app.service.InterfaceConfigManager
import network.columba.app.service.manager.InterfaceTransportObserver
import network.columba.app.rns.api.RnsCore
import network.columba.app.service.PropagationNodeManager
import network.columba.app.service.tak.CotEndpointManager
import network.columba.app.service.tak.TakIdentity
import java.security.MessageDigest
import javax.inject.Inject

/**
 * The mesh interface the ATAK plugin binds: version 1 of reticulum-atak's
 * `docs/ColumbaInterface.md`.
 *
 * Built on Columba's own state -- the TAK endpoint's member table, the path
 * table, the propagation manager -- and exposing only what the plugin's panel
 * needs. Columba's internal interfaces are never handed out: IRnsCore alone can
 * export the identity's private key.
 *
 * Every call checks its caller against [MeshCallerGate]. The snapshot is kept
 * fresh by a loop while something is bound, so a read never waits on the path
 * table; watchers hear a change at most every [REFRESH_MS].
 */
@AndroidEntryPoint
class MeshService : Service() {
    @Inject lateinit var endpoint: CotEndpointManager

    @Inject lateinit var propagation: PropagationNodeManager

    @Inject lateinit var rnsCore: RnsCore

    @Inject lateinit var settings: SettingsRepository

    // The interfaces capability: the same objects Columba's own interface screen uses.
    @Inject lateinit var interfaceRepository: InterfaceRepository

    @Inject lateinit var transportAdmin: RnsTransportAdmin

    @Inject lateinit var transportObserver: InterfaceTransportObserver

    @Inject lateinit var rnsBackend: RnsBackend

    @Inject lateinit var configManager: InterfaceConfigManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Interface commands, one at a time (underCommandLock). */
    private val commandLock = Mutex()
    private val gate = MeshCallerGate()
    private val watchers = RemoteCallbackList<IColumbaMeshWatcher>()
    private var refresher: Job? = null

    @Volatile private var current: MeshSnapshot? = null

    override fun onBind(intent: Intent?): IBinder {
        if (refresher == null) refresher = scope.launch { refreshLoop() }
        return binder
    }

    override fun onDestroy() {
        binder.detach()
        scope.cancel()
        watchers.kill()
        super.onDestroy()
    }

    private suspend fun refreshLoop() {
        while (scope.isActive) {
            val fresh = runCatching { build() }.onFailure { Log.w(TAG, "Snapshot failed: ${it.message}") }.getOrNull()
            if (fresh != null) {
                // "at" changes every pass; anything else is a change worth telling.
                val changed = current?.copy(at = 0) != fresh.copy(at = 0)
                current = fresh
                if (changed) broadcast(fresh.toJson().toString())
            }
            delay(REFRESH_MS)
        }
    }

    private fun broadcast(json: String) {
        val count = watchers.beginBroadcast()
        try {
            for (i in 0 until count) {
                runCatching { watchers.getBroadcastItem(i).changed(json) }
            }
        } finally {
            watchers.finishBroadcast()
        }
    }

    private suspend fun build(): MeshSnapshot {
        val now = System.currentTimeMillis()
        val view = endpoint.meshView(now)
        val relay = propagation.currentRelay.value
        val entities = interfaceRepository.allInterfaceEntities.first()
        val configured = configuredOf(entities)
        val peers =
            view?.members.orEmpty().map { member ->
                val route = route(member.destinationHash)
                MeshSnapshot.Peer(
                    uid = TakIdentity.uidFor(member.destinationHash),
                    callsign = member.callsign.ifBlank { null },
                    role = member.role.ifBlank { null },
                    heard = member.heard,
                    path = route.path,
                    hops = route.hops,
                    carrier = NextHop.carrier(route.iface, configured),
                    iface = route.iface,
                )
            }
        val ifaces = interfaces(peers, entities, configured)
        return MeshSnapshot(
            at = now,
            node =
                MeshSnapshot.Node(
                    uid = view?.let { TakIdentity.uidFor(it.nodeHash) },
                    callsign = view?.callsign,
                    control = settings.takAtakControlFlow.first(),
                    running = view != null,
                ),
            propagation =
                relay?.let {
                    val route = route(it.destinationHash.hexToBytes())
                    MeshSnapshot.Propagation(
                        hash = it.destinationHash.lowercase(),
                        name = it.displayName.ifBlank { null },
                        path = route.path,
                        hops = route.hops,
                        carrier = NextHop.carrier(route.iface, configured),
                        // Not known yet: a command post's propagation node and
                        // its TAK node are different identities on the deck.
                        // Null rather than a guess (ColumbaInterface.md).
                        isCommandPost = null,
                        lastSync = propagation.lastSyncTimestamp.value,
                    )
                },
            peers = peers,
            interfacesLive = ifaces.live,
            interfacesPending = ifaces.pending,
            interfaces = ifaces.list,
        )
    }

    private class Ifaces(val live: Boolean, val pending: Boolean, val list: List<MeshSnapshot.Iface>)

    private fun configuredOf(entities: List<InterfaceEntity>) = entities.map { NextHop.Configured(it.name, it.type) }

    /**
     * Every configured interface (Columba's database), joined by name -- as
     * Columba's own interface screen joins them -- with the running stack's state.
     * A failed read of that state throws: the snapshot pass is abandoned and the
     * last known one kept, rather than calling every interface down.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun interfaces(
        peers: List<MeshSnapshot.Peer>,
        entities: List<InterfaceEntity>,
        configured: List<NextHop.Configured>,
    ): Ifaces {
        val running =
            ((transportAdmin.getDebugInfo()["interfaces"] as? List<*>) ?: emptyList<Any>())
                .mapNotNull { it as? Map<String, Any?> }
                .associateBy { it["name"] as? String }
        val live = rnsBackend.capabilities.value.interfaces.hotReloadInterfaces
        // The interfaces the command post's paths leave through (NextHop: the
        // backends report a configured name, not "Type[name/address]").
        val commandPostRoutes =
            peers
                .filter { it.role == MeshSnapshot.COMMAND_POST_ROLE && it.path }
                .mapNotNull { peer -> NextHop.configured(peer.iface, configured)?.name }
                .toSet()
        val staged = stagedNames(live)
        val list =
            entities.map { entity ->
                val run = running[entity.name]
                MeshSnapshot.Iface(
                    id = entity.id,
                    name = entity.name,
                    type = entity.type,
                    carrier = MeshCarrier.ofConfigType(entity.type),
                    enabled = entity.enabled,
                    online = run?.get("online") as? Boolean ?: false,
                    rxBytes = (run?.get("rx_bytes") as? Number)?.toLong(),
                    txBytes = (run?.get("tx_bytes") as? Number)?.toLong(),
                    reason = (run?.get("status_reason") as? String)?.takeIf { it.isNotBlank() },
                    carriesCommandPost = entity.name in commandPostRoutes,
                    pending = entity.name in staged,
                )
            }
        return Ifaces(live, list.any { it.pending }, list)
    }

    /**
     * Staged, where switches are not live: the interfaces whose wanted state
     * differs from the configuration the stack was last started with
     * (ReticulumConfigSnapshot, written on every start and Apply). Both are
     * filtered for the current transport, so an interface the transport leaves
     * out, or one that failed to come up, is not staged -- comparing with what
     * is running called those pending for ever. Empty with no record to compare.
     */
    private suspend fun stagedNames(live: Boolean): Set<String> {
        if (live) return emptySet()
        val applied =
            ReticulumConfigSnapshot.read(this)?.configWithoutKey?.enabledInterfaces?.mapTo(HashSet()) { it.name }
                ?: return emptySet()
        val wanted =
            filterByTransport(interfaceRepository.enabledInterfaces.first(), transportObserver.snapshotTransport())
                .mapTo(HashSet()) { it.name }
        return (wanted - applied) + (applied - wanted)
    }

    private class Route(val path: Boolean, val hops: Int?, val iface: String?)

    /**
     * A failed query throws rather than answering "no path": during a backend
     * restart that would tell every watcher the whole team had dropped off.
     * The throw aborts this pass of [build], and [refreshLoop] keeps the last
     * snapshot that was actually known.
     */
    private suspend fun route(hash: ByteArray): Route {
        if (!rnsCore.hasPath(hash)) return Route(false, null, null)
        return Route(
            path = true,
            hops = rnsCore.getHopCount(hash),
            iface = rnsCore.getNextHopInterfaceName(hash),
        )
    }

    /** The calling uid's packages, for the gate and for the command log. */
    private fun callerPackages(): List<String> =
        packageManager.getPackagesForUid(Binder.getCallingUid())?.toList().orEmpty()

    private fun allowed(packages: List<String>): Boolean = gate.allows(packages) { certDigests(it) }

    private fun certDigests(packageName: String): List<String> =
        runCatching {
            val signatures =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageManager
                        .getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                        .signingInfo
                        ?.apkContentsSigners
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
                }
            signatures.orEmpty().map { sha256(it.toByteArray()) }
        }.getOrDefault(emptyList())

    private val binder = MeshBinder(this)

    /**
     * The binder ATAK holds. Not an inner object of the service: the caller's
     * process keeps a binder reachable until its own garbage collection, and
     * an inner object kept the destroyed service with it -- LeakCanary caught
     * one per ATAK restart (2026-10-03). The service is let go in onDestroy;
     * a call after that is answered as "not ready", never with an exception,
     * which would reach ATAK as an unchecked one.
     */
    private class MeshBinder(
        service: MeshService,
    ) : IColumbaMesh.Stub() {
        @Volatile private var service: MeshService? = service

        fun detach() {
            service = null
        }

        override fun version(): Int {
            val s = service ?: return MeshSnapshot.VERSION
            return if (s.allowed(s.callerPackages())) MeshSnapshot.VERSION else -1
        }

        override fun snapshot(): String {
            val s = service?.takeIf { it.allowed(it.callerPackages()) } ?: return ""
            val snapshot = s.current ?: runBlocking { runCatching { s.build() }.getOrNull() } ?: return "" // THREADING: allowed
            return snapshot.toJson().toString()
        }

        override fun watch(watcher: IColumbaMeshWatcher?) {
            val s = service ?: return
            if (watcher == null || !s.allowed(s.callerPackages())) return
            s.watchers.register(watcher)
            s.current?.let { runCatching { watcher.changed(it.toJson().toString()) } }
        }

        override fun unwatch(watcher: IColumbaMeshWatcher?) {
            if (watcher != null) service?.watchers?.unregister(watcher)
        }

        override fun capabilities(): Int {
            val s = service ?: return 0
            return if (s.allowed(s.callerPackages())) MeshSnapshot.CAP_INTERFACES else 0
        }

        override fun announce(): Int = command("announce") { s, packages -> s.announceFor(packages) }

        override fun setInterfaceEnabled(
            id: Long,
            enabled: Boolean,
        ): Int = command("setInterfaceEnabled($id, $enabled)") { s, packages -> s.setInterfaceFor(packages, id, enabled) }

        override fun applyInterfaces(): Int = command("applyInterfaces") { s, packages -> s.applyInterfacesFor(packages) }

        /** A command, logged with its caller and outcome (OpenDecisions 1). */
        private fun command(
            name: String,
            run: (MeshService, List<String>) -> Int,
        ): Int {
            val s = service ?: return ERR_NOT_READY
            val packages = s.callerPackages()
            val result = run(s, packages)
            Log.i(TAG, "$name from ${packages.joinToString()}: ${resultName(result)}")
            return result
        }
    }

    private fun announceFor(packages: List<String>): Int {
        val now = System.currentTimeMillis()
        return when {
            !allowed(packages) -> ERR_CALLER
            !controlAllowed() -> ERR_CONTROL_OFF
            floor.remaining(now) > 0 -> ERR_RATE_LIMITED
            !announced() -> ERR_NOT_READY
            else -> OK.also { floor.sent(now) }
        }
    }

    // The commands run on binder threads, never the main one: blocking there is
    // what a synchronous AIDL call is.
    private fun controlAllowed(): Boolean = runBlocking { settings.takAtakControlFlow.first() } // THREADING: allowed

    private fun announced(): Boolean =
        runBlocking(Dispatchers.IO) { withTimeoutOrNull(ANNOUNCE_TIMEOUT_MS) { endpoint.announceNow() } } == true // THREADING: allowed

    private fun guardView(snapshot: MeshSnapshot) =
        snapshot.interfaces.map { InterfaceGuard.Iface(it.id, it.enabled, it.online, it.carriesCommandPost) }

    /**
     * One interface command at a time, decided on a snapshot built inside the
     * lock -- not the cached one, whose command post routes may be stale. Binder
     * serves calls concurrently: two switches, each checked while the other's
     * interface was still online, could together cut this phone off. Null when
     * the state could not be read in time.
     */
    private fun <T> underCommandLock(block: suspend (MeshSnapshot) -> T): T? =
        runBlocking(Dispatchers.IO) { // THREADING: allowed
            withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
                commandLock.withLock { runCatching { build() }.getOrNull()?.let { block(it) } }
            }
        }

    private fun setInterfaceFor(
        packages: List<String>,
        id: Long,
        enabled: Boolean,
    ): Int =
        when {
            !allowed(packages) -> ERR_CALLER
            !controlAllowed() -> ERR_CONTROL_OFF
            else -> underCommandLock { fresh -> switchInterface(fresh, id, enabled) } ?: ERR_NOT_READY
        }

    private suspend fun switchInterface(
        fresh: MeshSnapshot,
        id: Long,
        enabled: Boolean,
    ): Int =
        when (InterfaceGuard.check(guardView(fresh), id, enabled)) {
            InterfaceGuard.Verdict.UNKNOWN_INTERFACE -> ERR_UNKNOWN_INTERFACE
            InterfaceGuard.Verdict.WOULD_ISOLATE -> ERR_WOULD_ISOLATE
            InterfaceGuard.Verdict.OK -> {
                interfaceRepository.toggleInterfaceEnabled(id, enabled)
                if (fresh.interfacesLive) {
                    // As Columba's own screen does on the Kotlin backend.
                    val configs = interfaceRepository.enabledInterfaces.first()
                    transportAdmin.reloadInterfaces(filterByTransport(configs, transportObserver.snapshotTransport()))
                    OK
                } else {
                    OK_PENDING
                }
            }
        }

    private fun applyInterfacesFor(packages: List<String>): Int =
        when {
            !allowed(packages) -> ERR_CALLER
            !controlAllowed() -> ERR_CONTROL_OFF
            else -> underCommandLock { fresh -> applyStaged(fresh) } ?: ERR_NOT_READY
        }

    private fun applyStaged(fresh: MeshSnapshot): Int =
        when {
            fresh.interfacesLive || !fresh.interfacesPending -> OK
            InterfaceGuard.checkApply(guardView(fresh)) == InterfaceGuard.Verdict.WOULD_ISOLATE -> ERR_WOULD_ISOLATE
            // The same path as Columba's own "Apply & Restart": it takes seconds and
            // restarts :reticulum, so it runs on its own; the reply says it started.
            else -> OK.also { scope.launch { configManager.applyInterfaceChanges() } }
        }

    companion object {
        private const val TAG = "MeshService"

        /** How often the snapshot is rebuilt, and so the most often a watcher hears. */
        const val REFRESH_MS = 2_000L

        private const val ANNOUNCE_TIMEOUT_MS = 10_000L
        private const val COMMAND_TIMEOUT_MS = 10_000L

        const val OK = 0
        const val ERR_CALLER = 1
        const val ERR_CONTROL_OFF = 2
        const val ERR_NOT_READY = 3
        const val ERR_RATE_LIMITED = 4
        const val ERR_WOULD_ISOLATE = 5
        const val ERR_UNKNOWN_INTERFACE = 6
        const val OK_PENDING = 7

        /** One floor for the process: a rebound service must not reset it. */
        private val floor = MeshAnnounceFloor()

        private fun resultName(code: Int) =
            when (code) {
                OK -> "ok"
                ERR_CALLER -> "refused: caller"
                ERR_CONTROL_OFF -> "refused: control off"
                ERR_NOT_READY -> "not ready"
                ERR_RATE_LIMITED -> "rate limited"
                ERR_WOULD_ISOLATE -> "refused: would isolate"
                ERR_UNKNOWN_INTERFACE -> "unknown interface"
                OK_PENDING -> "staged"
                else -> "code $code"
            }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

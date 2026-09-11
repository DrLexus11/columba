package network.columba.app.service.tak

import android.net.TrafficStats
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import network.columba.app.di.ApplicationScope
import network.columba.app.repository.SettingsRepository
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.model.Destination
import network.columba.app.rns.api.model.DestinationType
import network.columba.app.rns.api.model.Direction
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The local CoT endpoint: ATAK connects to 127.0.0.1, never to anyone's IP.
 *
 * Decision 1 of docs/TAKNative.md, and the Android half of `tools/cot_bridge.py`.
 * Each participant runs one of these, so a partition never looks to ATAK like a
 * disconnected server -- the app keeps running and the data thins out. Pointing
 * every ATAK at the command post instead would end an exercise for everyone the
 * moment the command post went away, including two people standing next to each
 * other.
 *
 * Everything ATAK sends goes to the team's GROUP destination; everything the
 * team sends is written back to every connected client. Command is a member of
 * the group rather than a hop in it, which is what lets a team keep working
 * with command out of range.
 */
@Singleton
class CotEndpointManager
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val rnsCore: RnsCore,
        private val rnsLxmf: RnsLxmf,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        companion object {
            private const val TAG = "CotEndpointManager"

            /**
             * The port ATAK is pointed at. Fixed, because it is one more thing
             * that can be wrong in a field configuration and nothing is gained
             * by moving it.
             */
            const val PORT = 8087

            /**
             * Loopback only, and deliberately not configurable.
             *
             * This endpoint applies no authentication: it assumes only this
             * device can reach it. Binding it to a routable address would hand
             * the team's mesh traffic to anyone who could open a socket to the
             * phone, with no announce, no key and no trace.
             *
             * Loopback on Android is shared between apps rather than private to
             * one, which is the whole mechanism -- it is how ATAK, a separate
             * app, reaches this at all. The consequence is that any app on the
             * device can also inject CoT here, which is why the endpoint is off
             * until an operator turns it on.
             */
            private const val BIND_HOST = "127.0.0.1"

            private const val READ_BUFFER = 4096

            /**
             * How long to wait before retrying a failed listener.
             *
             * The usual cause is the port still being held by a previous
             * instance during a restart, which clears on its own.
             */
            private const val RETRY_DELAY_MS = 5_000L

            /**
             * Socket tag for the endpoint's own sockets.
             *
             * Android raises an UntaggedSocketViolation for every socket
             * opened without one. This traffic is loopback, so the accounting
             * it enables is of no interest -- but an untagged socket logs a
             * StrictMode violation on every accept, and a log that cries wolf
             * on healthy behaviour is one nobody reads when something is
             * actually wrong.
             */
            private const val SOCKET_TAG = 0x7A4B
        }

        /** What the endpoint is doing, for the settings screen. */
        sealed interface State {
            data object Stopped : State

            data class Listening(val team: String, val destinationHash: String, val clients: Int) : State

            data class Failed(val reason: String) : State
        }

        private val _state = MutableStateFlow<State>(State.Stopped)
        val state: StateFlow<State> = _state.asStateFlow()

        private var supervisor: Job? = null

        // Sockets rather than writers, so a client that has gone away can be
        // dropped by closing it. Guarded because the accept loop, each client
        // reader and the mesh listener all touch it.
        private val clients = mutableListOf<Socket>()
        private val clientsLock = Mutex()

        fun start() {
            if (supervisor != null) return
            supervisor =
                scope.launch {
                    combine(
                        settingsRepository.takEndpointEnabledFlow,
                        settingsRepository.takTeamFlow,
                        settingsRepository.takFleetSecretFlow,
                    ) { enabled, team, secret -> Triple(enabled, team, secret) }
                        .distinctUntilChanged()
                        // collectLatest, so changing the team tears the old
                        // endpoint down before the new one binds. Two listeners
                        // on one port would leave ATAK talking to whichever won
                        // the race, which is not a thing anyone could diagnose
                        // from the map.
                        .collectLatest { (enabled, team, secret) ->
                            if (!enabled || secret.isNullOrEmpty()) {
                                // Logged rather than passed over in silence.
                                // "Configured but switched off" and "never
                                // started at all" look identical from outside
                                // the process otherwise, and telling them apart
                                // was the first thing needed on hardware.
                                Log.i(
                                    TAG,
                                    "Not listening: " +
                                        if (!enabled) "endpoint is switched off" else "no fleet secret",
                                )
                                _state.value = State.Stopped
                                return@collectLatest
                            }
                            runEndpoint(team, secret)
                        }
                }
        }

        fun stop() {
            scope.launch {
                supervisor?.cancelAndJoin()
                supervisor = null
                closeAllClients()
                _state.value = State.Stopped
            }
        }

        private suspend fun runEndpoint(team: String, secret: String) {
            val keys =
                try {
                    Keys(team, TakGroups.secretBytes(secret))
                } catch (error: IllegalArgumentException) {
                    // A secret too short to use is a provisioning mistake, not
                    // a transient fault: say so and stay stopped rather than
                    // retrying a value that cannot become valid.
                    Log.w(TAG, "Not starting: ${error.message}")
                    _state.value = State.Failed(error.message ?: "invalid fleet secret")
                    return
                }

            while (currentCoroutineContext().isActive) {
                try {
                    serve(keys)
                } catch (error: IOException) {
                    // Almost always the port still held by the previous
                    // instance across a restart, which clears on its own.
                    Log.w(TAG, "Endpoint stopped: ${error.message}")
                    _state.value = State.Failed(error.message ?: "listener failed")
                    delay(RETRY_DELAY_MS)
                }
            }
        }

        /**
         * A setup step that did not succeed, as the one exception
         * [runEndpoint] retries on.
         *
         * Every step below fails for the same reason -- the backend is not
         * ready yet -- and each has to say which step it was. One helper rather
         * than a throw per step means the next step added is one line, and the
         * retry contract stays stated in a single place.
         */
        private fun <T> Result<T>.orFail(step: String): T =
            getOrElse { throw IOException("$step: ${it.message}", it) }

        private suspend fun serve(keys: Keys) {
            val identity =
                rnsCore.identityFromPrivateKey(keys.identityKey)
                    .orFail("could not derive the team identity")
            // IN and OUT are separate objects in RNS even though a group is
            // symmetric -- every member both speaks and listens on it. Both are
            // built from the same derived identity so every member lands on the
            // same address.
            val inbound =
                rnsCore.createGroupDestination(identity, Direction.IN, TakGroups.APP, keys.aspects, keys.groupKey)
                    .orFail("could not join the team")
            val outbound =
                rnsCore.createGroupDestination(identity, Direction.OUT, TakGroups.APP, keys.aspects, keys.groupKey)
                    .orFail("could not address the team")
            // The UID names *this node*, never the team. Deriving it from
            // `inbound` -- the group destination -- gave every member of the
            // team the same UID, which ATAK draws as one track teleporting
            // between everybody's positions.
            val nodeIdentity =
                rnsLxmf.getLxmfIdentity().orFail("no node identity yet")
            val node =
                rnsCore.createDestination(
                    nodeIdentity,
                    Direction.IN,
                    DestinationType.SINGLE,
                    TakIdentity.NODE_APP,
                    TakIdentity.NODE_ASPECTS,
                ).orFail("could not claim a node address")

            // One pipeline per run: the learned ATAK UID belongs to this
            // endpoint's lifetime, not to the process.
            val pipeline = CotOutbound(TakIdentity.uidFor(node.hash))

            withContext(Dispatchers.IO) {
                // Applies to every socket this thread opens from here on,
                // which is the listener and each connection accepted from it.
                TrafficStats.setThreadStatsTag(SOCKET_TAG)
                ServerSocket().use { listener ->
                    // accept() blocks in a way cancellation cannot reach, so
                    // closing the socket is the only thing that unblocks it.
                    // Without this, changing team would leave the old listener
                    // holding port 8087 until someone happened to connect --
                    // and the new one would fail to bind for as long as that
                    // lasted, which is forever if nobody does.
                    currentCoroutineContext().job.invokeOnCompletion {
                        runCatching { listener.close() }
                    }
                    // Set before bind: a restart that finds the port in
                    // TIME_WAIT would otherwise refuse to listen for a minute
                    // or more, which in the field reads as the app being broken.
                    listener.reuseAddress = true
                    listener.bind(java.net.InetSocketAddress(InetAddress.getByName(BIND_HOST), PORT), 8)
                    Log.i(
                        TAG,
                        "Team ${keys.team} on ${inbound.hexHash} as ${pipeline.ourUid}; " +
                            "point ATAK at $BIND_HOST:$PORT, TCP, no SSL",
                    )
                    publishState(keys.team, inbound, clientsLock.withLock { clients.size })

                    val fromMesh = launch { pumpMeshToClients(inbound) }
                    try {
                        while (isActive) {
                            val connection = listener.accept()
                            connection.tcpNoDelay = true
                            launch { serveClient(connection, outbound, pipeline, keys.team, inbound) }
                        }
                    } finally {
                        fromMesh.cancel()
                        // NonCancellable because this finally runs *during*
                        // cancellation, and a suspending call there would be
                        // cancelled before it did anything -- leaving every
                        // ATAK connection open against an endpoint that is no
                        // longer reading them.
                        withContext(NonCancellable) { closeAllClients() }
                    }
                }
            }
        }

        // ---- ATAK -> mesh ----

        private suspend fun serveClient(
            connection: Socket,
            outbound: Destination,
            pipeline: CotOutbound,
            team: String,
            inbound: Destination,
        ) {
            TrafficStats.setThreadStatsTag(SOCKET_TAG)
            val stream = CotStream()
            val buffer = ByteArray(READ_BUFFER)
            publishState(team, inbound, clientsLock.withLock { clients.add(connection); clients.size })
            try {
                val input = connection.getInputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    for (event in stream.feed(buffer, read)) {
                        forwardToMesh(event, outbound, pipeline)
                    }
                }
            } catch (_: IOException) {
                // An operator closing ATAK is the ordinary way this ends.
            } finally {
                withContext(NonCancellable) {
                    val remaining = clientsLock.withLock { clients.remove(connection); clients.size }
                    runCatching { connection.close() }
                    publishState(team, inbound, remaining)
                }
            }
        }

        private suspend fun forwardToMesh(
            cotXml: String,
            outbound: Destination,
            pipeline: CotOutbound,
        ) {
            val known = pipeline.atakUid
            val frame = pipeline.frame(cotXml) ?: return
            if (known == null) {
                pipeline.atakUid?.let { Log.i(TAG, "This ATAK calls itself $it") }
            }
            rnsCore.sendPacket(outbound, frame)
                .onFailure { Log.w(TAG, "Could not send to the team: ${it.message}") }
        }

        // ---- mesh -> ATAK ----

        private suspend fun pumpMeshToClients(inbound: Destination) {
            rnsCore.observePackets().collect { packet ->
                if (!packet.destination.hash.contentEquals(inbound.hash)) return@collect
                val xml =
                    try {
                        CotTier2.decode(packet.data)
                    } catch (_: IllegalArgumentException) {
                        // A frame we cannot read is ordinary on a shared
                        // destination: an older node, a newer dictionary, or
                        // simply not ours.
                        return@collect
                    }
                writeToClients(xml.toByteArray(Charsets.UTF_8))
            }
        }

        private suspend fun writeToClients(payload: ByteArray) {
            // Set on whichever IO thread does the writing, for the same reason.
            TrafficStats.setThreadStatsTag(SOCKET_TAG)
            // Snapshot under the lock, write outside it. A client whose
            // receive window has filled blocks on write for as long as it
            // takes, and holding the lock across that would stall the accept
            // loop and every other client behind the slowest one.
            val targets = clientsLock.withLock { clients.toList() }
            val dead = mutableListOf<Socket>()
            withContext(Dispatchers.IO) {
                for (client in targets) {
                    try {
                        client.getOutputStream().apply {
                            write(payload)
                            flush()
                        }
                    } catch (_: IOException) {
                        dead.add(client)
                    }
                }
            }
            if (dead.isEmpty()) return
            clientsLock.withLock { clients.removeAll(dead) }
            for (client in dead) runCatching { client.close() }
        }

        private suspend fun closeAllClients() {
            val closing = clientsLock.withLock { clients.toList().also { clients.clear() } }
            for (client in closing) runCatching { client.close() }
        }

        private fun publishState(team: String, inbound: Destination, clientCount: Int) {
            _state.value = State.Listening(team, inbound.hexHash, clientCount)
        }

        /**
         * Everything derived from the team name and the fleet secret.
         *
         * Computed once per endpoint rather than per packet: these are HMACs
         * over a secret, and recomputing them in a send path would be both
         * waste and one more place for the secret to be held.
         */
        private class Keys(val team: String, secret: ByteArray) {
            val groupKey: ByteArray = TakGroups.groupKey(team, secret)
            val identityKey: ByteArray = TakGroups.groupIdentityKey(team, secret)
            val aspects: List<String> = TakGroups.groupAspects(team, secret)
        }
    }

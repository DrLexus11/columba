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
import network.columba.app.service.PositionCodec
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
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
 * Everything ATAK sends is addressed to each member of the team; everything
 * the team sends is written back to every connected client. Command is a member
 * rather than a hop, which is what lets a team keep working with command out of
 * range.
 *
 * Not a GROUP destination, which is what this used to be. Pivot 5 of
 * TAKIntegrationPivots.md, measured: a group packet reaches only peers on the
 * same interface as the sender, and any intermediary at all -- including a
 * shared Reticulum instance on the same host -- spends its single hop. A team
 * is a membership set instead, learned from announces, and addressed traffic
 * routes.
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

            /**
             * How often this node re-announces its membership.
             *
             * Announces are how Reticulum learns paths and are deliberately
             * expensive, so this is not a heartbeat. Members are kept far
             * longer than this, so a missed announce costs nothing.
             */
            private const val ANNOUNCE_INTERVAL_MS = 30L * 60 * 1000

            /**
             * How long a peer's position is worth drawing before the track
             * should go grey.
             *
             * Twice the report floor: one missed report is a radio being a
             * radio, two is worth an operator noticing. A track that never goes
             * stale is a marker where somebody used to be, still being trusted.
             */
            private const val POSITION_STALE_MS = 2 * CotPosition.DEFAULT_INTERVAL_MS
        }

        /** What the endpoint is doing, for the settings screen. */
        sealed interface State {
            data object Stopped : State

            data class Listening(
                val team: String,
                val destinationHash: String,
                val clients: Int,
                val members: Int = 0,
            ) : State

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
            // No group destination. Pivot 5: a group packet reaches only peers
            // on the same interface as the sender, so a team is a membership
            // set and traffic for it is addressed to each member.
            //
            // The UID names *this node*, never the team.
            val nodeIdentity = rnsLxmf.getLxmfIdentity().orFail("no node identity yet")
            val node =
                rnsCore.createDestination(
                    nodeIdentity,
                    Direction.IN,
                    DestinationType.SINGLE,
                    TakIdentity.NODE_APP,
                    TakIdentity.NODE_ASPECTS,
                ).orFail("could not claim a node address")

            // One session per run: the learned ATAK UID, the member list and
            // the position cadence all belong to this endpoint's lifetime
            // rather than to the process.
            val session = Session(
                node = node,
                team = keys.team,
                pipeline = CotOutbound(TakIdentity.uidFor(node.hash)),
                registry = TakMembership.Registry(keys.team, keys.secret, ownHash = node.hash),
                payload = TakMembership.memberPayload(keys.team, keys.secret, keys.callsign),
            )

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
                        "Team ${keys.team}, this node is ${session.pipeline.ourUid}; " +
                            "point ATAK at $BIND_HOST:$PORT, TCP, no SSL",
                    )
                    publishState(session, clientsLock.withLock { clients.size })

                    val fromMesh = launch { pumpMeshToClients(session) }
                    val fromAnnounces = launch { learnMembers(session) }
                    val beacon = launch { announceForever(session) }
                    try {
                        while (isActive) {
                            val connection = listener.accept()
                            connection.tcpNoDelay = true
                            launch { serveClient(connection, session) }
                        }
                    } finally {
                        beacon.cancel()
                        fromAnnounces.cancel()
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

        // ---- membership ----

        /**
         * Say who we are, so peers can address us.
         *
         * Without this the UID derived in pivot 1 decodes to a destination
         * nothing has a path to: correct, and unreachable. The payload carries
         * an HMAC of the team rather than its name, so announcing does not undo
         * what TakGroups goes to trouble to hide.
         */
        private suspend fun announce(session: Session) {
            rnsCore.announceDestination(session.node, session.payload)
                .onFailure { Log.w(TAG, "Announce failed: ${it.message}") }
        }

        private suspend fun announceForever(session: Session) {
            announce(session)
            while (currentCoroutineContext().isActive) {
                delay(ANNOUNCE_INTERVAL_MS)
                announce(session)
            }
        }

        /**
         * Learn team members from the announces this node already hears.
         *
         * Most of what arrives here is not ours -- another team, or software
         * that is not this project. The registry decides, and a payload it does
         * not recognise is ordinary.
         */
        private suspend fun learnMembers(session: Session) {
            rnsCore.observeAnnounces().collect { announceEvent ->
                val arrival =
                    session.registry.remember(
                        announceEvent.destinationHash,
                        announceEvent.appData,
                        System.currentTimeMillis(),
                    )
                if (arrival != TakMembership.Arrival.NEW) return@collect
                val claims = session.registry.describe(announceEvent.destinationHash)
                Log.i(
                    TAG,
                    "Team member ${claims?.callsign ?: "?"} is " +
                        TakIdentity.uidFor(announceEvent.destinationHash),
                )
                publishState(session, clientsLock.withLock { clients.size })
                // Say who we are back. A node that starts late hears everyone
                // who announces after it and nobody who announced before, so
                // without this the first node up stays invisible to the second
                // until the next re-announce -- half an hour of a team that
                // cannot see its own members. Greeting only happens for a
                // member that was not already known, so the reply it provokes
                // finds a known member and goes no further.
                if (session.registry.shouldGreet(System.currentTimeMillis())) {
                    announce(session)
                }
            }
        }

        // ---- ATAK -> mesh ----

        private suspend fun serveClient(connection: Socket, session: Session) {
            TrafficStats.setThreadStatsTag(SOCKET_TAG)
            val stream = CotStream()
            val buffer = ByteArray(READ_BUFFER)
            publishState(session, clientsLock.withLock { clients.add(connection); clients.size })
            try {
                val input = connection.getInputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    for (event in stream.feed(buffer, read)) {
                        forwardToMesh(event, session)
                    }
                }
            } catch (_: IOException) {
                // An operator closing ATAK is the ordinary way this ends.
            } finally {
                withContext(NonCancellable) {
                    val remaining = clientsLock.withLock { clients.remove(connection); clients.size }
                    runCatching { connection.close() }
                    publishState(session, remaining)
                }
            }
        }

        private suspend fun forwardToMesh(cotXml: String, session: Session) {
            // The typed codecs come first, and the echo guard is asked once
            // for both rather than once each -- through the pipeline's own
            // check, not a second copy of it.
            //
            // Position because 94% of what ATAK emits is a position report, and
            // as compressed CoT addressed to every member that is most of a
            // LoRa channel. Chat because a real GeoChat line compresses to more
            // than one packet, so on tier 2 it is not expensive, it is
            // undeliverable.
            // The echo guard first, for everything, before anything is
            // learned from the event or spent on it. Our own self-report
            // coming back is a well-formed self-report, and learning from it
            // would teach this pipeline our own UID.
            if (session.pipeline.isEcho(cotXml)) return
            // Then learn, and only then route. This used to be a side effect
            // of the tier-2 path, which the typed codecs return before ever
            // reaching -- so a session that opened with an SPI put
            // ANDROID-<device>.SPI1 on the air as tier 2, bypassing the very
            // scrubbing the marker codec added. The typed handlers do not need
            // the learned UID; only the tier-2 rewrite does.
            val known = session.pipeline.atakUid
            session.pipeline.observe(cotXml)
            if (known == null) {
                session.pipeline.atakUid?.let { Log.i(TAG, "This ATAK calls itself $it") }
            }
            if (CotPosition.isPosition(cotXml) && forwardPosition(cotXml, session)) return
            if (forwardChat(cotXml, session)) return
            if (forwardMarker(cotXml, session)) return
            val frame = session.pipeline.frame(cotXml) ?: return
            fanOut(frame, session)
        }

        /**
         * Send a position as twenty-one bytes, if it is due.
         *
         * True when this event was handled here, whether or not anything went
         * on the air: a suppressed report is handled, and must not then also be
         * sent as CoT.
         */
        private suspend fun forwardPosition(cotXml: String, session: Session): Boolean {
            val fix = CotPosition.fixFromCot(cotXml, TakMembership.senderIdFor(session.node.hash))
                // Shaped like a position and carrying none. Not ours to encode,
                // so it falls through to tier 2 rather than being dropped.
                ?: return false
            if (!session.gate.allows(fix, System.currentTimeMillis())) return true
            fanOut(PositionCodec.encode(fix), session)
            return true
        }

        /**
         * Send a chat line or a receipt as tens of bytes, if it is one.
         *
         * True when this event was handled here, so it does not then also go
         * out as CoT.
         */
        private suspend fun forwardChat(cotXml: String, session: Session): Boolean {
            val frame =
                CotChat.chatFromCot(cotXml, TakMembership.senderIdFor(session.node.hash))
                    ?: return false
            // A direct message goes to one member, not to the team. ATAK puts
            // the recipient's callsign in the chatroom field, so without the
            // recipient carried separately every private line was fanned out
            // to everybody -- not a cost problem, a confidentiality one.
            //
            // This works because peers are announced under their
            // Reticulum-rooted UID, so ATAK addresses them by it and
            // destinationFor() reverses it. That is pivot 1 paying for itself.
            val recipient = CotChat.decode(frame)?.recipient.orEmpty()
            if (recipient.isEmpty()) {
                fanOut(frame, session)
                return true
            }
            // Addressed to somebody who is not a peer of ours: a server
            // contact, or a callsign this mesh has never announced. The
            // fan-out is not a fallback here -- broadcasting a line meant for
            // one person is the bug this whole path exists to stop, and it
            // would not deliver it either. Dropped, and anything reachable
            // another way is still reached that way.
            val destination = TakIdentity.destinationFor(recipient)
            if (destination == null) {
                Log.i(TAG, "Chat for a uid that is not a member of this team, not sent")
                return true
            }
            sendTo(destination, frame)
            return true
        }

        /**
         * Send a point marker as tens of bytes, if it is one.
         *
         * SPI passes a cadence gate first. It is a pointer being dragged across
         * a map -- 121 of the 853 captured events -- so most of what ATAK emits
         * is a position it has already superseded, and sending every one would
         * spend the channel on a cursor.
         */
        private suspend fun forwardMarker(cotXml: String, session: Session): Boolean {
            val frame = CotMarker.markerFromCot(cotXml, TakMembership.senderIdFor(session.node.hash))
                ?: return false
            val marker = CotMarker.decode(frame)
            if (marker != null && marker.type == CotMarker.SPI_TYPE) {
                val fix = PositionCodec.Fix(latE7 = marker.latE7, lonE7 = marker.lonE7)
                if (!session.spiGate.allows(fix, System.currentTimeMillis())) {
                    // Handled: suppressed rather than falling through to tier 2,
                    // which would spend more airtime than the codec just saved.
                    return true
                }
            }
            fanOut(frame, session)
            return true
        }

        /**
         * Send one frame to every member of the team.
         *
         * One routed unicast each, because that is the only thing that crosses
         * a hop. The airtime is real and is why position does not come this way
         * as CoT: a marker is an operator action and rare, a position report is
         * a beacon.
         */
        private suspend fun fanOut(frame: ByteArray, session: Session) {
            val now = System.currentTimeMillis()
            for (memberHash in session.registry.members(now)) {
                sendTo(memberHash, frame)
            }
        }

        /**
         * Send one frame to one node.
         *
         * Shared with the fan-out so an addressed line and a broadcast line
         * take the same path -- a second copy of this would be a second place
         * for the recall-and-request-path dance to be got wrong.
         */
        private suspend fun sendTo(memberHash: ByteArray, frame: ByteArray) {
            val identity = rnsCore.recallIdentity(memberHash)
            if (identity == null) {
                // Heard the announce, lost the identity -- possible after a
                // restart. Ask for the path; the next event will find it.
                rnsCore.requestPath(memberHash)
                return
            }
            val destination =
                rnsCore.createDestination(
                    identity,
                    Direction.OUT,
                    DestinationType.SINGLE,
                    TakIdentity.NODE_APP,
                    TakIdentity.NODE_ASPECTS,
                ).getOrNull() ?: return
            // One unreachable member must not cost the others their copy.
            rnsCore.sendPacket(destination, frame)
                .onFailure { Log.w(TAG, "Could not reach a member: ${it.message}") }
        }

        // ---- mesh -> ATAK ----

        private suspend fun pumpMeshToClients(session: Session) {
            rnsCore.observePackets().collect { packet ->
                if (!packet.destination.hash.contentEquals(session.node.hash)) return@collect
                // Byte zero says which codec produced this. One namespace
                // shared by all of them rather than three independent version
                // counters -- see TakPayload for why that distinction matters.
                when (TakPayload.kindOf(packet.data)) {
                    TakPayload.POSITION_V2 ->
                        if (renderPosition(packet.data, session)) return@collect
                    TakPayload.CHAT_V1 ->
                        if (renderChat(packet.data, session)) return@collect
                    TakPayload.MARKER_V1 ->
                        if (renderMarker(packet.data, session)) return@collect
                    else -> Unit
                }
                val xml =
                    try {
                        CotTier2.decode(packet.data)
                    } catch (_: IllegalArgumentException) {
                        // A frame we cannot read is ordinary: an older node, a
                        // newer dictionary, or simply not ours.
                        return@collect
                    }
                writeToClients(xml.toByteArray(Charsets.UTF_8))
            }
        }

        /** Render a peer's position report as CoT for the local ATAK. */
        private suspend fun renderPosition(raw: ByteArray, session: Session): Boolean {
            val fix = PositionCodec.decode(raw) ?: return false
            // Four bytes of identity is a lookup key here, not an identity.
            // Membership is the table that turns it back into a whole
            // destination hash, so a peer's track carries the same UID as
            // everything else that node sends rather than a track of its own.
            val sender = session.registry.resolveSenderId(fix.senderId, System.currentTimeMillis())
                ?: return true
            val claims = session.registry.describe(sender)
            writeToClients(
                CotPosition.buildCot(
                    fix,
                    TakIdentity.uidFor(sender),
                    claims?.callsign ?: "UNKNOWN",
                    POSITION_STALE_MS,
                    team = session.team,
                ).toByteArray(Charsets.UTF_8),
            )
            return true
        }

        /** Render a peer's marker as CoT for the local ATAK. */
        private suspend fun renderMarker(raw: ByteArray, session: Session): Boolean {
            val marker = CotMarker.decode(raw) ?: return false
            val sender =
                session.registry.resolveSenderId(marker.senderId, System.currentTimeMillis())
                    // A marker from a node this team has never heard announce.
                    // Drawing it under an invented identity puts an object on
                    // the map nobody can be asked about.
                    ?: return true
            val claims = session.registry.describe(sender)
            val now = System.currentTimeMillis()
            writeToClients(
                CotMarker.buildMarkerCot(
                    marker,
                    TakIdentity.uidFor(sender),
                    claims?.callsign ?: "UNKNOWN",
                    cotTime(now),
                    // The author's own stale, not one invented here: a spot
                    // marker is good for a year and an SPI for twenty seconds.
                    cotTime(now + marker.staleSeconds * 1000L),
                ).toByteArray(Charsets.UTF_8),
            )
            return true
        }

        /** Render a peer's chat line or receipt as CoT for the local ATAK. */
        private suspend fun renderChat(raw: ByteArray, session: Session): Boolean {
            val message = CotChat.decode(raw) ?: return false
            val sender =
                session.registry.resolveSenderId(message.senderId, System.currentTimeMillis())
                    // Chat from a node this team has never heard announce.
                    // Dropping it is the honest option: putting words on an
                    // operator's screen under an identity we cannot name is
                    // worse than not showing them at all.
                    ?: return true
            val claims = session.registry.describe(sender)
            writeToClients(
                CotChat.buildChatCot(
                    message,
                    TakIdentity.uidFor(sender),
                    claims?.callsign ?: "UNKNOWN",
                    cotTime(System.currentTimeMillis()),
                ).toByteArray(Charsets.UTF_8),
            )
            return true
        }

        /** CoT wants ISO 8601 in UTC with a Z, to millisecond precision. */
        private fun cotTime(millis: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(java.util.Date(millis))

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

        private fun publishState(session: Session, clientCount: Int) {
            _state.value = State.Listening(
                session.team,
                session.node.hexHash,
                clientCount,
                session.registry.size,
            )
        }

        /**
         * Everything one run of the endpoint owns.
         *
         * Grouped rather than passed as five parameters: every one of these has
         * the lifetime of a single serve() call, and a member list or a learned
         * ATAK UID surviving a team change would be a claim about a team this
         * node is no longer on.
         */
        private class Session(
            val node: Destination,
            val team: String,
            val pipeline: CotOutbound,
            val registry: TakMembership.Registry,
            val payload: ByteArray,
            val gate: CotPosition.PositionGate = CotPosition.PositionGate(),
            /** Shorter than position's: a pointer moves continuously. */
            val spiGate: CotPosition.PositionGate =
                CotPosition.PositionGate(intervalMs = 5_000),
        )

        /**
         * What the team name and the fleet secret derive.
         *
         * Computed once per endpoint rather than per packet: these are HMACs
         * over a secret, and recomputing them in a send path would be both
         * waste and one more place for the secret to be held.
         */
        private class Keys(val team: String, val secret: ByteArray) {
            /**
             * How this node identifies itself to the team.
             *
             * Derived rather than configured for now: a callsign setting is UI
             * that PR B does not have yet, and a node announcing nothing at all
             * would be worse than one announcing a name nobody chose.
             */
            val callsign: String = "COLUMBA"
        }
    }

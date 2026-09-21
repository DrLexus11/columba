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
import network.columba.app.data.repository.IdentityRepository
import network.columba.app.di.ApplicationScope
import network.columba.app.repository.SettingsRepository
import network.columba.app.service.PositionCodec
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.model.DeliveryMethod
import network.columba.app.rns.api.model.DeliveryStatus
import network.columba.app.rns.api.model.Destination
import network.columba.app.rns.api.model.DestinationType
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.util.Aspects
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
        private val identityRepository: IdentityRepository,
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
             *
             * **Not 8087**, which is ATAK's own default CoT input. An endpoint
             * there competes with ATAK for a port ATAK already owns, and
             * whichever binds first wins: found on hardware 2026-09-13 with
             * ATAK holding `0.0.0.0:8087` and a connection open from itself to
             * itself, while this endpoint retried every five seconds for an
             * hour and a message that had survived a partition never reached
             * the map. The retry loop reads as connection flapping from ATAK's
             * side. 18087 is clear of every ATAK default -- 8087, 8089 for
             * TLS, 6969 for multicast SA, 4242 and 8080.
             */
            const val PORT = 18087

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

            /**
             * How long a rebuilt chat line stays on screen.
             *
             * A day, because a chat message is a thing somebody said rather
             * than a position that goes out of date: the reason to expire it at
             * all is that CoT requires a stale, not that the words stop being
             * true. It was the event's own time, which made every line arrive
             * already expired.
             */
            private const val CHAT_STALE_MS = 24L * 60 * 60 * 1000

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
        private val portConflict = CotPortConflict()
        private val ticks = DeliveryTicks(rnsLxmf)

        /**
         * Tells arriving peers where our inbox is. Without it they get markers
         * and positions and no chat until the app's own hourly-to-twelve-hourly
         * announce happens to fire. See InboxAnnouncer.
         */
        private val inboxAnnouncer = InboxAnnouncer(identityRepository, rnsCore)

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

            var lastReported: String? = null
            // Logged on change rather than per attempt: this retries every five
            // seconds for as long as the fault lasts, and on hardware that was
            // an hour of identical lines burying everything else in the buffer.
            //
            // A lambda rather than a method so the two catches below can share
            // it without adding to a class that is already at its function
            // budget.
            val report: suspend (String, String) -> Unit = { reason, detail ->
                if (reason != lastReported) {
                    Log.w(TAG, "Endpoint stopped: $detail. $reason")
                    lastReported = reason
                }
                _state.value = State.Failed(reason)
                delay(RETRY_DELAY_MS)
            }
            while (currentCoroutineContext().isActive) {
                try {
                    serve(keys)
                    lastReported = null
                } catch (error: SetupFailure) {
                    // Nothing has offered the port yet, so this is not a port
                    // question. Probing one here reported "still held from a
                    // previous run" about a port nothing had tried to take, and
                    // sent an operator hunting a second copy of the app that
                    // did not exist.
                    val reason = error.message ?: "the endpoint could not start"
                    report(reason, reason)
                } catch (error: IOException) {
                    // A real bind failure, and two causes with opposite cures:
                    // a previous run that has not let go, where waiting is the
                    // fix, and another server owning the port, where waiting
                    // never becomes the fix.
                    val reason =
                        portConflict.explain(
                            portConflict.diagnose(BIND_HOST, PORT), BIND_HOST, PORT,
                        )
                    report(reason, error.message ?: "bind failed")
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
            getOrElse { throw SetupFailure("$step: ${it.message}", it) }

        /**
         * One session per run.
         *
         * The learned ATAK UID, the member list, the position cadence and the
         * proofs waiting for a tick all belong to this endpoint's lifetime
         * rather than to the process, so changing team starts a fresh one.
         */
        private suspend fun newSession(keys: Keys): Session {
            val nodeIdentity = rnsLxmf.getLxmfIdentity().orFail("no node identity yet")
            val node =
                rnsCore.createDestination(
                    nodeIdentity,
                    Direction.IN,
                    DestinationType.SINGLE,
                    TakIdentity.NODE_APP,
                    TakIdentity.NODE_ASPECTS,
                ).orFail("could not claim a node address")
            val registry = TakMembership.Registry(keys.team, keys.secret, ownHash = node.hash)
            return Session(
                node = node,
                keys = keys,
                lxmf = TakLxmf.Carrier(rnsCore, rnsLxmf, nodeIdentity),
                pipeline = CotOutbound(TakIdentity.uidFor(node.hash)),
                registry = registry,
                fragments = TakLxmfCarriage(rnsCore),
                renderer =
                    CotRenderer(
                        registry = registry,
                        team = keys.team,
                        nodeHash = node.hash,
                        positionStaleMs = POSITION_STALE_MS,
                        chatStaleMs = CHAT_STALE_MS,
                    ),
            )
        }

        /**
         * A setup step failed before the listener was ever offered the port.
         *
         * Distinct from an IOException out of bind() because the diagnosis is
         * different and the cure is too: probing the port after a
         * backend-not-ready error reports "still held from a previous run"
         * about a port nothing has tried to take, and sends an operator looking
         * for a second copy of the app that does not exist.
         */
        private class SetupFailure(message: String, cause: Throwable?) : IOException(message, cause)

        private suspend fun serve(keys: Keys) {
            // No group destination. Pivot 5: a group packet reaches only peers
            // on the same interface as the sender, so a team is a membership
            // set and traffic for it is addressed to each member.
            //
            // The UID names *this node*, never the team.
            val session = newSession(keys)

            withContext(Dispatchers.IO) {
                // Applies to every socket this thread opens from here on,
                // which is the listener and each connection accepted from it.
                TrafficStats.setThreadStatsTag(SOCKET_TAG)
                ServerSocket().use { listener ->
                    // accept() blocks in a way cancellation cannot reach, so
                    // closing the socket is the only thing that unblocks it.
                    // Without this, changing team would leave the old listener
                    // holding the endpoint port until someone happened to connect --
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
                    session.versions.attach(this)

                    val fromMesh = launch { pumpMeshToClients(session) }
                    // The same renderer the packet path uses: a chat line is
                    // the same chat line whichever carrier brought it, and a
                    // second rendering here would be a second place for the
                    // two to drift.
                    // Everything tier-2 takes the same road out: held for a
                    // client that is not attached, then written to those that are.
                    val deliver: suspend (ByteArray) -> Unit = { bytes ->
                        session.replay.hold(bytes, System.currentTimeMillis())
                        writeToClients(bytes, held = true)
                    }
                    val fromLxmf =
                        launch {
                            session.lxmf.frames().collect { inbound ->
                                Log.i(
                                    TAG,
                                    "TAK frame arrived over LXMF, ${inbound.frame.size} bytes",
                                )
                                session.fragments.deliver(
                                    inbound, session.reassembler, session.renderer,
                                    session.freshness, deliver,
                                )
                            }
                        }
                    // The sender's tick, from LXMF's proof. See DeliveryTicks.
                    val fromProofs =
                        launch {
                            ticks.collect(
                                session.proofs, session.renderer,
                                session.pipeline.ourUid, deliver,
                            )
                        }
                    val fromAnnounces = launch { learnMembers(session) }
                    val beacon =
                        launch {
                            announce(session)
                            while (isActive) {
                                delay(ANNOUNCE_INTERVAL_MS)
                                announce(session)
                            }
                        }
                    try {
                        while (isActive) {
                            val connection = listener.accept()
                            connection.tcpNoDelay = true
                            launch { serveClient(connection, session) }
                        }
                    } finally {
                        beacon.cancel()
                        fromProofs.cancel()
                        fromAnnounces.cancel()
                        fromMesh.cancel()
                        fromLxmf.cancel()
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
            // Built here rather than once per session. The callsign in it is
            // read from ATAK, so it is not known when the session starts and
            // can change while it runs.
            val payload = TakMembership.memberPayload(
                session.keys.team,
                session.keys.secret,
                session.pipeline.atakCallsign ?: Keys.FALLBACK_CALLSIGN,
            )
            rnsCore.announceDestination(session.node, payload)
                .onFailure { Log.w(TAG, "Announce failed: ${it.message}") }
        }

        private suspend fun learnMembers(session: Session) {
            rnsCore.observeAnnounces().collect { announceEvent ->
                // Every aspect Columba tracks arrives here, not just ours.
                // Membership was taken from the payload alone, so a matching
                // payload on an lxmf.delivery or lxst.telephony destination
                // would have entered the fan-out under *that* destination's
                // hash -- and sendTo rebuilds a TAK destination from whatever
                // hash it is given, addressing something the peer is not
                // listening on. The aspect is the only thing that says this
                // hash is a TAK node.
                if (announceEvent.aspect != Aspects.TAK_NODE) return@collect
                val arrival =
                    session.registry.remember(
                        announceEvent.destinationHash,
                        announceEvent.appData,
                        System.currentTimeMillis(),
                    )
                if (arrival == null) return@collect
                if (arrival == TakMembership.Arrival.NEW) {
                    val claims = session.registry.describe(announceEvent.destinationHash)
                    Log.i(
                        TAG,
                        "Team member ${claims?.callsign ?: "?"} is " +
                            TakIdentity.uidFor(announceEvent.destinationHash),
                    )
                    publishState(session, clientsLock.withLock { clients.size })
                }
                // Say who we are back -- to whoever just announced, not only to
                // a member that is new to us.
                //
                // A node that starts late hears everyone who announces after it
                // and nobody who announced before. A node that *restarts* is
                // worse: it has lost its own membership while every peer still
                // remembers it, so greeting only new members leaves it greeted
                // by nobody at all. It is not visibly broken while that lasts
                // -- its own announces go out, peers see it fine -- and every
                // frame it receives is dropped for an identity it cannot name.
                // CarriedIssues #5, found on hardware 2026-09-12.
                //
                // The rate limit is what keeps this bounded, and it was already
                // here; only the trigger was too narrow.
                if (session.registry.shouldGreet(System.currentTimeMillis())) {
                    announce(session)
                    inboxAnnouncer.announceIfDue(System.currentTimeMillis())
                }
            }
        }

        // ---- ATAK -> mesh ----

        private suspend fun serveClient(connection: Socket, session: Session) {
            TrafficStats.setThreadStatsTag(SOCKET_TAG)
            val stream = CotStream()
            val buffer = ByteArray(READ_BUFFER)
            publishState(session, clientsLock.withLock { clients.add(connection); clients.size })
            Log.i(TAG, "ATAK connected")
            // Before anything new arrives: what it missed while it was away.
            // This is what makes ATAK as reliable as Columba when both are
            // running -- Columba keeps a message because LXMF persists it, and
            // until now ATAK kept nothing at all.
            val missed = session.replay.pending(System.currentTimeMillis())
            if (missed.isNotEmpty()) {
                Log.i(TAG, "Replaying ${missed.size} held event(s) to a new client")
                withContext(Dispatchers.IO) {
                    for (payload in missed) {
                        try {
                            writeToClient(connection, payload)
                        } catch (error: IOException) {
                            Log.w(TAG, "Replay failed", error)
                            break
                        }
                    }
                }
            }
            try {
                val input = connection.getInputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    for (event in stream.feed(buffer, read)) {
                        val reply = CotEvent.pingReply(event)
                        if (reply != null) {
                            writeToClient(connection, reply.toByteArray(Charsets.UTF_8))
                        } else {
                            forwardToMesh(event, session)
                        }
                    }
                }
            } catch (error: IOException) {
                Log.w(TAG, "ATAK connection failed", error)
            } finally {
                withContext(NonCancellable) {
                    val remaining = clientsLock.withLock { clients.remove(connection); clients.size }
                    runCatching { connection.close() }
                    publishState(session, remaining)
                    Log.i(TAG, "ATAK disconnected: $remaining client(s)")
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
            val knownCallsign = session.pipeline.atakCallsign
            session.pipeline.observe(cotXml)
            if (known == null) {
                session.pipeline.atakUid?.let { Log.i(TAG, "This ATAK calls itself $it") }
            }
            val callsign = session.pipeline.atakCallsign
            if (callsign != null && callsign != knownCallsign) {
                // Announced now rather than at the next interval. Until this
                // goes out the team is drawing this node under the fallback
                // name and addressing chat to it, and the interval is thirty
                // minutes -- long enough for an operator to conclude the
                // callsign does not work and stop trying.
                Log.i(TAG, "This ATAK's operator is $callsign; re-announcing")
                announce(session)
            }
            // The typed codecs in order, each answering "was this mine?".
            // A new codec is a term in this expression rather than another
            // early return threaded through the routing.
            val typed =
                CotPosition.isPosition(cotXml) && forwardPosition(cotXml, session) ||
                    forwardChat(cotXml, session) ||
                    forwardMarker(cotXml, session)
            if (typed) return
            // Cut up rather than refused. An event over the one-packet bound
            // used to be dropped outright, which is why drawings and a
            // nine-line MEDEVAC never crossed. An ordinary event still comes
            // back as one frame and pays nothing for this.
            // An edited and re-sent drawing arrives as several versions of one
            // event, and a version costs about 7.7 s of channel for a team of
            // seven. Latest wins; see CotCoalesce. A fragment goes over LXMF,
            // a single frame takes the cheap fan-out; see TakLxmfCarriage.
            val frames = session.pipeline.frames(cotXml)
            session.versions.submit(cotXml, frames) { version ->
                if (version.size > 1) {
                    session.fragments.send(
                        version, session.registry, session.lxmf, System.currentTimeMillis(),
                    )
                } else {
                    fanOut(version[0], session)
                }
            }
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
            val message = CotChat.decode(frame)
            val recipient = message?.recipient.orEmpty()
            val kind = message?.kind
            when {
                // A receipt for a room line goes nowhere. Every member
                // answering a broadcast with a delivery and a read receipt is
                // two thirds of what group chat costs on the air -- 7.2 s of
                // the 11.1 s a ten-person room spends -- for one bit of
                // meaning each, and none of it is something an operator can
                // act on.
                recipient.isEmpty() && kind != null && kind != CotChat.KIND_MESSAGE -> Unit

                recipient.isEmpty() -> fanOut(frame, session)

                // A delivered-receipt is a transport fact and LXMF has already
                // established it: the sender holds a proof that this node
                // received the message. Sending the same fact back as a second
                // LXMF message, with its own retry budget, pays twice for one
                // answer -- and on a lossy path the receipt is the half that is
                // lost, so an operator sees no tick on a line that did arrive.
                // The sender draws its own tick from the proof instead.
                //
                // A read-receipt still goes: only this ATAK knows a human
                // opened it, and no transport can prove that.
                kind == CotChat.KIND_DELIVERED -> Unit

                else -> {
                    // Addressed to one person, so it reaches that person or
                    // nobody. The fan-out is not a fallback here --
                    // broadcasting a line meant for one person is the bug this
                    // whole path exists to stop, and it would not deliver it
                    // either.
                    val destination =
                        session.registry.memberDestination(
                            recipient, System.currentTimeMillis())
                    val hash =
                        destination?.let {
                            session.lxmf.send(it, frame, message?.text.orEmpty())
                        }
                    when {
                        destination == null ->
                            Log.i(TAG, "Chat for a uid that is not a member of this team, not sent")
                        // The bare packet is the fallback for a peer whose
                        // identity we cannot recall, not the normal route. It
                        // delivers the line and promises nothing about it.
                        hash == null -> sendTo(destination, frame)
                        // Only a message earns a tick. Acknowledging a receipt
                        // would have two nodes answering each other for ever.
                        kind == CotChat.KIND_MESSAGE && message != null -> {
                            val alreadyProved =
                                session.proofs.awaiting(
                                    hash,
                                    ChatProofs.Pending(
                                        destination, message.messageId, message.room),
                                )
                            // The proof beat the registration. Both backends
                            // install their delivery callback before dispatching
                            // the send and the status stream does not replay, so
                            // a peer one hop away is proved before this line
                            // runs -- and the tick was dropped for exactly the
                            // messages most certain to have arrived.
                            if (alreadyProved) {
                                val now = System.currentTimeMillis()
                                val tick =
                                    session.renderer.deliveryReceipt(
                                        destination, message.messageId, message.room,
                                        session.pipeline.ourUid, now,
                                    ).toByteArray(Charsets.UTF_8)
                                session.replay.hold(tick, now)
                                writeToClients(tick, held = true)
                            }
                        }
                    }
                }
            }
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
                Log.i(TAG, "mesh packet for this node: ${packet.data.size} bytes, " +
                    "kind=${TakPayload.nameOf(packet.data)}")
                // Keyed on the sender, which comes from the packet and never
                // from the frame: a peer that could choose its own key could
                // merge itself into somebody else's transfer.
                val data =
                    if (TakPayload.kindOf(packet.data) == TakPayload.FRAGMENT_V1) {
                        // Keyed on the sender, which comes from the packet and
                        // never from the frame: a peer that could choose its
                        // own key could merge itself into somebody else's
                        // transfer. The per-fragment line comes from the
                        // reassembler's own observer.
                        session.reassembler.feed(
                            packet.destination.hash, packet.data, System.currentTimeMillis(),
                        )?.also {
                            Log.i(TAG, "reassembled a ${it.size} byte event from fragments")
                        } ?: return@collect
                    } else {
                        packet.data
                    }
                val payload =
                    when (val rendered = session.renderer.render(data, System.currentTimeMillis())) {
                        is CotRenderer.Rendered.Cot -> rendered.xml
                        // Ours, and deliberately not drawn. Falling through to
                        // tier 2 here would put a frame we just declined onto
                        // the map by another route.
                        CotRenderer.Rendered.Handled -> return@collect
                        CotRenderer.Rendered.NotOurs ->
                            try {
                                CotTier2.decode(data)
                            } catch (_: IllegalArgumentException) {
                                // A frame we cannot read is ordinary: an older
                                // node, a newer dictionary, or simply not ours.
                                return@collect
                            }.takeIf { session.freshness.admit(it) } ?: return@collect
                    }
                val bytes = payload.toByteArray(Charsets.UTF_8)
                // Position is latest-wins and is not held; everything else is.
                val keep = TakPayload.kindOf(data) != TakPayload.POSITION_V2
                if (keep) {
                    session.replay.hold(bytes, System.currentTimeMillis())
                }
                writeToClients(bytes, held = keep)
            }
        }

        /**
         * Hand a newly attached client what it missed.
         *
         * Oldest first, so a conversation arrives in the order it happened.
         * Every frame carries its own uid and message id, so a client that
         * already has one recognises it again -- replay is safe to repeat and
         * cheap to ignore.
         */
        private suspend fun writeToClients(payload: ByteArray, held: Boolean = false) {
            // Set on whichever IO thread does the writing, for the same reason.
            TrafficStats.setThreadStatsTag(SOCKET_TAG)
            // Snapshot under the lock, write outside it. A client whose
            // receive window has filled blocks on write for as long as it
            // takes, and holding the lock across that would stall the accept
            // loop and every other client behind the slowest one.
            val targets = clientsLock.withLock { clients.toList() }
            if (targets.isEmpty()) {
                // "Lost" and "held" are not the same outcome and must not read
                // the same. This logged "lost" for a chat line it had just put
                // in the replay buffer -- on hardware 2026-09-13, in the one
                // situation where knowing which it was is the whole question.
                // The firmware bridge already made this distinction; this side
                // had the buffer and kept the old wording.
                if (held) {
                    Log.i(TAG, "no ATAK attached; ${payload.size} bytes held for replay")
                } else {
                    Log.w(TAG, "ATAK delivery lost: ${payload.size} bytes, no connected clients")
                }
            }
            val dead = mutableListOf<Socket>()
            withContext(Dispatchers.IO) {
                for (client in targets) {
                    try {
                        writeToClient(client, payload)
                    } catch (error: IOException) {
                        Log.w(TAG, "ATAK write failed", error)
                        dead.add(client)
                    }
                }
            }
            if (dead.isEmpty()) return
            clientsLock.withLock { clients.removeAll(dead) }
            for (client in dead) runCatching { client.close() }
        }

        // Heartbeat replies and mesh callbacks share a stream: each complete
        // XML event must be written before another writer can start one.
        private suspend fun closeAllClients() {
            val closing = clientsLock.withLock { clients.toList().also { clients.clear() } }
            for (client in closing) runCatching { client.close() }
        }

        private fun publishState(session: Session, clientCount: Int) {
            _state.value = State.Listening(
                session.team,
                session.node.hexHash,
                clientCount,
                session.registry.size(System.currentTimeMillis()),
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
            /**
             * How chat reaches one named peer: over LXMF, signed with the same
             * identity the node destination is built from, which is why a peer
             * who can address our node can also address our inbox.
             */
            val lxmf: TakLxmf.Carrier,
            val pipeline: CotOutbound,
            val registry: TakMembership.Registry,
            val renderer: CotRenderer,
            /** How an event too big for one packet travels, and arrives. */
            val fragments: TakLxmfCarriage,
            /** Kept so the announce can be rebuilt around a new callsign. */
            val keys: Keys,
        ) {
            /**
             * The team this run is on.
             *
             * Read from [keys] rather than passed alongside them: the two were
             * always the same string, and a Session whose team disagreed with
             * the secret its keys were derived from would address a team it
             * could not decrypt.
             */
            val team: String get() = keys.team

            /**
             * Sent messages whose delivery proof will draw their tick.
             *
             * Properties rather than constructor parameters, here and below:
             * nothing constructs a Session with any of them, and each is state
             * this run owns rather than something handed to it.
             */
            val proofs = ChatProofs()

            /** Fragments of events too big for one packet, until they are whole. */
            /** Latest wins across repeated versions of one event. */
            val versions = CotVersions()

            /** Never draw a version older than one already drawn. */
            val freshness = Freshness()
            val reassembler =
                CotReassembler(
                    observer = { index, count, held, elapsedMs ->
                        Log.i(
                            TAG,
                            "fragment ${index + 1} of $count, $held held, " +
                                "${elapsedMs}ms since the first",
                        )
                    },
                )

            /**
             * Held events belong to this run, not to the process.
             *
             * collectLatest builds a new session when the team or the fleet
             * secret changes, and a buffer that outlived that would hand the
             * next ATAK client events received under the old team -- replayed
             * into the new one, attributed to members of a team they were never
             * sent to.
             */
            val replay = CotReplay()

            val gate = CotPosition.PositionGate()

            /** Shorter than position's: a pointer moves continuously. */
            val spiGate = CotPosition.PositionGate(intervalMs = 5_000)
        }

        /**
         * What the team name and the fleet secret derive.
         *
         * Computed once per endpoint rather than per packet: these are HMACs
         * over a secret, and recomputing them in a send path would be both
         * waste and one more place for the secret to be held.
         */
        private class Keys(val team: String, val secret: ByteArray) {
            companion object {
                /**
                 * What this node calls itself until ATAK says otherwise.
                 *
                 * It is a placeholder, and it used to be the answer. Every
                 * handset on the mesh announced "COLUMBA" -- a constant in
                 * this file, not a name -- so the command post's map showed the
                 * same word for whoever was carrying it, and chat was addressed
                 * to a label nobody had chosen. Observed on hardware
                 * 2026-09-13.
                 *
                 * The real callsign is read from ATAK's own self-report, which
                 * already carries it; see CotEvent.learnAtakCallsign. Nothing
                 * is configured here for the reason the UID is not: the
                 * operator has already typed it once, and a second place to
                 * type it is a second place for it to be wrong. This is only
                 * what goes out before ATAK has connected at all.
                 */
                const val FALLBACK_CALLSIGN = "COLUMBA"
            }
        }
    }

/**
 * Write one whole event to one client, serialised on that socket.
 *
 * The lock is the point. A heartbeat is answered from the reader thread while
 * the mesh writes from another, and two threads interleaving halves of two
 * events on one socket produce a stream ATAK cannot parse.
 *
 * Outside the class because it holds no state of it, and because the class was
 * over detekt's method budget -- which is a fair thing to be told about an
 * endpoint that already does discovery, framing, rendering and four codecs.
 */
private fun writeToClient(client: Socket, payload: ByteArray) {
    synchronized(client) {
        client.getOutputStream().apply {
            write(payload)
            flush()
        }
    }
}

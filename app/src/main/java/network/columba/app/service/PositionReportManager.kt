package network.columba.app.service

import android.content.Context
import android.location.Location
import android.os.CancellationSignal
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import network.columba.app.di.ApplicationScope
import network.columba.app.repository.SettingsRepository
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.model.Destination
import network.columba.app.rns.api.model.DestinationType
import network.columba.app.rns.api.model.Direction
import network.columba.app.util.LocationPermissionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Report this device's position to a gateway that speaks CoT.
 *
 * A responder already carries a GNSS receiver with a battery and a screen. The
 * nodes do not — wiring a module to every board was the original plan and is
 * now a feature of its own, because the phone is here today and the clock
 * argument that justified the module has already been answered by signed time
 * propagation.
 *
 * ATAK never speaks Reticulum. This sends twenty bytes to a fixed gateway, and
 * the gateway expands them into CoT XML and serves ordinary TAK clients over
 * the local network. No plugin, no fork, no client work.
 *
 * Three things shape the design, and all three are about not being believed
 * more than we deserve:
 *
 *  - **Off by default, and inert without a gateway.** Position is the most
 *    sensitive thing this app can emit. It goes nowhere until someone turns it
 *    on *and* names where it should go.
 *  - **Never report a stale fix.** Android will hand back a last known location
 *    from hours ago without comment. A marker on a map is a claim about now,
 *    and repeating an old one is how a search team ends up somewhere the
 *    person used to be.
 *  - **Unicast to one destination.** Not a broadcast, not an announce.
 *    Reticulum learns paths from announces and they are deliberately
 *    expensive; announcing every minute to carry twenty bytes would spend more
 *    on routing than on the payload. See TAKCapability.md §3.
 */
@Singleton
class PositionReportManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsRepository: SettingsRepository,
        private val rnsCore: RnsCore,
        private val rnsLxmf: network.columba.app.rns.api.RnsLxmf,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        companion object {
            private const val TAG = "PositionReportManager"

            /** rnstransport.position.report, matching PositionReport.h. */
            private const val APP_NAME = "rnstransport"
            private val ASPECTS = listOf("position", "report")

            /**
             * How old a fix may be and still be worth sending.
             *
             * Android's last known location can be hours stale, and it arrives
             * looking exactly like a fresh one. Two minutes is a couple of
             * missed reports at the cadence below, and matches
             * NODE_POSITION_STALE_MS in the firmware so a report that survives
             * this check is not thrown away at the other end.
             */
            internal const val MAX_FIX_AGE_MS = 120_000L
        }

        // The same choice LocationSharingManager makes, and for the same reason.
        // LocationCompat exists for devices *without* Play Services -- custom
        // ROMs and F-Droid builds -- and using it as the primary path on a phone
        // that has GMS asks the wrong provider: a raw LocationManager one-shot
        // on GPS returns null indoors while the fused provider has a current fix
        // the whole time. Measured on the A54, where every request failed and
        // `dumpsys location` showed gps, network and fused all populated.
        private val useGms = network.columba.app.util.LocationCompat.isPlayServicesAvailable(context)
        private val fusedLocationClient: FusedLocationProviderClient? =
            if (useGms) LocationServices.getFusedLocationProviderClient(context) else null

        private var reportJob: Job? = null

        // Kept rather than rebuilt, and rebuilt only when the gateway changes.
        // The time authority next door learned this the hard way in the other
        // direction: Reticulum refuses to register a destination it already
        // holds. Here it is simply waste to re-derive a destination from an
        // identity that has not changed.
        private var cachedDestination: Destination? = null
        private var cachedForGatewayHash: String? = null

        /** Start observing settings and reporting position while enabled. */
        fun start() {
            Log.d(TAG, "Starting PositionReportManager")
            reportJob =
                scope.launch {
                    combine(
                        settingsRepository.positionReportEnabledFlow,
                        settingsRepository.positionReportIntervalMinutesFlow,
                        settingsRepository.positionGatewayHashFlow,
                    ) { enabled, intervalMinutes, gatewayHash ->
                        Triple(enabled, intervalMinutes, gatewayHash)
                    }
                        // collectLatest, not collect: the loop below never
                        // returns, so a plain collect would take the first
                        // settings value and never see another. Turning the
                        // feature off would leave it reporting.
                        .collectLatest { (enabled, intervalMinutes, gatewayHash) ->
                            when {
                                !enabled ->
                                    Log.d(TAG, "Position reporting disabled")
                                gatewayHash.isNullOrBlank() ->
                                    Log.i(
                                        TAG,
                                        "Position reporting is on but no gateway is set; " +
                                            "nothing will be sent until one is",
                                    )
                                else -> runReportLoop(intervalMinutes, gatewayHash)
                            }
                        }
                }
        }

        /** Stop reporting. Call when the app is shutting down. */
        fun stop() {
            Log.d(TAG, "Stopping PositionReportManager")
            reportJob?.cancel()
            reportJob = null
        }

        /**
         * Send one report now, regardless of the schedule.
         *
         * The same reasoning as asserting time on arrival: walking into a
         * situation and wanting the map to show where you are should not mean
         * waiting out an interval.
         */
        suspend fun reportNow(): Boolean {
            val gatewayHash = settingsRepository.currentPositionGatewayHash()
            if (gatewayHash.isNullOrBlank()) {
                Log.w(TAG, "No gateway set; not reporting position")
                return false
            }
            return emitReport(gatewayHash)
        }

        private suspend fun runReportLoop(
            intervalMinutes: Int,
            gatewayHash: String,
        ) {
            Log.d(TAG, "Position reporting every ${intervalMinutes}min to ${gatewayHash.take(16)}")
            val intervalMillis = intervalMinutes.toLong() * 60L * 1000L
            while (true) {
                emitReport(gatewayHash)
                delay(intervalMillis)
            }
        }

        private suspend fun emitReport(gatewayHash: String): Boolean =
            try {
                when {
                    // Fine, not coarse. hasPermission() accepts either, but
                    // approximate location lands kilometres from the truth
                    // (issue #855) -- and a marker kilometres out on a tactical
                    // map is worse than an absent one, because somebody drives
                    // to it. Refuse rather than report a position we know is
                    // wrong at that scale.
                    !LocationPermissionManager.hasFineLocationPermission(context) -> {
                        // Say it plainly. A feature that is on, configured, and
                        // silent because of a permission is the hardest kind of
                        // fault for someone to find from the outside.
                        Log.w(TAG, "Precise location not granted; not reporting position")
                        false
                    }
                    else -> sendFix(gatewayHash)
                }
            } catch (e: CancellationException) {
                // collectLatest cancels this loop on a settings change and that
                // arrives as a CancellationException from whatever call is in
                // flight. Swallowing it would log a phantom failure.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error while reporting position", e)
                false
            }

        private suspend fun sendFix(gatewayHash: String): Boolean {
            val location = freshLocation() ?: return false
            val destination = readyDestination(gatewayHash) ?: return false
            return deliver(destination,
                PositionCodec.encode(PositionCodec.fromLocation(location, senderId())))
        }

        /**
         * Put one report on the wire.
         *
         * A packet, not a link. A position report needs no session and no
         * reply, and a packet to a SINGLE destination is already encrypted to
         * the gateway's identity. On the constrained nodes the same choice
         * saves about eight kilobytes of transient heap per report; here it
         * simply saves a round trip that buys nothing.
         */
        private suspend fun deliver(
            destination: Destination,
            payload: ByteArray,
        ): Boolean =
            rnsCore.sendPacket(destination, payload).fold(
                onSuccess = {
                    Log.d(TAG, "Reported position, ${payload.size} bytes")
                    settingsRepository.saveLastPositionReportTime(System.currentTimeMillis())
                    true
                },
                onFailure = { error ->
                    Log.e(TAG, "Position report failed: ${error.message}")
                    false
                },
            )

        /**
         * A location we are willing to state is where we are now, or null.
         *
         * Android hands back a last known location from hours ago with no
         * indication of its age, and it arrives looking exactly like a fresh
         * one. A marker on a map is a claim about now.
         */
        private suspend fun freshLocation(): Location? {
            val location = currentLocation()
            if (location == null) {
                Log.d(TAG, "No location available this cycle")
                return null
            }
            val ageMs = System.currentTimeMillis() - location.time
            if (ageMs > MAX_FIX_AGE_MS) {
                Log.d(TAG, "Fix is ${ageMs}ms old; not reporting it as current")
                return null
            }
            return location
        }

        /**
         * The gateway destination, if we can reach it and know who it is.
         *
         * Both failures here are ordinary and self-resolving: a path arrives
         * after a request, and an identity arrives with the gateway's next
         * announce.
         */
        private suspend fun readyDestination(gatewayHash: String): Destination? {
            val gatewayBytes = decodeHash(gatewayHash)
            if (gatewayBytes == null) {
                Log.w(TAG, "Gateway hash is not valid hex; not reporting position")
                return null
            }
            if (!rnsCore.hasPath(gatewayBytes)) {
                rnsCore.requestPath(gatewayBytes)
                Log.d(TAG, "No path to the gateway yet; requested one")
                return null
            }
            return destinationFor(gatewayHash, gatewayBytes)
        }

        /**
         * Four bytes of this device's LXMF identity, so the gateway can tell
         * one reporter from another.
         *
         * The LXMF identity rather than any other: it is the one this device is
         * already known by across the mesh, it is what the time authority signs
         * with, and it is what an operator provisions onto a node. A second
         * identifier here would be a second thing to keep in step.
         *
         * Zero when there is no identity yet, which the gateway renders as a
         * track of its own rather than pretending to know who it is.
         */
        private suspend fun senderId(): Int {
            val hash = rnsLxmf.getLxmfIdentity().getOrNull()?.hash ?: return 0
            if (hash.size < 4) return 0
            return ((hash[0].toInt() and 0xFF) shl 24) or
                ((hash[1].toInt() and 0xFF) shl 16) or
                ((hash[2].toInt() and 0xFF) shl 8) or
                (hash[3].toInt() and 0xFF)
        }

        private suspend fun destinationFor(
            gatewayHash: String,
            gatewayBytes: ByteArray,
        ): Destination? {
            val cached = cachedDestination
            if (cached != null && cachedForGatewayHash == gatewayHash) return cached
            val created = createGatewayDestination(gatewayBytes)
            if (created != null) {
                cachedDestination = created
                cachedForGatewayHash = gatewayHash
            }
            return created
        }

        private suspend fun createGatewayDestination(gatewayBytes: ByteArray): Destination? {
            val identity = rnsCore.recallIdentity(gatewayBytes)
            if (identity == null) {
                // A path exists but the identity has not been learned yet,
                // which happens between a path reply and the gateway's next
                // announce. Ordinary, and it resolves itself.
                Log.d(TAG, "No identity for the gateway yet")
                return null
            }
            return rnsCore
                .createDestination(identity, Direction.OUT, DestinationType.SINGLE, APP_NAME, ASPECTS)
                .getOrElse { error ->
                    Log.e(TAG, "Could not create the gateway destination", error)
                    null
                }
        }

        private fun decodeHash(hash: String): ByteArray? {
            val cleaned = hash.trim().removePrefix("<").removeSuffix(">")
            if (cleaned.length % 2 != 0 || cleaned.isEmpty()) return null
            return try {
                ByteArray(cleaned.length / 2) { index ->
                    cleaned.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                }
            } catch (e: NumberFormatException) {
                Log.w(TAG, "Gateway hash is not hex", e)
                null
            }
        }

        /**
         * One location, or null. Wraps the callback API so the loop above reads
         * as a sequence rather than a nest, and cancels the request if the
         * coroutine is cancelled -- otherwise a settings change during a fix
         * would leave the provider holding a callback into a dead scope.
         */
        /**
         * The best fix available, by whichever route this device actually has.
         *
         * Ordered most-current first, and every one of them is allowed to fail:
         * freshLocation() applies the age check afterwards, so a fallback never
         * smuggles in a stale position, it only avoids reporting nothing when
         * the device plainly knows where it is.
         */
        private suspend fun currentLocation(): Location? =
            fusedCurrentLocation()
                ?: fusedLastLocation()
                ?: requestCurrentLocation()
                ?: lastKnownLocation()

        private suspend fun fusedCurrentLocation(): Location? {
            val client = fusedLocationClient ?: return null
            return suspendCancellableCoroutine { continuation ->
                val tokens = CancellationTokenSource()
                continuation.invokeOnCancellation { tokens.cancel() }
                try {
                    client
                        .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokens.token)
                        .addOnSuccessListener { location ->
                            if (continuation.isActive) continuation.resume(location)
                        }.addOnFailureListener { error ->
                            Log.d(TAG, "Fused current location failed", error)
                            if (continuation.isActive) continuation.resume(null)
                        }
                } catch (e: SecurityException) {
                    Log.w(TAG, "Location permission revoked mid-request", e)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

        private suspend fun fusedLastLocation(): Location? {
            val client = fusedLocationClient ?: return null
            return suspendCancellableCoroutine { continuation ->
                try {
                    client.lastLocation
                        .addOnSuccessListener { location ->
                            if (continuation.isActive) continuation.resume(location)
                        }.addOnFailureListener {
                            if (continuation.isActive) continuation.resume(null)
                        }
                } catch (e: SecurityException) {
                    Log.w(TAG, "Location permission revoked before last fused read", e)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

        private suspend fun requestCurrentLocation(): Location? =
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                try {
                    network.columba.app.util.LocationCompat.getCurrentLocation(
                        context,
                        signal,
                    ) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                } catch (e: SecurityException) {
                    // The permission was revoked between the check and the call.
                    Log.w(TAG, "Location permission revoked mid-request", e)
                    if (continuation.isActive) continuation.resume(null)
                }
            }

        /**
         * What the system already has, when a fresh request produced nothing.
         *
         * On API 30 and above LocationCompat hands back whatever
         * LocationManager.getCurrentLocation() returned, null included, and a
         * one-shot GPS request from cold indoors is exactly the case that
         * returns null -- while the same device has a fix from seconds ago
         * because something else is holding continuous updates. Measured on the
         * A54: six locations delivered to this app in the half-minute during
         * which every one-shot request failed.
         *
         * Asking for the last known fix is not a lowering of standards. The age
         * check in freshLocation() is what decides whether a position may be
         * reported as current, and it applies to this exactly as it applies to
         * a fresh one; a fix from four seconds ago is a fix, whatever produced
         * it.
         */
        private fun lastKnownLocation(): Location? =
            try {
                network.columba.app.util.LocationCompat
                    .getLastKnownLocation(context)
                    ?.also { Log.d(TAG, "Using last known fix; no current one available") }
            } catch (e: SecurityException) {
                Log.w(TAG, "Location permission revoked before last-known read", e)
                null
            }
    }

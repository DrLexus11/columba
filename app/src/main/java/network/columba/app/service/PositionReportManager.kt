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
import network.columba.app.service.tak.CotEndpointManager
import network.columba.app.util.LocationPermissionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Keep this handset on the team's map while ATAK is closed.
 *
 * While ATAK is connected to the endpoint it reports its own position, at its
 * own cadence, and this does nothing: a second report of the same position is
 * airtime spent on nothing. When ATAK is closed -- the phone in a pocket, the
 * screen locked, ATAK killed for memory -- this takes over at a lighter cadence
 * the operator picks, so teammates still see where this person is.
 *
 * It replaces a first design that sent twenty bytes to a fixed gateway. That
 * reported alongside ATAK rather than instead of it, so a teammate saw this
 * person twice, and the gateway drew the second track under a uid built from
 * four bytes of identity -- a different EUD from the one ATAK itself was on.
 * Now the report goes out through the endpoint as the same frame ATAK's own
 * reports become, from the same node, and a receiver draws it on the same
 * track under the same callsign. One EUD, whether or not ATAK is open.
 *
 * So it needs the endpoint running with a team: without one there is nobody
 * to report to, and the switch says so rather than looking on and doing
 * nothing. The rules that shaped the first design still stand:
 *
 *  - **Off by default.** Position is the most sensitive thing this app emits.
 *  - **Never report a stale fix.** Android hands back a last known location
 *    from hours ago without comment, and a marker on a map is a claim about now.
 *  - **Say when the next one is due.** The report states its interval, so a
 *    receiver keeps the track current until then instead of greying it out
 *    after the one-minute default.
 */
@Singleton
class PositionReportManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsRepository: SettingsRepository,
        private val cotEndpointManager: CotEndpointManager,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        companion object {
            private const val TAG = "PositionReportManager"

            /**
             * How old a fix may be and still be worth sending.
             *
             * Android's last known location can be hours stale, and it arrives
             * looking exactly like a fresh one. Two minutes matches
             * NODE_POSITION_STALE_MS in the firmware.
             */
            internal const val MAX_FIX_AGE_MS = 120_000L

            /**
             * How soon to try again after a cycle that sent nothing.
             *
             * No fix yet, or no teammate reachable yet: both usually clear in
             * seconds, and waiting out a five-minute interval would leave this
             * person off the map for five minutes because of one bad moment.
             */
            internal const val RETRY_MS = 30_000L

            /** What reporting should be doing, from the switch and the endpoint. */
            internal fun statusFor(
                enabled: Boolean,
                intervalMinutes: Int,
                endpoint: CotEndpointManager.State,
            ): Status =
                when {
                    !enabled -> Status.Off
                    endpoint !is CotEndpointManager.State.Listening -> Status.NeedsEndpoint
                    endpoint.clients > 0 -> Status.AtakReporting
                    else -> Status.Reporting(intervalMinutes)
                }
        }

        /** What reporting is doing, for the settings card. */
        sealed interface Status {
            /** Switched off. */
            data object Off : Status

            /** On, but the endpoint is not running with a team, so nobody to tell. */
            data object NeedsEndpoint : Status

            /** On, and ATAK is connected and reporting for itself. */
            data object AtakReporting : Status

            /** On, ATAK is closed, and this is reporting every [intervalMinutes]. */
            data class Reporting(val intervalMinutes: Int) : Status
        }

        private val _status = MutableStateFlow<Status>(Status.Off)
        val status: StateFlow<Status> = _status.asStateFlow()

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

        /** Start following the switch and the endpoint, and report while due. */
        fun start() {
            Log.d(TAG, "Starting PositionReportManager")
            reportJob?.cancel()
            reportJob =
                scope.launch {
                    combine(
                        settingsRepository.positionReportEnabledFlow,
                        settingsRepository.positionReportIntervalMinutesFlow,
                        cotEndpointManager.state,
                        ::statusFor,
                    )
                        // The endpoint's state also carries a member count, and
                        // a teammate joining must not restart the loop -- that
                        // would send a report every time anyone announced.
                        .distinctUntilChanged()
                        // collectLatest: the loop below never returns, and ATAK
                        // connecting has to stop it.
                        .collectLatest { status ->
                            _status.value = status
                            when (status) {
                                is Status.Reporting -> runReportLoop(status.intervalMinutes)
                                Status.AtakReporting -> Log.d(TAG, "ATAK is connected and reporting; standing by")
                                Status.NeedsEndpoint ->
                                    Log.i(TAG, "Position reporting is on but the TAK endpoint is not running")
                                Status.Off -> Log.d(TAG, "Position reporting off")
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
         * Send one report now, if reporting is what this handset should be doing.
         *
         * Arriving somewhere and wanting the map to show it should not mean
         * waiting out an interval. Declined while ATAK is connected, which is
         * already reporting.
         */
        suspend fun reportNow(): Boolean {
            val status = _status.value as? Status.Reporting ?: run {
                Log.i(TAG, "Not reporting now: ${_status.value}")
                return false
            }
            return emitReport(status.intervalMinutes)
        }

        private suspend fun runReportLoop(intervalMinutes: Int) {
            Log.i(TAG, "ATAK is closed; reporting this handset's position every ${intervalMinutes}min")
            val intervalMillis = intervalMinutes.toLong() * 60L * 1000L
            while (true) {
                val sent = emitReport(intervalMinutes)
                delay(if (sent) intervalMillis else minOf(intervalMillis, RETRY_MS))
            }
        }

        private suspend fun emitReport(intervalMinutes: Int): Boolean =
            try {
                when {
                    // Fine, not coarse. Approximate location lands kilometres
                    // from the truth (issue #855), and a marker kilometres out
                    // on a tactical map is worse than an absent one, because
                    // somebody drives to it.
                    !LocationPermissionManager.hasFineLocationPermission(context) -> {
                        Log.w(TAG, "Precise location not granted; not reporting position")
                        false
                    }
                    else -> sendFix(intervalMinutes)
                }
            } catch (e: CancellationException) {
                // collectLatest cancels this loop when ATAK connects, and that
                // arrives as a CancellationException from whatever call is in
                // flight. Swallowing it would log a phantom failure.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error while reporting position", e)
                false
            }

        private suspend fun sendFix(intervalMinutes: Int): Boolean {
            val location = freshLocation() ?: return false
            val fix = PositionCodec.fromLocation(location).copy(intervalMin = intervalMinutes)
            val sent = cotEndpointManager.reportOwnPosition(fix)
            if (sent) {
                Log.d(TAG, "Reported this handset's position to the team")
                settingsRepository.saveLastPositionReportTime(System.currentTimeMillis())
            } else {
                // No teammate took it: none known yet, none reachable, or ATAK
                // connected in the moment between the check and the send.
                Log.d(TAG, "No teammate took the position report")
            }
            return sent
        }

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

        /**
         * One location, or null. Wraps the callback API so the caller reads as
         * a sequence rather than a nest, and cancels the request if the
         * coroutine is cancelled -- otherwise a settings change during a fix
         * would leave the provider holding a callback into a dead scope.
         */
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

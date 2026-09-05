package network.columba.app.service

import android.util.Log
import network.columba.app.di.ApplicationScope
import network.columba.app.repository.SettingsRepository
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.model.Destination
import network.columba.app.rns.api.model.DestinationType
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.model.Identity
import org.msgpack.core.MessagePack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assert this device's clock onto the mesh, as a signed statement anyone may
 * relay and nobody can forge.
 *
 * Nodes on a disaster mesh have no clock of their own worth trusting. They
 * reboot to an uptime counter, and a restored clock is only a lower bound that
 * drifts further behind on every restart. A phone, by contrast, is
 * NTP-disciplined and walks around, which makes it the one thing a deployment
 * can rely on to bring real time to nodes nothing else can reach.
 *
 * The mechanism is deliberately not "the phone sets the clock on the node it is
 * connected to". That only ever fixes the one node at the other end of a link.
 * Instead this announces a destination whose app data *is* the assertion, so
 * Reticulum's ordinary announce machinery carries it — flooding, hop counting
 * and deduplication included — and every relay along the way is untrusted by
 * construction. A relay can drop the assertion or sit on it; it cannot change
 * what it says, because the signature is checked against a key provisioned onto
 * each node out of band.
 *
 * The wire format is fixed by the firmware's TimeBeacon.h. Keep the two in step:
 * a disagreement about a single byte makes every signature fail to verify, and
 * that failure is indistinguishable from an attack.
 */
@Singleton
class TimeAuthorityManager
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val rnsCore: RnsCore,
        private val rnsLxmf: RnsLxmf,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        companion object {
            private const val TAG = "TimeAuthorityManager"

            /** Destination announced by an authority: rnstransport.time.assertion */
            private const val APP_NAME = "rnstransport"
            private val ASPECTS = listOf("time", "assertion")

            /**
             * Domain separation. The nonce-challenged reply signs a different
             * layout, and without a distinct prefix a signature captured from
             * one exchange could be presented as the other.
             */
            private val DOMAIN = "urtn-time-beacon-v1".toByteArray(Charsets.US_ASCII)

            /** Assertion version on the wire. */
            private const val VERSION = 1

            /**
             * How long a receiver that has a clock of its own may treat an
             * assertion as fresh. Comfortably longer than the interval, so an
             * assertion does not expire before its successor arrives.
             */
            private const val VALID_FOR_SECONDS = 7200

            /**
             * Hops from a real reference. A phone with system time from the
             * network is one hop; a node that learns from us records two.
             */
            private const val STRATUM = 1

            /** RNS::Utilities::OS::WallTimeSource::NTP */
            private const val SOURCE_NTP = 2

            private const val SIGNATURE_LENGTH = 64

            /**
             * The exact bytes an authority signs, matching time_beacon_signed_bytes()
             * in the firmware: domain, then big-endian unix_ms, valid_for, stratum,
             * source. Fixed width and no msgpack, because a canonicalisation
             * disagreement between packers would produce signatures that can never
             * verify.
             */
            internal fun signedBytes(
                unixMillis: Long,
                validForSeconds: Int,
                stratum: Int,
                source: Int,
            ): ByteArray =
                ByteBuffer
                    .allocate(DOMAIN.size + Long.SIZE_BYTES + Int.SIZE_BYTES + 2)
                    .order(ByteOrder.BIG_ENDIAN)
                    .put(DOMAIN)
                    .putLong(unixMillis)
                    .putInt(validForSeconds)
                    .put(stratum.toByte())
                    .put(source.toByte())
                    .array()

            /**
             * The assertion as it travels: a msgpack map read by field name, so
             * adding a field later cannot break a receiver that predates it.
             *
             * There is no authority field. The announce that carries this
             * cryptographically binds the identity that signed it, so a field
             * naming the authority could only ever agree with something already
             * proven — it goes in the day an assertion travels over something that
             * is not an announce.
             */
            internal fun assertionAppData(
                unixMillis: Long,
                validForSeconds: Int,
                stratum: Int,
                source: Int,
                signature: ByteArray,
            ): ByteArray =
                MessagePack.newDefaultBufferPacker().use { packer ->
                    packer.packMapHeader(6)
                    packer.packString("v").packInt(VERSION)
                    packer.packString("t").packLong(unixMillis)
                    packer.packString("f").packInt(validForSeconds)
                    packer.packString("s").packInt(stratum)
                    packer.packString("o").packInt(source)
                    packer.packString("g").packBinaryHeader(signature.size).writePayload(signature)
                    packer.toByteArray()
                }
        }

        private var authorityJob: Job? = null

        // Created once and kept. Reticulum refuses to register a destination it
        // already holds -- Python RNS raises KeyError, "Attempt to register an
        // already registered destination" -- so building it per assertion means
        // the first one is announced and every one after it fails. Measured on
        // a Galaxy A54: assertion 1 sent, assertion 2 onwards `not_sent`.
        private var cachedDestination: Destination? = null
        private var cachedForIdentityHash: String? = null

        /** Start observing settings and asserting time while enabled. */
        fun start() {
            Log.d(TAG, "Starting TimeAuthorityManager")
            authorityJob =
                scope.launch {
                    combine(
                        settingsRepository.timeAuthorityEnabledFlow,
                        settingsRepository.timeAuthorityIntervalMinutesFlow,
                    ) { enabled, intervalMinutes -> enabled to intervalMinutes }
                        .collect { (enabled, intervalMinutes) ->
                            if (enabled) {
                                Log.d(TAG, "Time authority enabled, asserting every ${intervalMinutes}min")
                                runAuthorityLoop(intervalMinutes)
                            } else {
                                Log.d(TAG, "Time authority disabled")
                            }
                        }
                }
        }

        /** Stop asserting. Call when the app is shutting down. */
        fun stop() {
            Log.d(TAG, "Stopping TimeAuthorityManager")
            authorityJob?.cancel()
            authorityJob = null
        }

        /**
         * The destination this authority announces under, created once.
         *
         * Rebuilt only if the active identity changes, which happens when the
         * user switches identities -- the destination is derived from the
         * identity, so a stale one would announce under the wrong key.
         */
        private suspend fun timeDestinationFor(identity: Identity): Destination? {
            val identityHash = identity.hash.joinToString("") { "%02x".format(it) }
            cachedDestination?.let { existing ->
                if (cachedForIdentityHash == identityHash) return existing
            }
            val created =
                rnsCore
                    .createDestination(identity, Direction.IN, DestinationType.SINGLE, APP_NAME, ASPECTS)
                    .getOrElse { error ->
                        Log.e(TAG, "Could not create the time destination", error)
                        return null
                    }
            cachedDestination = created
            cachedForIdentityHash = identityHash
            return created
        }

        /**
         * Emit a single assertion now, regardless of the periodic schedule.
         *
         * Worth having as its own entry point: walking into a building and
         * wanting every node in earshot to have the time immediately is the
         * whole use case, and waiting out an interval defeats it.
         *
         * @return true if an assertion was announced.
         */
        suspend fun assertNow(): Boolean = emitAssertion()

        private suspend fun runAuthorityLoop(intervalMinutes: Int) {
            val intervalMillis = intervalMinutes.toLong() * 60L * 1000L
            while (true) {
                emitAssertion()
                delay(intervalMillis)
            }
        }

        private suspend fun emitAssertion(): Boolean {
            try {
                val unixMillis = System.currentTimeMillis()
                val signed =
                    signedBytes(
                        unixMillis = unixMillis,
                        validForSeconds = VALID_FOR_SECONDS,
                        stratum = STRATUM,
                        source = SOURCE_NTP,
                    )

                val signature = rnsCore.signWithIdentity(signed)
                if (signature == null || signature.size != SIGNATURE_LENGTH) {
                    // No identity, or a backend that cannot sign. Say nothing
                    // rather than announce an assertion nobody can verify.
                    Log.w(TAG, "No signature available; not asserting time")
                    return false
                }

                // The same identity the backend signed with: signWithIdentity
                // uses the delivery identity, so the announce and the assertion
                // inside it are made by one key. A receiver that trusts the
                // announced identity is trusting exactly what signed the bytes.
                val identity =
                    rnsLxmf.getLxmfIdentity().getOrElse { error ->
                        Log.w(TAG, "No LXMF identity yet; not asserting time", error)
                        return false
                    }
                val destination =
                    timeDestinationFor(identity) ?: return false

                val appData =
                    assertionAppData(
                        unixMillis = unixMillis,
                        validForSeconds = VALID_FOR_SECONDS,
                        stratum = STRATUM,
                        source = SOURCE_NTP,
                        signature = signature,
                    )

                val result = rnsCore.announceDestination(destination, appData)
                return if (result.isSuccess) {
                    Log.d(TAG, "Asserted UTC $unixMillis at stratum $STRATUM")
                    settingsRepository.saveLastTimeAssertionTime(unixMillis)
                    true
                } else {
                    Log.e(TAG, "Announce failed: ${result.exceptionOrNull()?.message}")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error while asserting time", e)
                return false
            }
        }
    }

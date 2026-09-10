package network.columba.app.service

import android.content.Context
import android.location.Location
import network.columba.app.repository.SettingsRepository
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.model.Destination
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.PacketReceipt
import network.columba.app.util.LocationCompat
import network.columba.app.util.LocationPermissionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What this pins down is the difference between "the call did not throw" and
 * "the packet went out".
 *
 * `sendPacket` returns `Result.success` for both, and the two backends disagree
 * about what a receipt means: Python RNS returns False from `Packet.send()`
 * when it could not send, and the Kotlin backend's `sendPacket` is still a stub
 * that sends nothing and reports `delivered = false` every time. Treating
 * either as a delivered report writes a timestamp into the settings card for a
 * position that never left the phone -- the one lie this feature must not tell,
 * and one that is invisible from the outside because the UI looks healthy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PositionReportManagerTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var rnsCore: RnsCore
    private lateinit var rnsLxmf: RnsLxmf

    /** A real 32-character destination hash, the only shape now accepted. */
    private val gatewayHash = "a1b2c3d4e5f60718293a4b5c6d7e8f90"

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        rnsCore = mockk(relaxed = true)
        rnsLxmf = mockk(relaxed = true)

        // Both are objects reached statically from inside the manager.
        mockkObject(LocationCompat)
        mockkObject(LocationPermissionManager)

        // No Play Services, so the fused paths are skipped entirely and the
        // fix arrives through LocationCompat, which is stubbed below.
        every { LocationCompat.isPlayServicesAvailable(any()) } returns false
        every { LocationPermissionManager.hasFineLocationPermission(any()) } returns true
        every { LocationCompat.getCurrentLocation(any(), any(), any()) } answers {
            thirdArg<(Location?) -> Unit>().invoke(freshFix())
        }

        coEvery { settingsRepository.currentPositionGatewayHash() } returns gatewayHash
        // No LXMF identity: senderId() falls back to zero, which is a case the
        // gateway already handles and keeps this test off that path.
        coEvery { rnsLxmf.getLxmfIdentity() } returns Result.failure(IllegalStateException("no identity"))
        coEvery { rnsCore.hasPath(any()) } returns true
        coEvery { rnsCore.recallIdentity(any()) } returns mockk<Identity>(relaxed = true)
        coEvery {
            rnsCore.createDestination(any(), any(), any(), any(), any())
        } returns Result.success(mockk<Destination>(relaxed = true))
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun manager() =
        PositionReportManager(
            context = context,
            settingsRepository = settingsRepository,
            rnsCore = rnsCore,
            rnsLxmf = rnsLxmf,
            scope = CoroutineScope(testDispatcher),
        )

    /** A fix from now, so the age check in freshLocation() lets it through. */
    private fun freshFix(): Location =
        mockk<Location>(relaxed = true) {
            every { time } returns System.currentTimeMillis()
            every { latitude } returns 41.0082
            every { longitude } returns 28.9784
        }

    private fun receipt(delivered: Boolean) =
        PacketReceipt(
            hash = ByteArray(32),
            delivered = delivered,
            timestamp = System.currentTimeMillis(),
        )

    @Test
    fun `a receipt the transport refused is not a report`() =
        runTest {
            coEvery { rnsCore.sendPacket(any(), any(), any()) } returns Result.success(receipt(delivered = false))

            val reported = manager().reportNow()

            assertFalse("a refused packet must not count as a report", reported)
            coVerify(exactly = 0) { settingsRepository.saveLastPositionReportTime(any()) }
        }

    @Test
    fun `a receipt the transport accepted is a report`() =
        runTest {
            coEvery { rnsCore.sendPacket(any(), any(), any()) } returns Result.success(receipt(delivered = true))

            val reported = manager().reportNow()

            assertTrue("an accepted packet is a report", reported)
            coVerify(exactly = 1) { settingsRepository.saveLastPositionReportTime(any()) }
        }

    @Test
    fun `a thrown send is not a report`() =
        runTest {
            coEvery {
                rnsCore.sendPacket(any(), any(), any())
            } returns Result.failure(IllegalStateException("no interface"))

            val reported = manager().reportNow()

            assertFalse(reported)
            coVerify(exactly = 0) { settingsRepository.saveLastPositionReportTime(any()) }
        }

    /**
     * A short hash is not a near miss. It addresses nothing, so the report has
     * to stop here rather than be built and handed to a destination that cannot
     * exist -- the UI refuses these now, but the debug harness writes this
     * setting directly.
     */
    @Test
    fun `a gateway hash of the wrong length sends nothing`() =
        runTest {
            coEvery { settingsRepository.currentPositionGatewayHash() } returns "a1b2c3d4"

            val reported = manager().reportNow()

            assertFalse(reported)
            coVerify(exactly = 0) { rnsCore.sendPacket(any(), any(), any()) }
            coVerify(exactly = 0) { settingsRepository.saveLastPositionReportTime(any()) }
        }

    @Test
    fun `a gateway hash that is not hex sends nothing`() =
        runTest {
            coEvery { settingsRepository.currentPositionGatewayHash() } returns "z1b2c3d4e5f60718293a4b5c6d7e8f90"

            val reported = manager().reportNow()

            assertFalse(reported)
            coVerify(exactly = 0) { rnsCore.sendPacket(any(), any(), any()) }
        }

    /**
     * Reticulum prints destination hashes as <hex> and people paste them back
     * with the brackets attached, so those are stripped rather than rejected.
     */
    @Test
    fun `a gateway hash in angle brackets is accepted`() =
        runTest {
            coEvery { settingsRepository.currentPositionGatewayHash() } returns "<$gatewayHash>"
            coEvery { rnsCore.sendPacket(any(), any(), any()) } returns Result.success(receipt(delivered = true))

            val reported = manager().reportNow()

            assertTrue("brackets are punctuation, not part of the hash", reported)
        }

    @Test
    fun `a stale fix is not reported`() =
        runTest {
            val stale =
                mockk<Location>(relaxed = true) {
                    every { time } returns
                        System.currentTimeMillis() - (PositionReportManager.MAX_FIX_AGE_MS + 60_000L)
                    every { latitude } returns 41.0082
                    every { longitude } returns 28.9784
                }
            every { LocationCompat.getCurrentLocation(any(), any(), any()) } answers {
                thirdArg<(Location?) -> Unit>().invoke(stale)
            }
            every { LocationCompat.getLastKnownLocation(any()) } returns null

            val reported = manager().reportNow()

            assertFalse("a position from minutes ago is not where you are", reported)
            coVerify(exactly = 0) { rnsCore.sendPacket(any(), any(), any()) }
        }

    @Test
    fun `without precise location nothing is sent`() =
        runTest {
            every { LocationPermissionManager.hasFineLocationPermission(any()) } returns false

            val reported = manager().reportNow()

            assertFalse(reported)
            coVerify(exactly = 0) { rnsCore.sendPacket(any(), any(), any()) }
        }

    @Test
    fun `with no gateway set nothing is sent`() =
        runTest {
            coEvery { settingsRepository.currentPositionGatewayHash() } returns null

            val reported = manager().reportNow()

            assertFalse(reported)
            coVerify(exactly = 0) { rnsCore.sendPacket(any(), any(), any()) }
        }
}

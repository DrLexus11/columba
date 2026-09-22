package network.columba.app.service

import android.content.Context
import android.location.Location
import network.columba.app.repository.SettingsRepository
import network.columba.app.service.PositionReportManager.Status
import network.columba.app.service.tak.CotEndpointManager
import network.columba.app.util.LocationCompat
import network.columba.app.util.LocationPermissionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Position is reported by ATAK while ATAK is connected, and by Columba only
 * while it is not.
 *
 * The first design reported alongside ATAK, to a gateway that drew the second
 * report under a different uid: a teammate saw this person twice, one of them
 * as a stranger. What is pinned here is the handover -- never both at once,
 * and never neither while the switch is on and a team is there to tell.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PositionReportManagerTest {
    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var endpoint: CotEndpointManager

    private val enabled = MutableStateFlow(true)
    private val interval = MutableStateFlow(5)
    private val endpointState = MutableStateFlow<CotEndpointManager.State>(listening(clients = 0))

    private val sent = mutableListOf<PositionCodec.Fix>()

    /** What the endpoint says about ATAK: connected and reporting lately. */
    private var atakReporting = false

    // Context is the sanctioned exception: an Android system handle with far
    // more surface than this touches. Everything else is strict.
    @Suppress("NoRelaxedMocks")
    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        settingsRepository = mockk()
        endpoint = mockk()
        every { settingsRepository.positionReportEnabledFlow } returns enabled
        every { settingsRepository.positionReportIntervalMinutesFlow } returns interval
        coEvery { settingsRepository.saveLastPositionReportTime(any()) } returns Unit
        every { endpoint.state } returns endpointState
        every { endpoint.atakIsReporting } answers { atakReporting }
        val fix = slot<PositionCodec.Fix>()
        coEvery { endpoint.reportOwnPosition(capture(fix)) } answers {
            sent.add(fix.captured)
            true
        }

        mockkObject(LocationCompat)
        mockkObject(LocationPermissionManager)
        // No Play Services, so the fix arrives through LocationCompat.
        every { LocationCompat.isPlayServicesAvailable(any()) } returns false
        every { LocationPermissionManager.hasFineLocationPermission(any()) } returns true
        every { LocationCompat.getCurrentLocation(any(), any(), any()) } answers {
            thirdArg<(Location?) -> Unit>().invoke(freshFix())
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun listening(clients: Int, members: Int = 2) =
        CotEndpointManager.State.Listening("Cyan", "00".repeat(16), clients, members)

    private fun TestScope.started(): PositionReportManager =
        PositionReportManager(context, settingsRepository, endpoint, CoroutineScope(backgroundScope.coroutineContext))
            .also { it.start() }

    /**
     * A fix from now, so the age check lets it through. Relaxed because
     * Location is a framework type whose accessors the codec probes; a real one
     * reads back zero for its time under returnDefaultValues.
     */
    @Suppress("NoRelaxedMocks")
    private fun freshFix(ageMs: Long = 0): Location =
        mockk<Location>(relaxed = true) {
            every { time } returns System.currentTimeMillis() - ageMs
            every { latitude } returns 41.0082
            every { longitude } returns 28.9784
        }

    @Test
    fun `the status follows the switch and the endpoint`() {
        assertEquals(Status.Off, PositionReportManager.statusFor(false, 5, listening(0)))
        assertEquals(Status.NeedsEndpoint, PositionReportManager.statusFor(true, 5, CotEndpointManager.State.Stopped))
        assertEquals(
            Status.NeedsEndpoint,
            PositionReportManager.statusFor(true, 5, CotEndpointManager.State.Failed("port")),
        )
        // Connected is not reporting; that is judged each cycle.
        assertEquals(Status.Reporting(5), PositionReportManager.statusFor(true, 5, listening(1)))
        assertEquals(Status.Reporting(5), PositionReportManager.statusFor(true, 5, listening(0)))
    }

    @Test
    fun `with ATAK closed the handset reports at once, stating its interval`() =
        runTest(UnconfinedTestDispatcher()) {
            started()

            assertEquals(1, sent.size)
            assertEquals("the receiver keeps the track current until the next", 5, sent.single().intervalMin)
            coVerify(exactly = 1) { settingsRepository.saveLastPositionReportTime(any()) }
        }

    @Test
    fun `and again every interval`() =
        runTest(UnconfinedTestDispatcher()) {
            started()
            advanceTimeBy(5 * 60_000L + 1)
            assertEquals(2, sent.size)
        }

    @Test
    fun `with ATAK reporting nothing is sent`() =
        runTest(UnconfinedTestDispatcher()) {
            endpointState.value = listening(clients = 1)
            atakReporting = true
            val manager = started()
            advanceTimeBy(30 * 60_000L)

            assertTrue(sent.isEmpty())
            assertEquals(Status.AtakReporting, manager.status.value)
            assertFalse("the button is declined too", manager.reportNow())
        }

    @Test
    fun `ATAK reporting stops the reports, and ATAK stopping resumes them`() =
        runTest(UnconfinedTestDispatcher()) {
            started()
            endpointState.value = listening(clients = 1)
            atakReporting = true
            advanceTimeBy(30 * 60_000L)
            assertEquals("only the report from before ATAK took over", 1, sent.size)

            atakReporting = false
            endpointState.value = listening(clients = 0)
            advanceTimeBy(PositionReportManager.CHECK_MS + 1)
            assertEquals("back within one check", 2, sent.size)
        }

    /**
     * The Nexus 6P case: ATAK connected for seven minutes indoors with no GPS
     * fix and sent nothing, while the phone's network location was good.
     */
    @Test
    fun `ATAK connected but silent is reported for`() =
        runTest(UnconfinedTestDispatcher()) {
            endpointState.value = listening(clients = 1)
            atakReporting = false
            val manager = started()

            assertEquals(1, sent.size)
            assertEquals(Status.Reporting(5, atakSilent = true), manager.status.value)
        }

    @Test
    fun `a teammate joining does not trigger a report`() =
        runTest(UnconfinedTestDispatcher()) {
            started()
            endpointState.value = listening(clients = 0, members = 3)
            endpointState.value = listening(clients = 0, members = 4)
            assertEquals(1, sent.size)
        }

    @Test
    fun `without the endpoint nothing is sent`() =
        runTest(UnconfinedTestDispatcher()) {
            endpointState.value = CotEndpointManager.State.Stopped
            val manager = started()
            assertTrue(sent.isEmpty())
            assertEquals(Status.NeedsEndpoint, manager.status.value)
        }

    @Test
    fun `a report no teammate took is not recorded, and is retried soon`() =
        runTest(UnconfinedTestDispatcher()) {
            var attempts = 0
            coEvery { endpoint.reportOwnPosition(any()) } answers {
                attempts++
                false
            }
            started()
            advanceTimeBy(PositionReportManager.RETRY_MS + 1)

            assertEquals("retried after thirty seconds, not five minutes", 2, attempts)
            coVerify(exactly = 0) { settingsRepository.saveLastPositionReportTime(any()) }
        }

    @Test
    fun `a stale fix is not reported`() =
        runTest(UnconfinedTestDispatcher()) {
            every { LocationCompat.getCurrentLocation(any(), any(), any()) } answers {
                thirdArg<(Location?) -> Unit>().invoke(freshFix(PositionReportManager.MAX_FIX_AGE_MS + 60_000L))
            }
            every { LocationCompat.getLastKnownLocation(any()) } returns null
            started()
            assertTrue("a position from minutes ago is not where you are", sent.isEmpty())
        }

    @Test
    fun `without precise location nothing is sent`() =
        runTest(UnconfinedTestDispatcher()) {
            every { LocationPermissionManager.hasFineLocationPermission(any()) } returns false
            started()
            assertTrue(sent.isEmpty())
        }
}

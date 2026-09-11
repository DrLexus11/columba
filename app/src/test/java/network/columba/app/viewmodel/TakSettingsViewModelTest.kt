package network.columba.app.viewmodel

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import network.columba.app.repository.SettingsRepository
import network.columba.app.service.PositionReportManager
import network.columba.app.service.tak.CotEndpointManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TakSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        enabled: Boolean = false,
        team: String = "Cyan",
        secret: String? = null,
    ): TakSettingsViewModel {
        val settings =
            mockk<SettingsRepository>(relaxed = true) {
                every { takEndpointEnabledFlow } returns flowOf(enabled)
                every { takTeamFlow } returns flowOf(team)
                every { takFleetSecretFlow } returns flowOf(secret)
                every { positionReportEnabledFlow } returns flowOf(false)
                every { positionReportIntervalMinutesFlow } returns flowOf(1)
                every { positionGatewayHashFlow } returns flowOf(null)
                every { lastPositionReportTimeFlow } returns flowOf(null)
            }
        val endpoint =
            mockk<CotEndpointManager> {
                every { state } returns MutableStateFlow(CotEndpointManager.State.Stopped)
            }
        return TakSettingsViewModel(settings, mockk<PositionReportManager>(relaxed = true), endpoint)
    }

    @Test
    fun `the fleet secret never reaches view state`() = runTest(dispatcher) {
        // State survives every recomposition, every saved-state write and
        // whatever a crash reporter collects. The page needs to know a secret
        // exists; it has no use for the value.
        val secret = "a fleet secret long enough to pass"
        val model = viewModel(secret = secret)
        advanceUntilIdle()

        assertTrue(model.state.value.hasFleetSecret)
        assertFalse(model.state.value.toString().contains(secret))
    }

    @Test
    fun `no secret means nothing is stored and nothing is wrong`() = runTest(dispatcher) {
        val model = viewModel(secret = null)
        advanceUntilIdle()

        assertFalse(model.state.value.hasFleetSecret)
        assertFalse("an absent secret is not a broken one", model.state.value.secretTooShort)
    }

    @Test
    fun `a stored secret too short to derive a key is reported`() = runTest(dispatcher) {
        // Checked against the same rule the derivation uses, so the page
        // cannot show a secret as usable that the endpoint will refuse the
        // moment it tries to start.
        val model = viewModel(secret = "too short")
        advanceUntilIdle()

        assertTrue(model.state.value.hasFleetSecret)
        assertTrue(model.state.value.secretTooShort)
    }

    @Test
    fun `a secret that is only whitespace padded is still usable`() = runTest(dispatcher) {
        // The Python side strips the same characters, so a secret pasted with
        // a trailing newline derives the same key rather than being refused.
        val model = viewModel(secret = "  a fleet secret long enough to pass \n")
        advanceUntilIdle()

        assertFalse(model.state.value.secretTooShort)
    }

    @Test
    fun `the team and enabled flag reach state`() = runTest(dispatcher) {
        val model = viewModel(enabled = true, team = "Magenta", secret = "x".repeat(32))
        advanceUntilIdle()

        assertTrue(model.state.value.endpointEnabled)
        assertEquals("Magenta", model.state.value.team)
    }
}

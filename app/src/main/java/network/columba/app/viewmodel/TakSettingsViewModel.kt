package network.columba.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import network.columba.app.repository.SettingsRepository
import network.columba.app.service.PositionReportManager
import network.columba.app.service.tak.CotEndpointManager
import network.columba.app.service.tak.TakGroups
import javax.inject.Inject

/**
 * State for the TAK settings page.
 *
 * Everything TAK-related is collected behind one view model rather than
 * scattered through SettingsViewModel, which is where these settings started
 * and which is already long enough that adding an arm to its combine shifts
 * every index after it.
 */
@HiltViewModel
class TakSettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val positionReportManager: PositionReportManager,
        private val cotEndpointManager: CotEndpointManager,
    ) : ViewModel() {
        data class State(
            val endpointEnabled: Boolean = false,
            val team: String = "Cyan",
            /**
             * Whether a fleet secret is stored, never the secret itself.
             *
             * The page needs to know if the endpoint can start; it does not
             * need the value, and a secret held in UI state outlives the field
             * it was typed into -- through every recomposition, every state
             * save, and into whatever a crash reporter collects.
             */
            val hasFleetSecret: Boolean = false,
            val secretTooShort: Boolean = false,
            val positionEnabled: Boolean = false,
            val positionIntervalMinutes: Int = 1,
            val positionGatewayHash: String? = null,
            val lastPositionReportTime: Long? = null,
        )

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        /** What the endpoint is actually doing, as opposed to what is configured. */
        val endpointState: StateFlow<CotEndpointManager.State> = cotEndpointManager.state

        init {
            observeEndpointSettings()
            observePositionSettings()
        }

        private fun observeEndpointSettings() {
            viewModelScope.launch {
                combine(
                    settingsRepository.takEndpointEnabledFlow,
                    settingsRepository.takTeamFlow,
                    settingsRepository.takFleetSecretFlow,
                ) { enabled, team, secret ->
                    Triple(enabled, team, secret)
                }.collect { (enabled, team, secret) ->
                    _state.value =
                        _state.value.copy(
                            endpointEnabled = enabled,
                            team = team,
                            hasFleetSecret = !secret.isNullOrEmpty(),
                            // Checked against the same rule the derivation
                            // uses, so the page cannot call a secret acceptable
                            // that TakGroups will refuse the moment the
                            // endpoint tries to start.
                            secretTooShort =
                                !secret.isNullOrEmpty() &&
                                    runCatching { TakGroups.secretBytes(secret) }.isFailure,
                        )
                }
            }
        }

        private fun observePositionSettings() {
            viewModelScope.launch {
                combine(
                    settingsRepository.positionReportEnabledFlow,
                    settingsRepository.positionReportIntervalMinutesFlow,
                    settingsRepository.positionGatewayHashFlow,
                    settingsRepository.lastPositionReportTimeFlow,
                ) { enabled, interval, gateway, last ->
                    listOf(enabled, interval, gateway, last)
                }.collect { values ->
                    _state.value =
                        _state.value.copy(
                            positionEnabled = values[0] as Boolean,
                            positionIntervalMinutes = values[1] as Int,
                            positionGatewayHash = values[2] as String?,
                            lastPositionReportTime = values[3] as Long?,
                        )
                }
            }
        }

        fun setEndpointEnabled(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.saveTakEndpointEnabled(enabled) }
        }

        fun setTeam(team: String) {
            viewModelScope.launch { settingsRepository.saveTakTeam(team) }
        }

        fun setFleetSecret(secret: String) {
            viewModelScope.launch { settingsRepository.saveTakFleetSecret(secret) }
        }

        fun setPositionEnabled(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.savePositionReportEnabled(enabled) }
        }

        fun setPositionInterval(minutes: Int) {
            viewModelScope.launch { settingsRepository.savePositionReportIntervalMinutes(minutes) }
        }

        fun setPositionGatewayHash(hash: String) {
            viewModelScope.launch { settingsRepository.savePositionGatewayHash(hash) }
        }

        fun reportPositionNow() {
            viewModelScope.launch { positionReportManager.reportNow() }
        }
    }

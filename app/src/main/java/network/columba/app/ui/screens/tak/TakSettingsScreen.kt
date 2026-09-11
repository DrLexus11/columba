package network.columba.app.ui.screens.tak

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import network.columba.app.viewmodel.TakSettingsViewModel
import network.columba.app.viewmodel.TaskViewModel

/**
 * Everything TAK, on one page.
 *
 * These settings began scattered through the general settings screen, where
 * each addition made that screen longer and none of them explained their
 * relationship to the others. Position reporting, tasking and the local CoT
 * endpoint are one capability seen from three sides, and the page is built to
 * take the rest of the roadmap -- markers, teams, data packages -- without
 * changing shape.
 *
 * Each section is a collapsible card, the same component the settings screen
 * uses, so a section can grow without pushing the others off the screen. Only
 * the endpoint opens by default: it is the one that has to be configured
 * before anything else here does anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TakSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: TakSettingsViewModel = hiltViewModel(),
    taskViewModel: TaskViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val endpointState by viewModel.endpointState.collectAsState()
    val taskState by taskViewModel.manager.state.collectAsState()

    var endpointExpanded by remember { mutableStateOf(true) }
    var positionExpanded by remember { mutableStateOf(false) }
    var authorityExpanded by remember { mutableStateOf(false) }
    var tasksExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TAK") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text =
                    "Columba carries TAK traffic over the mesh. ATAK talks to this " +
                        "phone; the phone talks to the team. Nothing here reaches a " +
                        "server, and nothing starts until you turn it on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )

            CotEndpointCard(
                isExpanded = endpointExpanded,
                onExpandedChange = { endpointExpanded = it },
                enabled = state.endpointEnabled,
                team = state.team,
                hasFleetSecret = state.hasFleetSecret,
                secretTooShort = state.secretTooShort,
                endpointState = endpointState,
                onToggle = viewModel::setEndpointEnabled,
                onTeamChange = viewModel::setTeam,
                onFleetSecretChange = viewModel::setFleetSecret,
            )

            PositionReportCard(
                isExpanded = positionExpanded,
                onExpandedChange = { positionExpanded = it },
                enabled = state.positionEnabled,
                intervalMinutes = state.positionIntervalMinutes,
                gatewayHash = state.positionGatewayHash,
                lastReportTime = state.lastPositionReportTime,
                onToggle = viewModel::setPositionEnabled,
                onIntervalChange = viewModel::setPositionInterval,
                onGatewayChange = viewModel::setPositionGatewayHash,
                onReportNow = viewModel::reportPositionNow,
            )

            TaskAuthorityCard(
                isExpanded = authorityExpanded,
                onExpandedChange = { authorityExpanded = it },
                state = taskState,
                onSaveAuthority = { taskViewModel.manager.setAuthority(it) },
            )

            ReceivedTasksCard(
                isExpanded = tasksExpanded,
                onExpandedChange = { tasksExpanded = it },
                state = taskState,
                onDecide = { row, decision -> taskViewModel.manager.decide(row, decision) },
            )

            // Clears the navigation bar and the floating elements the rest of
            // the app puts over the bottom of a scrolling screen.
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

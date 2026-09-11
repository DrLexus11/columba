package network.columba.app.ui.screens.settings.cards

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import network.columba.app.service.TaskCodec
import network.columba.app.service.tak.CotEndpointManager
import network.columba.app.ui.components.CollapsibleSettingsCard
import network.columba.app.viewmodel.TakSettingsViewModel
import network.columba.app.viewmodel.TaskViewModel

/**
 * One entry point for everything TAK, replacing three cards on this screen.
 *
 * Position reporting, tasking and the CoT endpoint are one capability, and
 * each arrived here as its own card because each was built on its own. The
 * result was a settings screen that grew with the roadmap and a task list that
 * could push everything below it off the bottom.
 *
 * Closed, this card answers the only question worth answering from here: is
 * any of it on, and is anything waiting for me.
 */
@Composable
fun TakCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpenTakSettings: () -> Unit,
    viewModel: TakSettingsViewModel = hiltViewModel(),
    taskViewModel: TaskViewModel = hiltViewModel(),
) {
    // The card reads its own state rather than being fed from
    // SettingsViewModel. That view model is already long enough that adding an
    // arm to its combine shifts every index after it, and the TAK page is the
    // owner of these values -- routing them through the settings screen would
    // make two places responsible for the same truth.
    val state by viewModel.state.collectAsState()
    val endpointState by viewModel.endpointState.collectAsState()
    val taskState by taskViewModel.manager.state.collectAsState()

    val endpointListening = endpointState is CotEndpointManager.State.Listening
    val positionReporting = state.positionEnabled
    val tasksAwaiting =
        taskState.tasks.count {
            it.status == TaskCodec.RECEIVED && taskState.now < it.message.expires
        }
    CollapsibleSettingsCard(
        title = "TAK",
        icon = Icons.Default.Map,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        headerAction = {
            // A task waiting for a decision is the one thing here that is time
            // critical, so it is visible without opening anything.
            if (tasksAwaiting > 0) {
                Badge { Text("$tasksAwaiting") }
            }
        },
    ) {
        Text(
            text =
                "ATAK over the mesh: the local CoT endpoint, position reporting " +
                    "and tasking from the command post.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = summary(endpointListening, positionReporting, tasksAwaiting),
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (endpointListening || positionReporting) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )

        Button(
            onClick = onOpenTakSettings,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text("Open TAK settings")
        }
    }
}

private fun summary(
    endpointListening: Boolean,
    positionReporting: Boolean,
    tasksAwaiting: Int,
): String {
    val on = buildList {
        if (endpointListening) add("endpoint listening")
        if (positionReporting) add("reporting position")
        if (tasksAwaiting > 0) {
            add(if (tasksAwaiting == 1) "1 task awaiting" else "$tasksAwaiting tasks awaiting")
        }
    }
    return if (on.isEmpty()) "Nothing running" else on.joinToString(" · ").replaceFirstChar { it.uppercase() }
}

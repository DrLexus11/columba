package network.columba.app.ui.screens.tak

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.columba.app.ui.components.CollapsibleSettingsCard
import network.columba.app.service.PositionReportManager.Status
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keep this handset on the team's map while ATAK is closed.
 *
 * A dependant of the endpoint switch, and shown as one: with no endpoint
 * running there is no team to report to, so the switch is disabled and says
 * why rather than looking on while sending nothing. What it is doing right now
 * -- standing by for ATAK, or reporting in its place -- is stated, because the
 * whole point is that it acts when nobody is looking at the phone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PositionReportCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    enabled: Boolean,
    endpointReady: Boolean,
    intervalMinutes: Int,
    status: Status,
    lastReportTime: Long?,
    onToggle: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onReportNow: () -> Unit,
) {
    val presetIntervals = listOf(1, 2, 5, 10)

    CollapsibleSettingsCard(
        title = "Position while ATAK is closed",
        icon = Icons.Default.MyLocation,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        headerAction = {
            Switch(
                checked = enabled && endpointReady,
                onCheckedChange = onToggle,
                enabled = endpointReady,
            )
        },
    ) {
        Text(
            text =
                "While ATAK is open it reports your position itself, and this " +
                    "stays out of the way. When ATAK is closed -- phone locked, in " +
                    "a pocket, or ATAK stopped -- Columba reports for you, as the " +
                    "same contact under the same callsign, so your team still sees you.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (!endpointReady) {
            Text(
                text = "Needs the TAK endpoint on, with a team and fleet secret. " +
                    "There is nobody to report to without one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Said once, plainly, where the switch is. Not a dialog nobody reads.
        Text(
            text =
                "This shares where you are with your team, on a schedule, until " +
                    "you turn it off. It needs precise location: approximate location " +
                    "is kilometres out, and a marker that far off is worse than none.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "How often, while ATAK is closed",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            presetIntervals.forEach { minutes ->
                FilterChip(
                    selected = intervalMinutes == minutes,
                    onClick = { onIntervalChange(minutes) },
                    enabled = enabled && endpointReady,
                    label = { Text("${minutes}m") },
                )
            }
        }
        Text(
            text =
                "Each report says when the next is due, so teammates keep your " +
                    "track current until then. Slower is lighter on the radio; " +
                    "every teammate receives a copy of every report.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = describe(status),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text =
                    lastReportTime?.let {
                        val stamp =
                            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))
                        "Columba last reported at $stamp"
                    } ?: "Columba has not reported yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onReportNow,
                enabled = status is Status.Reporting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Report position now")
            }
            Text(
                text =
                    "A report is only sent if the fix is current. A position from " +
                        "minutes ago is not where you are.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** What reporting is doing right now, in the operator's terms. */
private fun describe(status: Status): String =
    when (status) {
        Status.Off -> "Off. Teammates see you only while ATAK is open."
        Status.NeedsEndpoint -> "Waiting for the TAK endpoint to start."
        Status.AtakReporting -> "ATAK is connected and reporting your position. Standing by."
        is Status.Reporting -> "ATAK is closed. Reporting every ${status.intervalMinutes} min."
    }

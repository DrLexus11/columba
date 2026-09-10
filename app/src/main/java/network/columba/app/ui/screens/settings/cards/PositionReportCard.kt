package network.columba.app.ui.screens.settings.cards

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.columba.app.ui.components.CollapsibleSettingsCard
import network.columba.app.util.DestinationHashValidator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Report this device's position to a gateway that serves ATAK.
 *
 * The card carries the gateway field because the feature does nothing without
 * it, and says so rather than looking enabled while sending nowhere. It also
 * states plainly what turning this on means: position is the most sensitive
 * thing this app emits, and the switch should not feel like any other switch.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PositionReportCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    enabled: Boolean,
    intervalMinutes: Int,
    gatewayHash: String?,
    lastReportTime: Long?,
    onToggle: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onGatewayChange: (String) -> Unit,
    onReportNow: () -> Unit,
) {
    val presetIntervals = listOf(1, 2, 5, 10)
    var gatewayField by remember(gatewayHash) { mutableStateOf(gatewayHash ?: "") }
    // The app already has one answer to "is this a destination hash", used by
    // the manual relay field and the nomadnet parser. A second, looser one here
    // would let this card accept addresses the rest of the app calls invalid.
    val validation = DestinationHashValidator.validate(gatewayField)
    val gatewayIsValid = validation is DestinationHashValidator.ValidationResult.Valid
    val gatewayError = (validation as? DestinationHashValidator.ValidationResult.Error)?.message

    CollapsibleSettingsCard(
        title = "Position Reporting",
        icon = Icons.Default.MyLocation,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        headerAction = {
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
            )
        },
    ) {
        Text(
            text =
                "Send this device's position to a gateway that expands it into a " +
                    "CoT feed for ATAK. Twenty bytes over the mesh, so it fits on a " +
                    "radio that a full CoT message would not.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Said once, plainly, where the switch is. Not a dialog nobody reads.
        Text(
            text =
                "This shares where you are, on a schedule, until you turn it off. " +
                    "It needs precise location: approximate location is kilometres " +
                    "out, and a marker that far off is worse than none.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "How often",
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
                    enabled = enabled,
                    label = { Text("${minutes}m") },
                )
            }
        }
        Text(
            text =
                "Ten nodes reporting once a minute fits the airtime budget; " +
                    "twenty-five does not. Slow this down as the fleet grows.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Without this the switch is inert, so it is a field rather than
        // something buried behind an advanced screen.
        Text(
            text = "Gateway destination",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = gatewayField,
            onValueChange = { entered ->
                // Filtered and capped rather than validated after the fact, so
                // a stray character cannot be typed at all. Same treatment the
                // manual relay field gives the same kind of value.
                val filtered =
                    entered
                        .filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                        .lowercase()
                        .take(DestinationHashValidator.REQUIRED_LENGTH)
                gatewayField = filtered
                // Persisted only when it is a whole hash or nothing at all.
                // Every write here restarts the reporting loop through
                // collectLatest, and saving each keystroke meant 32 restarts
                // and 32 DataStore writes to enter one gateway -- each one
                // briefly pointing the loop at a prefix that addresses nothing.
                if (filtered.isEmpty() || filtered.length == DestinationHashValidator.REQUIRED_LENGTH) {
                    onGatewayChange(filtered)
                }
            },
            label = { Text("Destination hash") },
            placeholder = { Text("32 hex characters") },
            singleLine = true,
            isError = gatewayField.isNotEmpty() && !gatewayIsValid,
            supportingText = {
                Text(
                    text =
                        when {
                            gatewayField.isEmpty() -> "Nothing is sent until a gateway is set"
                            // Says which of the two things is wrong, and how
                            // far off: "Hash must be 32 characters (got 12)"
                            // rather than a flat "invalid".
                            gatewayError != null -> gatewayError
                            else -> "Reports go only here, not to the whole mesh"
                        },
                )
            },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text =
                    lastReportTime?.let {
                        val stamp =
                            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))
                        "Last reported at $stamp"
                    } ?: "Nothing reported yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onReportNow,
                enabled = enabled && gatewayIsValid,
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

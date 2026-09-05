package network.columba.app.ui.screens.settings.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.columba.app.ui.components.CollapsibleSettingsCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Offer this device's clock to the mesh as a signed assertion.
 *
 * The card has to carry the identity hash, because the feature does nothing
 * without it: a node only adopts time from an authority whose key it was
 * given out of band, so the operator has to copy this onto each node before
 * the switch means anything. A toggle with no way to read the hash would look
 * like it was working while nothing on the mesh ever changed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimeAuthorityCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    enabled: Boolean,
    intervalMinutes: Int,
    lastAssertionTime: Long?,
    identityHash: String?,
    onToggle: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onAssertNow: () -> Unit,
    onCopyIdentityHash: (String) -> Unit,
) {
    val presetIntervals = listOf(15, 30, 60, 120)

    CollapsibleSettingsCard(
        title = "Time Authority",
        icon = Icons.Default.Schedule,
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
                "Nodes on the mesh have no clock they can trust: they reboot to an " +
                    "uptime counter and drift further behind on every restart. This phone " +
                    "keeps real time, so it can offer it to every node in range as a " +
                    "signed statement that relays can carry but cannot alter.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    label = {
                        Text(if (minutes < 60) "${minutes}m" else "${minutes / 60}h")
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // What the operator must put on each node. Without this the switch is
        // inert as far as the mesh is concerned.
        Text(
            text = "Provision this identity on each node",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (identityHash.isNullOrBlank()) {
            Text(
                text = "No identity yet. Create or unlock one first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = identityHash,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = { onCopyIdentityHash(identityHash) }) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy identity hash",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                text = "A node ignores time from anyone it was not told to trust.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text =
                    lastAssertionTime?.let {
                        val stamp =
                            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))
                        "Last asserted at $stamp"
                    } ?: "Nothing asserted yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onAssertNow,
                enabled = enabled && !identityHash.isNullOrBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Assert time now")
            }
            Text(
                text =
                    "Useful on arrival: every node in range gets the time immediately " +
                        "rather than waiting out the interval.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

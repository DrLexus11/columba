package network.columba.app.ui.screens.tak

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import network.columba.app.ui.components.CollapsibleSettingsCard

/**
 * Whether ATAK may act on this node, not only read it.
 *
 * The ATAK plugin always reads the mesh -- peers, paths, the propagation node.
 * Commands are another matter: any plugin loaded in ATAK can reach the same
 * interface, and a command can disrupt, so they wait for this switch
 * (reticulum-atak OpenDecisions 1). Every command is logged with its caller.
 */
@Composable
fun AtakControlCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    allowed: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    CollapsibleSettingsCard(
        title = "ATAK control",
        icon = Icons.Default.SettingsRemote,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Allow ATAK to control this node",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = allowed, onCheckedChange = onToggle)
        }
        Text(
            text =
                "The ATAK plugin can always see the mesh. With this on, it can also " +
                    "announce this node. Any plugin in ATAK can use the same commands, " +
                    "so leave it off unless this phone's ATAK is set up by your team.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

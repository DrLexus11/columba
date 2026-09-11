package network.columba.app.ui.screens.tak

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import network.columba.app.service.tak.CotEndpointManager
import network.columba.app.service.tak.TakGroups
import network.columba.app.ui.components.CollapsibleSettingsCard

/**
 * The local CoT endpoint: ATAK connects to this device, not to a server.
 *
 * The card carries the team and the fleet secret because the switch is inert
 * without both, and says which one is missing rather than looking enabled while
 * listening to nobody.
 */
@Composable
fun CotEndpointCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    enabled: Boolean,
    team: String,
    hasFleetSecret: Boolean,
    secretTooShort: Boolean,
    endpointState: CotEndpointManager.State,
    onToggle: (Boolean) -> Unit,
    onTeamChange: (String) -> Unit,
    onFleetSecretChange: (String) -> Unit,
) {
    val context = LocalContext.current
    var teamField by remember(team) { mutableStateOf(team) }
    // Never seeded from stored state: the secret is deliberately not held in
    // view-model state, so an empty field means "leave what is stored alone"
    // rather than "the stored secret is empty".
    var secretField by remember { mutableStateOf("") }
    var secretVisible by remember { mutableStateOf(false) }
    // Asked of TakGroups rather than counted here. The derivation trims ASCII
    // whitespace and measures UTF-8 bytes; counting trimmed characters instead
    // disabled Save on a multibyte secret that was long enough, and disagreed
    // with the derivation about Unicode whitespace.
    val secretIsUsable = TakGroups.secretIsUsable(secretField)

    CollapsibleSettingsCard(
        title = "Local CoT endpoint",
        icon = Icons.Default.Lan,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        headerAction = {
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                enabled = hasFleetSecret && !secretTooShort,
            )
        },
    ) {
        Text(
            text =
                "ATAK connects to this phone instead of a server. Everything it " +
                    "sends goes to your team over the mesh, and everything the team " +
                    "sends appears on your map.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(4.dp))

        // The reason this design exists, said where the switch is.
        Text(
            text =
                "Because each phone runs its own endpoint, losing the command " +
                    "post thins the picture out instead of disconnecting everyone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        EndpointStatus(endpointState, hasFleetSecret)

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Team",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
        OutlinedTextField(
            value = teamField,
            onValueChange = { teamField = it },
            label = { Text("Team name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            supportingText = {
                Text(
                    "Everyone on this team with the same fleet secret shares a " +
                        "channel. Capitals and spacing do not matter.",
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { onTeamChange(teamField) },
            enabled = teamField.isNotBlank() && teamField.trim() != team.trim(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save team")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Fleet secret",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
        OutlinedTextField(
            value = secretField,
            onValueChange = { secretField = it },
            label = { Text(if (hasFleetSecret) "Replace fleet secret" else "Fleet secret") },
            singleLine = true,
            visualTransformation =
                if (secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            trailingIcon = {
                IconButton(onClick = { secretVisible = !secretVisible }) {
                    Icon(
                        imageVector =
                            if (secretVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (secretVisible) "Hide secret" else "Show secret",
                    )
                }
            },
            isError = secretField.isNotEmpty() && !secretIsUsable,
            supportingText = {
                Text(
                    text =
                        when {
                            secretField.isNotEmpty() && !secretIsUsable ->
                                "At least ${TakGroups.MIN_SECRET_BYTES} bytes"
                            secretTooShort -> "The stored secret is too short to use"
                            hasFleetSecret -> "A secret is stored. Leave blank to keep it."
                            // Not a hint to invent one: every node in the fleet
                            // must hold the same value, so this comes from
                            // whoever provisioned the fleet.
                            else -> "Provided with your fleet. The same value on every node."
                        },
                    color =
                        if (secretTooShort) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    onFleetSecretChange(secretField)
                    // Cleared immediately: the value is stored now, and a
                    // secret left sitting in a field is a secret on the screen.
                    secretField = ""
                    secretVisible = false
                },
                enabled = secretIsUsable,
                modifier = Modifier.weight(1f),
            ) {
                Text("Save secret")
            }
            OutlinedButton(
                onClick = {
                    onFleetSecretChange("")
                    secretField = ""
                },
                enabled = hasFleetSecret,
                modifier = Modifier.weight(1f),
            ) {
                Text("Clear")
            }
        }

        if (endpointState is CotEndpointManager.State.Listening) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Point ATAK here",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "TCP, no SSL",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "127.0.0.1:${CotEndpointManager.PORT}",
                style =
                    MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(top = 2.dp),
            )
            OutlinedButton(
                onClick = {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            "CoT endpoint",
                            "127.0.0.1:${CotEndpointManager.PORT}",
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Copy address")
            }
        }
    }
}

@Composable
private fun EndpointStatus(
    state: CotEndpointManager.State,
    hasFleetSecret: Boolean,
) {
    val (headline, detail, tone) =
        when (state) {
            is CotEndpointManager.State.Listening ->
                Triple(
                    if (state.clients == 0) {
                        "Listening — no client connected"
                    } else if (state.clients == 1) {
                        "Listening — ATAK connected"
                    } else {
                        "Listening — ${state.clients} clients connected"
                    },
                    "Team ${state.team} · ${state.destinationHash}",
                    MaterialTheme.colorScheme.primary,
                )

            is CotEndpointManager.State.Failed ->
                Triple("Not listening", state.reason, MaterialTheme.colorScheme.error)

            CotEndpointManager.State.Stopped ->
                // Saving a secret and turning the endpoint on are two actions
                // at opposite ends of the card, and the second is easy to miss
                // -- it was, the first time this ran on hardware. Once a secret
                // is stored, say plainly that the only thing left is the switch.
                if (hasFleetSecret) {
                    Triple(
                        "Off -- ready to start",
                        "Fleet secret stored. Turn on the switch above to start listening.",
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Triple(
                        "Off",
                        "Set a team and a fleet secret, then turn this on.",
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = headline,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = tone,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Matches TakGroups.MIN_SECRET_BYTES; ASCII, so characters and bytes agree. */

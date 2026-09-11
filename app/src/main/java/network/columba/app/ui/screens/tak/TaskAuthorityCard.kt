package network.columba.app.ui.screens.tak

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import network.columba.app.service.TaskManager
import network.columba.app.ui.components.CollapsibleSettingsCard

/**
 * Who is allowed to task this device.
 *
 * Split from the task list, which grows without bound: mixing a setting that
 * changes twice a year with a list that changes hourly meant scrolling past
 * every task to reach the key, or past the key to reach the tasks.
 */
@Composable
fun TaskAuthorityCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    state: TaskManager.State,
    onSaveAuthority: (String) -> Unit,
) {
    val context = LocalContext.current
    var authority by remember(state.owner, state.authority) { mutableStateOf(state.authority) }
    val valid =
        authority.isEmpty() ||
            (authority.length == AUTHORITY_KEY_LENGTH && authority.all { it in HEX })

    CollapsibleSettingsCard(
        title = "Tasking authority",
        icon = Icons.Default.VerifiedUser,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text =
                "Trust the public key supplied directly by your command-post " +
                    "operator. Only that authority can issue tasks to this identity.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = authority,
            onValueChange = { authority = it.trim() },
            label = { Text("Command-post public key") },
            isError = !valid,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            supportingText = {
                Text(
                    text =
                        when {
                            !valid && authority.length != AUTHORITY_KEY_LENGTH ->
                                "$AUTHORITY_KEY_LENGTH hexadecimal characters (got ${authority.length})"
                            !valid -> "Hexadecimal characters only"
                            else -> "Clear and save to stop receiving tasks."
                        },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { onSaveAuthority(authority) },
            enabled = valid && state.owner.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save trust setting")
        }

        OutlinedButton(
            onClick = {
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("Task receiver public key", state.publicKey),
                )
            },
            enabled = state.publicKey.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Copy my public key for command post")
        }
    }
}

private const val AUTHORITY_KEY_LENGTH = 128
private const val HEX = "0123456789abcdefABCDEF"

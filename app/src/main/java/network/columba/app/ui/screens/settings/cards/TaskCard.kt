package network.columba.app.ui.screens.settings.cards

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import network.columba.app.service.TaskCodec
import network.columba.app.ui.components.CollapsibleSettingsCard
import network.columba.app.viewmodel.TaskViewModel

@Composable
fun TaskCard(viewModel: TaskViewModel = hiltViewModel()) {
    val manager = viewModel.manager
    val state by manager.state.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var authority by remember(state.owner, state.authority) { mutableStateOf(state.authority) }
    val valid = authority.isEmpty() || (authority.length == 128 && authority.all { it in "0123456789abcdefABCDEF" })
    val context = LocalContext.current
    CollapsibleSettingsCard(
        title = "TAK tasks",
        icon = Icons.Default.Assignment,
        isExpanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.message)
            Text("Trust the public key supplied directly by your command-post operator. Only that authority can issue tasks to this identity.")
            OutlinedTextField(
                value = authority,
                onValueChange = { authority = it.trim() },
                label = { Text("Command-post public key") },
                isError = !valid,
                supportingText = { Text("128 hexadecimal characters. Clear and save to stop receiving.") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { manager.setAuthority(authority) }, enabled = valid && state.owner.isNotEmpty()) { Text("Save trust setting") }
            OutlinedButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Task receiver public key", state.publicKey))
            }, enabled = state.publicKey.isNotEmpty()) { Text("Copy my public key for command post") }
            if (state.tasks.isEmpty()) Text("No verified tasks received")
            state.tasks.forEach { row ->
                HorizontalDivider()
                val task = row.message
                val expired = state.now >= task.expires
                val trusted = row.publicKey == state.authority
                val status =
                    when (row.status) {
                        TaskCodec.ACCEPTED -> "Accepted"
                        TaskCodec.DECLINED -> "Declined"
                        else -> if (expired) "Expired" else "Received — awaiting your decision"
                    }
                Text("Go to point · $status")
                Text(task.instruction)
                Text("${task.latE7 / 1e7}, ${task.lonE7 / 1e7}")
                Text("Expires ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(task.expires * 1000))}")
                if (!trusted) Text("This task's authority is no longer trusted")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { manager.decide(row, TaskCodec.ACCEPTED) },
                        enabled = !expired && trusted && row.status == TaskCodec.RECEIVED,
                    ) { Text("Accept") }
                    OutlinedButton(
                        onClick = { manager.decide(row, TaskCodec.DECLINED) },
                        enabled = !expired && trusted && row.status == TaskCodec.RECEIVED,
                    ) { Text("Decline") }
                }
                OutlinedButton(onClick = {
                    val uri = Uri.parse("geo:${task.latE7 / 1e7},${task.lonE7 / 1e7}?q=${task.latE7 / 1e7},${task.lonE7 / 1e7}")
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
                }, enabled = trusted && !expired) { Text("Open point in map") }
                Text("Response attempts: ${row.attempts}/3. Sending a response does not confirm command-post receipt.")
            }
        }
    }
}

package network.columba.app.ui.screens.tak

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import network.columba.app.service.TaskCodec
import network.columba.app.service.TaskManager
import network.columba.app.service.TaskStore
import network.columba.app.ui.components.CollapsibleSettingsCard
import java.text.DateFormat
import java.util.Date

/**
 * Tasks received from the command post, one collapsed row each.
 *
 * Every task used to render in full, so the card grew without bound and a
 * settings page with five tasks on it could not be scrolled past. A task is
 * now a row: what it is, and whether it needs a decision. The detail and the
 * buttons appear when it is opened.
 *
 * Anything still awaiting a decision opens by default, because that is the
 * only reason to be on this screen in a hurry.
 */
@Composable
fun ReceivedTasksCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    state: TaskManager.State,
    onDecide: (TaskStore.Row, Int) -> Unit,
) {
    val awaiting =
        state.tasks.count { it.status == TaskCodec.RECEIVED && state.now < it.message.expires }

    CollapsibleSettingsCard(
        title = "Tasks",
        icon = Icons.Default.Assignment,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        headerAction = {
            // The count belongs in the header: the reason to open this card is
            // that something is waiting, and that should be legible closed.
            if (awaiting > 0) {
                Badge { Text("$awaiting") }
            } else if (state.tasks.isNotEmpty()) {
                Text(
                    text = "${state.tasks.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        if (state.tasks.isEmpty()) {
            Text(
                text = "No verified tasks received.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@CollapsibleSettingsCard
        }

        // Keyed by issuer and task id rather than list position, so a task
        // arriving while the card is open does not shift what is expanded.
        val opened = remember { mutableStateMapOf<String, Boolean>() }

        state.tasks.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider()
            val expired = state.now >= row.message.expires
            val actionable = row.status == TaskCodec.RECEIVED && !expired
            TaskRow(
                row = row,
                state = state,
                expired = expired,
                isOpen = opened[key(row)] ?: actionable,
                onOpenChange = { opened[key(row)] = it },
                onDecide = onDecide,
            )
        }
    }
}

@Composable
private fun TaskRow(
    row: TaskStore.Row,
    state: TaskManager.State,
    expired: Boolean,
    isOpen: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onDecide: (TaskStore.Row, Int) -> Unit,
) {
    val context = LocalContext.current
    val task = row.message
    val trusted = row.publicKey == state.authority
    val status =
        when (row.status) {
            TaskCodec.ACCEPTED -> "Accepted"
            TaskCodec.DECLINED -> "Declined"
            else -> if (expired) "Expired" else "Awaiting your decision"
        }
    val latitude = task.latE7 / 1e7
    val longitude = task.lonE7 / 1e7

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpenChange(!isOpen) }
                    .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color =
                        when {
                            !trusted -> MaterialTheme.colorScheme.error
                            row.status == TaskCodec.RECEIVED && !expired ->
                                MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                Text(
                    // One line closed. The instruction is what identifies a
                    // task to the person reading it, so it is the summary --
                    // but it is free text from the command post and can be any
                    // length at all.
                    text = task.instruction,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (isOpen) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector =
                    if (isOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (isOpen) "Collapse task" else "Expand task",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(visible = isOpen) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Text(
                    text = "$latitude, $longitude",
                    style =
                        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text =
                        "Expires ${DateFormat.getDateTimeInstance().format(Date(task.expires * 1000))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!trusted) {
                    Text(
                        text = "This task's authority is no longer trusted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onDecide(row, TaskCodec.ACCEPTED) },
                        enabled = !expired && trusted && row.status == TaskCodec.RECEIVED,
                    ) {
                        Text("Accept")
                    }
                    OutlinedButton(
                        onClick = { onDecide(row, TaskCodec.DECLINED) },
                        enabled = !expired && trusted && row.status == TaskCodec.RECEIVED,
                    ) {
                        Text("Decline")
                    }
                }

                OutlinedButton(
                    onClick = {
                        val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        if (intent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(intent)
                        }
                    },
                    enabled = trusted && !expired,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open point in map")
                }

                Text(
                    text =
                        "Response attempts: ${row.attempts}/3. Sending a response " +
                            "does not confirm command-post receipt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
    }
}

private fun key(row: TaskStore.Row) = "${row.message.issuer}/${row.message.taskId}"

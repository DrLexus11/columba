package network.columba.app.ui.screens.tak

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import network.columba.app.service.tak.TakFileStore
import network.columba.app.service.tak.TakFiles

/**
 * The files this handset holds for TAK, and a way to let them go.
 *
 * Only the sender keeps a full file -- no board or propagation node holds one
 * -- so deleting a file this operator sent means a teammate who has not yet
 * fetched it no longer can. Said where the button is.
 */
@Composable
fun HeldFilesCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    held: List<TakFileStore.Held>,
    nowMs: Long,
    onDelete: (String) -> Unit,
    onDeleteAll: () -> Unit,
) {
    var confirmAll by remember { mutableStateOf(false) }
    // A page at a time: over a day in the field this list only grows, and a
    // card that scrolls for ever buries everything below it on the page.
    var page by remember { mutableStateOf(0) }
    val pages = maxOf(1, (held.size + PAGE_SIZE - 1) / PAGE_SIZE)
    if (page >= pages) page = pages - 1
    val shown = held.drop(page * PAGE_SIZE).take(PAGE_SIZE)

    network.columba.app.ui.components.CollapsibleSettingsCard(
        title = "Files held",
        icon = Icons.Default.FolderZip,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        Text(
            text =
                if (held.isEmpty()) {
                    "No data packages or QuickPics are held on this phone."
                } else {
                    "${held.size} file(s), ${TakFiles.sizeText(held.sumOf { it.size })}: " +
                        TakFileStore.Kind.entries.mapNotNull { kind ->
                            held.count { it.kind == kind }.takeIf { it > 0 }?.let { "$it ${kindText(kind).lowercase()}" }
                        }.joinToString(", ") + ". Kept until deleted here."
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text =
                "Files you sent stay here so teammates can fetch them over a fast path. " +
                    "Deleting one means anyone who has not fetched it yet no longer can.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(8.dp))

        shown.forEach { file ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${kindText(file.kind)} · ${TakFiles.sizeText(file.size)} · ${ageText(nowMs - file.storedAtMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onDelete(file.hash) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete ${file.name}")
                }
            }
        }

        if (pages > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = { page -= 1 }, enabled = page > 0) { Text("Newer") }
                Text(
                    text = "${page * PAGE_SIZE + 1}-${page * PAGE_SIZE + shown.size} of ${held.size}",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { page += 1 }, enabled = page < pages - 1) { Text("Older") }
            }
        }

        if (held.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = { confirmAll = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Delete all")
            }
        }
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Delete all held files?") },
            text = { Text("Teammates who have not fetched a file you sent will no longer be able to.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmAll = false
                    onDeleteAll()
                }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
        )
    }
}

/** Rows per page: enough to recognise the latest, few enough to leave the page usable. */
private const val PAGE_SIZE = 5

private fun kindText(kind: TakFileStore.Kind) =
    when (kind) {
        TakFileStore.Kind.SENT -> "Sent"
        TakFileStore.Kind.RECEIVED -> "Received"
        TakFileStore.Kind.PREVIEW -> "Preview"
    }

private fun ageText(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} d ago"
    }
}

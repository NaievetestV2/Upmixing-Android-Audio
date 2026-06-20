package com.androidsurround.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidsurround.model.Playlist
import com.androidsurround.model.PlaylistItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistSheet(
    playlists: List<Playlist>,
    queueItems: List<PlaylistItem>,
    currentIndex: Int,
    isShuffled: Boolean,
    repeatMode: String,
    onDismiss: () -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onRenamePlaylist: (String, String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onLoadPlaylist: (String) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onPlayItem: (Int) -> Unit,
    onAddCurrentToPlaylist: (String) -> Unit,
) {
    var tab by remember { mutableStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Playlists", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onToggleShuffle) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (isShuffled) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onCycleRepeat) {
                    Icon(
                        when (repeatMode) {
                            "ONE" -> Icons.Filled.RepeatOne
                            else -> Icons.Filled.Repeat
                        },
                        contentDescription = "Repeat: $repeatMode",
                        tint = if (repeatMode != "NONE") MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Queue") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Playlists") })
            }

            Spacer(Modifier.height(8.dp))

            when (tab) {
                0 -> QueueTab(
                    items = queueItems,
                    currentIndex = currentIndex,
                    onPlayItem = onPlayItem,
                )
                1 -> PlaylistsTab(
                    playlists = playlists,
                    onCreatePlaylist = onCreatePlaylist,
                    onRenamePlaylist = onRenamePlaylist,
                    onDeletePlaylist = onDeletePlaylist,
                    onLoadPlaylist = onLoadPlaylist,
                    onAddCurrentToPlaylist = onAddCurrentToPlaylist,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun QueueTab(
    items: List<PlaylistItem>,
    currentIndex: Int,
    onPlayItem: (Int) -> Unit,
) {
    if (items.isEmpty()) {
        Text("Queue is empty", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp))
        return
    }
    Text("${items.size} item(s)", style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
        items(items.withIndex().toList()) { (idx, item) ->
            val isCurrent = idx == currentIndex
            ListItem(
                headlineContent = { Text(item.title.ifEmpty { item.uri }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(if (item.isVideo) "Video" else "Audio", style = MaterialTheme.typography.bodySmall) },
                leadingContent = {
                    Icon(
                        if (item.isVideo) Icons.Filled.Videocam else Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = if (isCurrent) {
                    { Icon(Icons.Filled.PlayArrow, "Now playing", tint = MaterialTheme.colorScheme.primary) }
                } else null,
                modifier = Modifier.clickable { onPlayItem(idx) },
            )
            if (idx < items.size - 1) Divider()
        }
    }
}

@Composable
private fun PlaylistsTab(
    playlists: List<Playlist>,
    onCreatePlaylist: (String) -> Unit,
    onRenamePlaylist: (String, String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onLoadPlaylist: (String) -> Unit,
    onAddCurrentToPlaylist: (String) -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Button(
        onClick = { showCreateDialog = true },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.Add, null)
        Spacer(Modifier.width(8.dp))
        Text("New Playlist")
    }

    Spacer(Modifier.height(8.dp))

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create Playlist") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        onCreatePlaylist(newName.trim())
                        newName = ""
                        showCreateDialog = false
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") } },
        )
    }

    if (playlists.isEmpty()) {
        Text("No playlists yet", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp))
        return
    }

    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
        items(playlists) { pl ->
            var showMenu by remember { mutableStateOf(false) }
            var showRename by remember { mutableStateOf(false) }
            var renameText by remember { mutableStateOf(pl.name) }

            ListItem(
                headlineContent = { Text(pl.name, fontWeight = FontWeight.Medium) },
                supportingContent = { Text("${pl.items.size} items") },
                leadingContent = { Icon(Icons.Filled.PlaylistPlay, null) },
                trailingContent = {
                    Row {
                        IconButton(onClick = { onLoadPlaylist(pl.id) }) {
                            Icon(Icons.Filled.PlayArrow, "Play")
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Filled.MoreVert, "More")
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Add current to playlist") },
                                    leadingIcon = { Icon(Icons.Filled.Add, null) },
                                    onClick = {
                                        showMenu = false
                                        onAddCurrentToPlaylist(pl.id)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                    onClick = {
                                        showMenu = false
                                        showRename = true
                                        renameText = pl.name
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        showMenu = false
                                        onDeletePlaylist(pl.id)
                                    },
                                )
                            }
                        }
                    }
                },
                modifier = Modifier.clickable { onLoadPlaylist(pl.id) },
            )
                    if (showRename) {
                AlertDialog(
                    onDismissRequest = { showRename = false },
                    title = { Text("Rename Playlist") },
                    text = {
                        OutlinedTextField(
                            value = renameText,
                            onValueChange = { renameText = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (renameText.isNotBlank()) {
                                onRenamePlaylist(pl.id, renameText.trim())
                                showRename = false
                            }
                        }) { Text("Rename") }
                    },
                    dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
                )
            }
            Divider()
        }
    }
}

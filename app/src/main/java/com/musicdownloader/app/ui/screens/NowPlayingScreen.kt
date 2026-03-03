package com.musicdownloader.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musicdownloader.app.ui.viewmodel.PlayerViewModel
import com.musicdownloader.app.ui.viewmodel.QueueItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val state by playerViewModel.playerState.collectAsState()
    val queueItems by playerViewModel.queueItems.collectAsState()
    var showQueueDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Now Playing") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.KeyboardArrowDown, "Close")
                    }
                },
                actions = {
                    IconButton(onClick = { playerViewModel.toggleNormalize() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeUp,
                            "Normalize volume",
                            tint = if (state.normalizeEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.weight(1f))

            // Album art placeholder
            Card(
                modifier = Modifier.size(280.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(120.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(Modifier.height(40.dp))

            // Song info
            Text(
                state.currentTitle.ifEmpty { "Not Playing" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                state.currentArtist,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(32.dp))

            // Seek bar
            if (state.duration > 0) {
                Slider(
                    value = state.position.toFloat(),
                    onValueChange = { playerViewModel.seekTo(it.toLong()) },
                    valueRange = 0f..state.duration.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(state.position), style = MaterialTheme.typography.bodySmall)
                    Text(formatTime(state.duration), style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(24.dp))

            // Playback controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Queue button with count
                Box {
                    IconButton(onClick = { showQueueDialog = true }) {
                        Icon(
                            Icons.Default.List,
                            "Queue",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    val remaining = state.queueSize - state.queueIndex - 1
                    if (remaining > 0) {
                        Text(
                            "$remaining",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.TopEnd)
                        )
                    }
                }

                // Previous
                IconButton(
                    onClick = { playerViewModel.skipPrevious() },
                    enabled = state.hasPrevious
                ) {
                    Icon(
                        Icons.Default.SkipPrevious, "Previous",
                        modifier = Modifier.size(40.dp)
                    )
                }

                // Play/Pause
                FilledIconButton(
                    onClick = { playerViewModel.togglePlayPause() },
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(40.dp)
                    )
                }

                // Next
                IconButton(
                    onClick = { playerViewModel.skipNext() },
                    enabled = state.hasNext
                ) {
                    Icon(
                        Icons.Default.SkipNext, "Next",
                        modifier = Modifier.size(40.dp)
                    )
                }

                // Repeat toggle
                IconButton(onClick = { playerViewModel.toggleRepeat() }) {
                    Icon(
                        Icons.Default.Repeat,
                        "Repeat",
                        tint = if (state.repeatEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(Modifier.weight(1f))
        }
    }

    // Queue dialog
    if (showQueueDialog) {
        QueueDialog(
            queueItems = queueItems,
            currentIndex = state.queueIndex,
            onRemove = { playerViewModel.removeFromQueue(it) },
            onClear = {
                playerViewModel.clearQueue()
                showQueueDialog = false
            },
            onDismiss = { showQueueDialog = false }
        )
    }
}

@Composable
private fun QueueDialog(
    queueItems: List<QueueItem>,
    currentIndex: Int,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Queue (${queueItems.size})")
                if (queueItems.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("Clear") }
                }
            }
        },
        text = {
            if (queueItems.isEmpty()) {
                Text("Queue is empty")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    itemsIndexed(queueItems) { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (index == currentIndex) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Spacer(Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    item.song.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (index == currentIndex)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "${item.song.artist} · ${item.sourcePlaylistName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (index != currentIndex) {
                                IconButton(
                                    onClick = { onRemove(index) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        "Remove",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

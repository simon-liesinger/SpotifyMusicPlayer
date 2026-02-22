package com.musicdownloader.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musicdownloader.app.data.db.SongEntity
import com.musicdownloader.app.data.repository.MusicRepository
import com.musicdownloader.app.ui.viewmodel.MainViewModel
import com.musicdownloader.app.ui.viewmodel.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    mainViewModel: MainViewModel,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    onNowPlayingClick: () -> Unit
) {
    val playlistWithSongs by mainViewModel.getPlaylistWithSongs(playlistId)
        .collectAsState(initial = null)
    val allPlaylists by mainViewModel.playlists.collectAsState()
    val addSongState by mainViewModel.addSongState.collectAsState()

    val playlist = playlistWithSongs?.playlist
    val songs = playlistWithSongs?.songs ?: emptyList()

    // Selection state
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    val inSelectionMode = selectedIds.isNotEmpty()

    // Dialog state
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showCopyToPicker by remember { mutableStateOf(false) }
    var showNewPlaylistName by remember { mutableStateOf(false) }
    var showAddSongDialog by remember { mutableStateOf(false) }

    fun clearSelection() { selectedIds = emptySet() }

    Scaffold(
        topBar = {
            if (inSelectionMode) {
                TopAppBar(
                    title = { Text("${selectedIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { clearSelection() }) {
                            Icon(Icons.Default.Close, "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showCopyToPicker = true }) {
                            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist")
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, "Remove")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            } else {
                TopAppBar(
                    title = { Text(playlist?.name ?: "Playlist") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!inSelectionMode) {
                FloatingActionButton(
                    onClick = { showAddSongDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, "Add song")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Play controls
            if (songs.isNotEmpty() && !inSelectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            playerViewModel.playSongs(songs)
                            onNowPlayingClick()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Play All")
                    }
                    OutlinedButton(
                        onClick = {
                            playerViewModel.playSongs(songs, shuffle = true)
                            onNowPlayingClick()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Shuffle, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Shuffle")
                    }
                }

                Text(
                    "${songs.size} songs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (songs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.MusicOff,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No songs in this playlist",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Tap + to add a song",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                        val isSelected = song.id in selectedIds
                        SongListItem(
                            song = song,
                            index = index + 1,
                            isSelected = isSelected,
                            inSelectionMode = inSelectionMode,
                            onClick = {
                                if (inSelectionMode) {
                                    selectedIds = if (isSelected) {
                                        selectedIds - song.id
                                    } else {
                                        selectedIds + song.id
                                    }
                                } else {
                                    playerViewModel.playSongs(songs, startIndex = index)
                                    onNowPlayingClick()
                                }
                            },
                            onLongClick = {
                                selectedIds = if (isSelected) {
                                    selectedIds - song.id
                                } else {
                                    selectedIds + song.id
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation
    if (showDeleteConfirm) {
        val count = selectedIds.size
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Remove Songs") },
            text = {
                Text("Remove $count song${if (count > 1) "s" else ""} from this playlist? Files will be deleted.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDelete = songs.filter { it.id in selectedIds }
                        mainViewModel.deleteSongs(toDelete)
                        clearSelection()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    // Copy-to-playlist picker
    if (showCopyToPicker) {
        val otherPlaylists = allPlaylists.filter { it.id != playlistId }
        AlertDialog(
            onDismissRequest = { showCopyToPicker = false },
            title = { Text("Add to Playlist") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showCopyToPicker = false
                            showNewPlaylistName = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("New Playlist", modifier = Modifier.fillMaxWidth())
                    }
                    otherPlaylists.forEach { pl ->
                        TextButton(
                            onClick = {
                                mainViewModel.copySongsToPlaylist(
                                    selectedIds.toList(), pl.id
                                )
                                clearSelection()
                                showCopyToPicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(pl.name, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCopyToPicker = false }) { Text("Cancel") }
            }
        )
    }

    // New playlist name for copy
    if (showNewPlaylistName) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewPlaylistName = false },
            title = { Text("New Playlist Name") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Playlist name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (name.isNotBlank()) {
                            mainViewModel.copySongsToNewPlaylist(
                                selectedIds.toList(), name.trim()
                            )
                            clearSelection()
                            showNewPlaylistName = false
                        }
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewPlaylistName = false }) { Text("Cancel") }
            }
        )
    }

    // File picker launchers
    val context = LocalContext.current
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            showAddSongDialog = false
            mainViewModel.importLocalFiles(playlistId, uris, isFolder = false, context = context)
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            showAddSongDialog = false
            mainViewModel.importLocalFiles(playlistId, listOf(uri), isFolder = true, context = context)
        }
    }

    // Add song dialog
    if (showAddSongDialog) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {
                showAddSongDialog = false
                mainViewModel.resetAddSongState()
            },
            title = { Text("Add Song") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Import from storage section
                    Text(
                        "From device storage",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                filePickerLauncher.launch(arrayOf("audio/*"))
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !addSongState.isLoading
                        ) {
                            Icon(Icons.Default.MusicNote, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Files")
                        }
                        OutlinedButton(
                            onClick = {
                                folderPickerLauncher.launch(null)
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !addSongState.isLoading
                        ) {
                            Icon(Icons.Default.Folder, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Folder")
                        }
                    }

                    HorizontalDivider()

                    // Search section
                    Text(
                        "Search online",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("YouTube URL or song search") },
                        placeholder = { Text("e.g. Bohemian Rhapsody Queen") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !addSongState.isLoading
                    )

                    addSongState.progress?.let { progress ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val (icon, text) = when (progress.status) {
                                MusicRepository.DownloadStatus.SEARCHING ->
                                    Icons.Default.Search to "Searching SoundCloud..."
                                MusicRepository.DownloadStatus.SEARCHING_BANDCAMP ->
                                    Icons.Default.Search to "Searching Bandcamp..."
                                MusicRepository.DownloadStatus.DOWNLOADING ->
                                    Icons.Default.Download to "Downloading..."
                                MusicRepository.DownloadStatus.DONE ->
                                    Icons.Default.CheckCircle to "Added!"
                                MusicRepository.DownloadStatus.FAILED ->
                                    Icons.Default.Error to "Download failed"
                                MusicRepository.DownloadStatus.NOT_FOUND ->
                                    Icons.Default.SearchOff to "Not found"
                            }
                            Icon(icon, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(text, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    if (addSongState.isLoading && addSongState.progress == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Resolving...", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    addSongState.error?.let { error ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                error,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    if (addSongState.completed) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Text(
                                "Song added to playlist!",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (!addSongState.completed) {
                    TextButton(
                        onClick = {
                            mainViewModel.addSongToPlaylist(playlistId, input.trim())
                        },
                        enabled = input.isNotBlank() && !addSongState.isLoading
                    ) { Text("Search") }
                } else {
                    TextButton(
                        onClick = {
                            input = ""
                            mainViewModel.resetAddSongState()
                        }
                    ) { Text("Add Another") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddSongDialog = false
                    mainViewModel.resetAddSongState()
                }) { Text(if (addSongState.completed) "Done" else "Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongListItem(
    song: SongEntity,
    index: Int,
    isSelected: Boolean,
    inSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surface
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (inSelectionMode) {
            Icon(
                if (isSelected) Icons.Default.CheckCircle else Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            )
            Spacer(Modifier.width(12.dp))
        } else {
            Text(
                "$index",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

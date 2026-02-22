package com.musicdownloader.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.musicdownloader.app.data.db.PlaylistEntity
import com.musicdownloader.app.ui.viewmodel.MainViewModel
import com.musicdownloader.app.updater.AppUpdater
import com.musicdownloader.app.updater.UpdateInfo
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onPlaylistClick: (Long) -> Unit,
    onImportClick: () -> Unit
) {
    val playlists by viewModel.playlists.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<PlaylistEntity?>(null) }
    var showDeleteDialog by remember { mutableStateOf<PlaylistEntity?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateDownloading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val appUpdater = remember { AppUpdater() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Music", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    IconButton(onClick = {
                        showUpdateDialog = true
                        updateChecking = true
                        updateError = null
                        updateInfo = null
                        scope.launch {
                            try {
                                updateInfo = appUpdater.checkForUpdate()
                            } catch (e: Exception) {
                                updateError = e.message
                            }
                            updateChecking = false
                        }
                    }) {
                        Icon(Icons.Default.SystemUpdate, "Check for updates")
                    }
                }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                SmallFloatingActionButton(
                    onClick = { showCreateDialog = true },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Icon(Icons.Default.CreateNewFolder, "New playlist")
                }
                Spacer(Modifier.height(12.dp))
                FloatingActionButton(
                    onClick = onImportClick,
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Download, "Import from Spotify")
                }
            }
        }
    ) { padding ->
        if (playlists.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.LibraryMusic,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No playlists yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Import a Spotify playlist to get started",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistCard(
                        playlist = playlist,
                        onClick = { onPlaylistClick(playlist.id) },
                        onRename = { showRenameDialog = playlist },
                        onDelete = { showDeleteDialog = playlist }
                    )
                }
            }
        }
    }

    // Create playlist dialog
    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New Playlist") },
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
                            viewModel.createEmptyPlaylist(name.trim())
                            showCreateDialog = false
                        }
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Rename dialog
    showRenameDialog?.let { playlist ->
        var name by remember { mutableStateOf(playlist.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("Rename Playlist") },
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
                            viewModel.renamePlaylist(playlist.id, name.trim())
                            showRenameDialog = null
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) { Text("Cancel") }
            }
        )
    }

    // Update dialog
    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!updateDownloading) {
                    showUpdateDialog = false
                }
            },
            title = { Text("App Update") },
            text = {
                Column {
                    when {
                        updateChecking -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("Checking for updates...")
                            }
                        }
                        updateError != null -> {
                            Text("Error: $updateError")
                        }
                        updateDownloading -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("Downloading update...")
                            }
                        }
                        updateInfo != null -> {
                            val info = updateInfo!!
                            Text("Current version: ${info.currentVersion}")
                            Spacer(Modifier.height(4.dp))
                            Text("Latest version: ${info.version}")
                            Spacer(Modifier.height(8.dp))
                            if (info.hasUpdate) {
                                Text(
                                    "A new version is available!",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text("You're up to date.")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (updateInfo?.hasUpdate == true && !updateDownloading) {
                    Button(onClick = {
                        updateDownloading = true
                        scope.launch {
                            try {
                                appUpdater.downloadAndInstall(updateInfo!!.downloadUrl, context)
                            } catch (e: Exception) {
                                updateError = e.message
                            }
                            updateDownloading = false
                        }
                    }) {
                        Text("Update")
                    }
                }
            },
            dismissButton = {
                if (!updateDownloading) {
                    TextButton(onClick = { showUpdateDialog = false }) {
                        Text("Close")
                    }
                }
            }
        )
    }

    // Delete dialog
    showDeleteDialog?.let { playlist ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Playlist") },
            text = {
                Text("Delete \"${playlist.name}\" and all its songs? This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePlaylist(playlist.id)
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PlaylistCard(
    playlist: PlaylistEntity,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (playlist.spotifyUrl != null) {
                    Text(
                        "Imported from Spotify",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, "Options")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = { showMenu = false; onRename() },
                        leadingIcon = { Icon(Icons.Default.Edit, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { showMenu = false; onDelete() },
                        leadingIcon = { Icon(Icons.Default.Delete, null) }
                    )
                }
            }
        }
    }
}

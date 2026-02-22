package com.musicdownloader.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import com.musicdownloader.app.data.repository.MusicRepository
import com.musicdownloader.app.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onComplete: () -> Unit
) {
    val importState by viewModel.importState.collectAsState()
    val existingPlaylists by viewModel.playlists.collectAsState()

    var playlistUrl by remember { mutableStateOf("") }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showApiSetup by remember { mutableStateOf(false) }
    var apiConfigured by remember { mutableStateOf(viewModel.isSpotifyApiConfigured()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import from Spotify") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                "Paste a Spotify playlist link below to import tracks.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = playlistUrl,
                onValueChange = { playlistUrl = it },
                label = { Text("Spotify Playlist URL") },
                placeholder = { Text("https://open.spotify.com/playlist/...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !importState.isLoading && !importState.isDownloading
            )

            if (importState.tracks == null && !importState.isLoading) {
                Button(
                    onClick = { viewModel.fetchPlaylist(playlistUrl.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = playlistUrl.contains("spotify.com/playlist/")
                ) {
                    Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Fetch Playlist")
                }
            }

            if (importState.isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Fetching playlist from Spotify...")
                }
            }

            importState.error?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(modifier = Modifier.padding(16.dp)) {
                        Icon(
                            Icons.Default.Error, null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            importState.apiWarning?.let { warning ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                    )
                ) {
                    Row(modifier = Modifier.padding(12.dp)) {
                        Icon(
                            Icons.Default.Error, null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            importState.tracks?.let { tracks ->
                if (!importState.isDownloading && !importState.completed) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                importState.playlistName ?: "Playlist",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "${tracks.size} tracks found",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    // Spotify API credentials prompt
                    if (tracks.size >= 30 && !apiConfigured) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "This playlist may have more tracks",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Spotify only shows ~30 tracks without API access. " +
                                        "If you have Spotify Premium, set up a developer app to fetch all tracks. " +
                                        "Otherwise, use \"Add to Existing Playlist\" to append more batches.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = { showApiSetup = true }) {
                                    Text("Set up Spotify API")
                                }
                            }
                        }
                    } else if (apiConfigured) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Spotify API connected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                viewModel.clearSpotifyApi()
                                apiConfigured = false
                            }) {
                                Text("Disconnect", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    Button(
                        onClick = { viewModel.startDownload(playlistUrl.trim()) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Download as New Playlist")
                    }

                    if (existingPlaylists.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { showPlaylistPicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Add to Existing Playlist")
                        }
                    }
                }
            }

            if (importState.isDownloading) {
                importState.downloadProgress?.let { progress ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Downloading: ${progress.currentTrack}/${progress.totalTracks}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = {
                                    progress.currentTrack.toFloat() / progress.totalTracks
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val (icon, statusText) = when (progress.status) {
                                    MusicRepository.DownloadStatus.SEARCHING ->
                                        Icons.Default.Search to "Searching SoundCloud..."
                                    MusicRepository.DownloadStatus.SEARCHING_BANDCAMP ->
                                        Icons.Default.Search to "Searching Bandcamp..."
                                    MusicRepository.DownloadStatus.DOWNLOADING -> {
                                        val src = when (progress.source) {
                                            MusicRepository.TrackSource.BANDCAMP -> " from Bandcamp"
                                            else -> ""
                                        }
                                        Icons.Default.Download to "Downloading$src..."
                                    }
                                    MusicRepository.DownloadStatus.DONE ->
                                        Icons.Default.CheckCircle to "Done"
                                    MusicRepository.DownloadStatus.FAILED ->
                                        Icons.Default.Error to "Failed"
                                    MusicRepository.DownloadStatus.NOT_FOUND ->
                                        Icons.Default.SearchOff to "Not found"
                                }
                                Icon(icon, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${progress.trackName} - $statusText",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            if (importState.completed) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Download Complete!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        importState.downloadSummary?.let { summary ->
                            val total = summary.soundCloudCount + summary.bandcampCount
                            Text(
                                "$total tracks downloaded",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(4.dp))
                            if (summary.soundCloudCount > 0) {
                                Text(
                                    "SoundCloud: ${summary.soundCloudCount}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (summary.bandcampCount > 0) {
                                Text(
                                    "Bandcamp: ${summary.bandcampCount}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (summary.notFoundCount > 0) {
                                Text(
                                    "Not found: ${summary.notFoundCount}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        } ?: Text(
                            "Your playlist is ready to play",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Button(
                    onClick = onComplete,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Go to Playlists")
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showApiSetup) {
        var clientId by remember { mutableStateOf("") }
        var clientSecret by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showApiSetup = false },
            title = { Text("Spotify API Setup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Requires Spotify Premium. Create a free app at developer.spotify.com and paste your credentials below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = clientId,
                        onValueChange = { clientId = it },
                        label = { Text("Client ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = clientSecret,
                        onValueChange = { clientSecret = it },
                        label = { Text("Client Secret") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.configureSpotifyApi(clientId, clientSecret)
                        apiConfigured = true
                        showApiSetup = false
                        // Re-fetch playlist with API to get all tracks
                        if (playlistUrl.isNotBlank()) {
                            viewModel.fetchPlaylist(playlistUrl.trim())
                        }
                    },
                    enabled = clientId.isNotBlank() && clientSecret.isNotBlank()
                ) {
                    Text("Save & Re-fetch")
                }
            },
            dismissButton = {
                TextButton(onClick = { showApiSetup = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showPlaylistPicker) {
        AlertDialog(
            onDismissRequest = { showPlaylistPicker = false },
            title = { Text("Add to Playlist") },
            text = {
                Column {
                    existingPlaylists.forEach { playlist ->
                        TextButton(
                            onClick = {
                                showPlaylistPicker = false
                                viewModel.startAppendDownload(playlist.id)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                playlist.name,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPlaylistPicker = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

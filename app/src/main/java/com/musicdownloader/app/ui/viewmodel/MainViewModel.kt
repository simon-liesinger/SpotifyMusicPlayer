package com.musicdownloader.app.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.musicdownloader.app.data.api.TrackInfo
import com.musicdownloader.app.data.db.PlaylistEntity
import com.musicdownloader.app.data.db.PlaylistWithSongs
import com.musicdownloader.app.data.db.SongEntity
import com.musicdownloader.app.data.repository.MusicRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ImportState(
    val isLoading: Boolean = false,
    val playlistName: String? = null,
    val tracks: List<TrackInfo>? = null,
    val isDownloading: Boolean = false,
    val downloadProgress: MusicRepository.DownloadProgress? = null,
    val error: String? = null,
    val apiWarning: String? = null,
    val completed: Boolean = false,
    val downloadSummary: MusicRepository.DownloadSummary? = null
)

data class AddSongState(
    val isLoading: Boolean = false,
    val progress: MusicRepository.DownloadProgress? = null,
    val error: String? = null,
    val completed: Boolean = false
)

class MainViewModel : ViewModel() {
    private val repository = MusicRepository()

    val playlists: StateFlow<List<PlaylistEntity>> = repository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _importState = MutableStateFlow(ImportState())
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    private val _addSongState = MutableStateFlow(AddSongState())
    val addSongState: StateFlow<AddSongState> = _addSongState.asStateFlow()

    fun getPlaylistWithSongs(id: Long): Flow<PlaylistWithSongs?> =
        repository.getPlaylistWithSongs(id)

    fun fetchPlaylist(playlistUrl: String) {
        viewModelScope.launch {
            _importState.value = ImportState(isLoading = true)
            try {
                val (name, tracks) = repository.fetchSpotifyPlaylist(playlistUrl)
                val apiError = repository.spotifyScraper.lastApiError
                _importState.value = ImportState(
                    playlistName = name,
                    tracks = tracks,
                    apiWarning = apiError?.let { "Spotify API failed: $it. Showing scraped results (~30 tracks)." }
                )
            } catch (e: Exception) {
                _importState.value = ImportState(
                    error = e.message ?: "Failed to fetch playlist"
                )
            }
        }
    }

    fun startDownload(playlistUrl: String) {
        val state = _importState.value
        val name = state.playlistName ?: return
        val tracks = state.tracks ?: return

        viewModelScope.launch {
            _importState.value = state.copy(isDownloading = true, error = null)
            try {
                val (_, summary) = repository.downloadAndSavePlaylist(name, playlistUrl, tracks) { progress ->
                    _importState.value = _importState.value.copy(downloadProgress = progress)
                }
                _importState.value = _importState.value.copy(
                    isDownloading = false,
                    completed = true,
                    downloadSummary = summary
                )
            } catch (e: Exception) {
                _importState.value = _importState.value.copy(
                    isDownloading = false,
                    error = e.message ?: "Download failed"
                )
            }
        }
    }

    fun startAppendDownload(targetPlaylistId: Long) {
        val state = _importState.value
        val tracks = state.tracks ?: return

        viewModelScope.launch {
            _importState.value = state.copy(isDownloading = true, error = null)
            try {
                val summary = repository.appendToPlaylist(targetPlaylistId, tracks) { progress ->
                    _importState.value = _importState.value.copy(downloadProgress = progress)
                }
                _importState.value = _importState.value.copy(
                    isDownloading = false,
                    completed = true,
                    downloadSummary = summary
                )
            } catch (e: Exception) {
                _importState.value = _importState.value.copy(
                    isDownloading = false,
                    error = e.message ?: "Download failed"
                )
            }
        }
    }

    fun addSongToPlaylist(playlistId: Long, input: String) {
        viewModelScope.launch {
            _addSongState.value = AddSongState(isLoading = true)
            try {
                val isYouTube = input.contains("youtube.com/") || input.contains("youtu.be/")
                val searchQuery = if (isYouTube) {
                    repository.extractYouTubeTitle(input)
                } else {
                    input.trim()
                }

                repository.downloadSingleTrack(playlistId, searchQuery, searchQuery) { progress ->
                    _addSongState.value = _addSongState.value.copy(progress = progress)
                }
                _addSongState.value = AddSongState(completed = true)
            } catch (e: Exception) {
                _addSongState.value = AddSongState(
                    error = e.message ?: "Failed to add song"
                )
            }
        }
    }

    fun importLocalFiles(playlistId: Long, uris: List<Uri>, isFolder: Boolean, context: Context) {
        viewModelScope.launch {
            _addSongState.value = AddSongState(isLoading = true)
            try {
                repository.importLocalFiles(playlistId, uris, isFolder, context) { progress ->
                    _addSongState.value = _addSongState.value.copy(progress = progress)
                }
                _addSongState.value = AddSongState(completed = true)
            } catch (e: Exception) {
                _addSongState.value = AddSongState(
                    error = e.message ?: "Failed to import files"
                )
            }
        }
    }

    fun resetAddSongState() {
        _addSongState.value = AddSongState()
    }

    fun copySongsToPlaylist(songIds: List<Long>, targetPlaylistId: Long) {
        viewModelScope.launch {
            try {
                repository.copySongsToPlaylist(songIds, targetPlaylistId)
            } catch (_: Exception) { }
        }
    }

    fun copySongsToNewPlaylist(songIds: List<Long>, playlistName: String) {
        viewModelScope.launch {
            try {
                val newId = repository.createPlaylist(playlistName)
                repository.copySongsToPlaylist(songIds, newId)
            } catch (_: Exception) { }
        }
    }

    fun deleteSongs(songs: List<SongEntity>) {
        viewModelScope.launch { repository.deleteSongs(songs) }
    }

    fun isSpotifyApiConfigured(): Boolean =
        repository.spotifyScraper.spotifyApi.isConfigured()

    fun configureSpotifyApi(clientId: String, clientSecret: String) {
        repository.spotifyScraper.spotifyApi.configure(clientId, clientSecret)
    }

    fun clearSpotifyApi() {
        repository.spotifyScraper.spotifyApi.clearCredentials()
    }

    fun resetImportState() {
        _importState.value = ImportState()
    }

    fun createEmptyPlaylist(name: String) {
        viewModelScope.launch { repository.createPlaylist(name) }
    }

    fun renamePlaylist(id: Long, newName: String) {
        viewModelScope.launch { repository.renamePlaylist(id, newName) }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch { repository.deletePlaylist(id) }
    }

    fun deleteSong(song: SongEntity) {
        viewModelScope.launch { repository.deleteSong(song) }
    }
}

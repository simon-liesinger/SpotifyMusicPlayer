package com.musicdownloader.app.ui.viewmodel

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.musicdownloader.app.MusicApp
import com.musicdownloader.app.data.db.SongEntity
import com.musicdownloader.app.player.MusicPlayerService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerState(
    val isPlaying: Boolean = false,
    val currentTitle: String = "",
    val currentArtist: String = "",
    val currentArtworkUrl: String? = null,
    val duration: Long = 0L,
    val position: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatEnabled: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val isActive: Boolean = false,
    val normalizeEnabled: Boolean = false
)

/**
 * ViewModel that connects to the MusicPlayerService via MediaController.
 *
 * MediaController communicates with MediaSession in the service, which provides:
 * - Bluetooth AVRCP control (play/pause/skip from headphones)
 * - Lock screen / notification controls
 * - Audio focus management
 * - Loudness normalization
 */
class PlayerViewModel : ViewModel() {
    private var mediaController: MediaController? = null
    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    // Store current playlist's songs for loudness lookup
    private var currentSongs: List<SongEntity> = emptyList()

    init {
        connectToService()
    }

    private fun connectToService() {
        val context = MusicApp.instance
        val sessionToken = SessionToken(
            context,
            ComponentName(context, MusicPlayerService::class.java)
        )

        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener({
            try {
                mediaController = controllerFuture.get()
                setupPlayerListener()
                startPositionUpdates()
            } catch (_: Exception) {
                // Service not available yet, will retry on next action
            }
        }, MoreExecutors.directExecutor())
    }

    private fun setupPlayerListener() {
        mediaController?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = updateState()
            override fun onMediaMetadataChanged(metadata: MediaMetadata) = updateState()
            override fun onPlaybackStateChanged(playbackState: Int) = updateState()
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = updateState()
            override fun onRepeatModeChanged(repeatMode: Int) = updateState()

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateState()
                // Send loudness data for the new track to the service
                if (_playerState.value.normalizeEnabled) {
                    sendCurrentTrackLoudness()
                }
            }
        })
    }

    private fun startPositionUpdates() {
        viewModelScope.launch {
            while (true) {
                delay(500)
                val controller = mediaController ?: continue
                if (controller.isPlaying) {
                    _playerState.value = _playerState.value.copy(
                        position = controller.currentPosition
                    )
                }
            }
        }
    }

    private fun updateState() {
        val controller = mediaController ?: return
        val metadata = controller.mediaMetadata
        _playerState.value = _playerState.value.copy(
            isPlaying = controller.isPlaying,
            currentTitle = metadata.title?.toString() ?: "",
            currentArtist = metadata.artist?.toString() ?: "",
            currentArtworkUrl = metadata.artworkUri?.toString(),
            duration = controller.duration.coerceAtLeast(0),
            position = controller.currentPosition.coerceAtLeast(0),
            shuffleEnabled = controller.shuffleModeEnabled,
            repeatEnabled = controller.repeatMode == Player.REPEAT_MODE_ALL,
            hasNext = controller.hasNextMediaItem() || controller.repeatMode == Player.REPEAT_MODE_ALL,
            hasPrevious = controller.hasPreviousMediaItem(),
            isActive = controller.mediaItemCount > 0
        )
    }

    /**
     * Load a list of songs into the player and start playback.
     * @param songs List of songs to play
     * @param startIndex Which song to start playing (0-based)
     * @param shuffle Whether to enable shuffle mode
     */
    fun playSongs(songs: List<SongEntity>, startIndex: Int = 0, shuffle: Boolean = false) {
        val controller = mediaController ?: return
        currentSongs = songs

        val mediaItems = songs.map { song ->
            MediaItem.Builder()
                .setUri(song.filePath)
                .setMediaId(song.id.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .apply {
                            song.artworkUrl?.let { setArtworkUri(Uri.parse(it)) }
                            // Store loudness in extras
                            val extras = Bundle()
                            song.loudnessDb?.let { extras.putFloat("loudnessDb", it) }
                            setExtras(extras)
                        }
                        .build()
                )
                .build()
        }

        controller.setMediaItems(mediaItems, startIndex, 0)
        controller.shuffleModeEnabled = shuffle
        controller.repeatMode = Player.REPEAT_MODE_ALL
        controller.prepare()
        controller.play()

        // Send normalization data for the starting track
        if (_playerState.value.normalizeEnabled) {
            viewModelScope.launch {
                delay(100) // Brief delay for media item to be set
                sendCurrentTrackLoudness()
            }
        }
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    fun seekTo(position: Long) {
        mediaController?.seekTo(position)
    }

    fun skipNext() {
        mediaController?.seekToNextMediaItem()
    }

    fun skipPrevious() {
        mediaController?.seekToPreviousMediaItem()
    }

    fun toggleShuffle() {
        val controller = mediaController ?: return
        controller.shuffleModeEnabled = !controller.shuffleModeEnabled
    }

    fun toggleRepeat() {
        val controller = mediaController ?: return
        controller.repeatMode = if (controller.repeatMode == Player.REPEAT_MODE_ALL) {
            Player.REPEAT_MODE_OFF
        } else {
            Player.REPEAT_MODE_ALL
        }
    }

    fun toggleNormalize() {
        val newEnabled = !_playerState.value.normalizeEnabled
        _playerState.value = _playerState.value.copy(normalizeEnabled = newEnabled)

        val controller = mediaController ?: return
        // Send enable/disable command to service
        val args = Bundle().apply { putBoolean(MusicPlayerService.KEY_ENABLED, newEnabled) }
        controller.sendCustomCommand(
            SessionCommand(MusicPlayerService.CMD_SET_NORMALIZE, Bundle.EMPTY),
            args
        )

        // If enabling, send current track's loudness
        if (newEnabled) {
            sendCurrentTrackLoudness()
        }
    }

    private fun sendCurrentTrackLoudness() {
        val controller = mediaController ?: return
        if (controller.mediaItemCount == 0) return

        val currentItem = controller.currentMediaItem ?: return

        // Look up loudness from our in-memory song list instead of MediaItem extras,
        // because extras may not survive the MediaController↔MediaSession IPC round-trip.
        val mediaId = currentItem.mediaId
        val songEntity = currentSongs.find { it.id.toString() == mediaId }
        val loudnessDb = songEntity?.loudnessDb

        if (loudnessDb != null) {
            val args = Bundle().apply { putFloat(MusicPlayerService.KEY_LOUDNESS_DB, loudnessDb) }
            controller.sendCustomCommand(
                SessionCommand(MusicPlayerService.CMD_SET_LOUDNESS, Bundle.EMPTY),
                args
            )
        } else {
            // No loudness data for this track — reset to neutral so we don't
            // carry over the previous track's attenuation/boost
            val args = Bundle().apply {
                putFloat(MusicPlayerService.KEY_LOUDNESS_DB, MusicPlayerService.TARGET_LOUDNESS_DB)
            }
            controller.sendCustomCommand(
                SessionCommand(MusicPlayerService.CMD_SET_LOUDNESS, Bundle.EMPTY),
                args
            )
        }
    }

    override fun onCleared() {
        mediaController?.release()
        super.onCleared()
    }
}

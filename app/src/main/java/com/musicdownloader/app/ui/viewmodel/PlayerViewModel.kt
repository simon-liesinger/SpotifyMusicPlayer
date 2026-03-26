package com.musicdownloader.app.ui.viewmodel

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
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

data class QueueItem(
    val song: SongEntity,
    val sourcePlaylistName: String
)

data class BpmSettings(
    val enabled: Boolean = false,
    val minBpm: Float = 70f,
    val maxBpm: Float = 110f,
    val targetBpm: Float = 90f
)

data class PlayerState(
    val isPlaying: Boolean = false,
    val currentTitle: String = "",
    val currentArtist: String = "",
    val currentArtworkUrl: String? = null,
    val duration: Long = 0L,
    val position: Long = 0L,
    val repeatEnabled: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val isActive: Boolean = false,
    val normalizeEnabled: Boolean = true,
    val bpmSettings: BpmSettings = BpmSettings(),
    val queueSize: Int = 0,
    val queueIndex: Int = -1
)

/**
 * ViewModel that connects to the MusicPlayerService via MediaController.
 *
 * Manages a global queue that can pull from multiple playlists.
 * Songs are added individually or as shuffled blocks.
 */
class PlayerViewModel : ViewModel() {
    private var mediaController: MediaController? = null
    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    // Queue items with source playlist info
    private val _queueItems = MutableStateFlow<List<QueueItem>>(emptyList())
    val queueItems: StateFlow<List<QueueItem>> = _queueItems.asStateFlow()

    // All songs in queue for loudness lookup
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
                // Sync normalization state with service on connect
                if (_playerState.value.normalizeEnabled) {
                    val args = Bundle().apply { putBoolean(MusicPlayerService.KEY_ENABLED, true) }
                    mediaController?.sendCustomCommand(
                        SessionCommand(MusicPlayerService.CMD_SET_NORMALIZE, Bundle.EMPTY),
                        args
                    )
                }
            } catch (_: Exception) {}
        }, MoreExecutors.directExecutor())
    }

    private fun setupPlayerListener() {
        mediaController?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = updateState()
            override fun onMediaMetadataChanged(metadata: MediaMetadata) = updateState()
            override fun onPlaybackStateChanged(playbackState: Int) = updateState()
            override fun onRepeatModeChanged(repeatMode: Int) = updateState()

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateState()
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
            repeatEnabled = controller.repeatMode == Player.REPEAT_MODE_ALL,
            hasNext = controller.hasNextMediaItem() || controller.repeatMode == Player.REPEAT_MODE_ALL,
            hasPrevious = controller.hasPreviousMediaItem(),
            isActive = controller.mediaItemCount > 0,
            queueSize = controller.mediaItemCount,
            queueIndex = controller.currentMediaItemIndex
        )
    }

    private fun songToMediaItem(song: SongEntity): MediaItem {
        return MediaItem.Builder()
            .setUri(song.filePath)
            .setMediaId(song.id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .apply {
                        song.artworkUrl?.let { setArtworkUri(Uri.parse(it)) }
                    }
                    .build()
            )
            .build()
    }

    // --- Queue management ---

    /**
     * Play a single song now, clearing the queue.
     */
    fun playNow(song: SongEntity, playlistName: String = "") {
        val controller = mediaController ?: return
        currentSongs = listOf(song)
        _queueItems.value = listOf(QueueItem(song, playlistName))

        controller.setMediaItems(listOf(songToMediaItem(song)), 0, 0)
        controller.shuffleModeEnabled = false
        controller.repeatMode = if (_playerState.value.repeatEnabled) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        controller.prepare()
        controller.play()

        if (_playerState.value.normalizeEnabled) {
            viewModelScope.launch {
                delay(100)
                sendCurrentTrackLoudness()
            }
        }
    }

    /**
     * Tap a song — insert at the front of the queue and play it immediately.
     */
    fun tapSong(song: SongEntity, playlistName: String = "") {
        val controller = mediaController ?: return

        if (controller.mediaItemCount == 0) {
            playNow(song, playlistName)
            return
        }

        controller.addMediaItem(0, songToMediaItem(song))
        val items = _queueItems.value.toMutableList()
        items.add(0, QueueItem(song, playlistName))
        _queueItems.value = items
        currentSongs = items.map { it.song }

        controller.seekTo(0, 0)
        if (!controller.isPlaying) controller.play()
        updateState()

        if (_playerState.value.normalizeEnabled) {
            viewModelScope.launch { delay(100); sendCurrentTrackLoudness() }
        }
    }

    /** Queue Block: append playlist in original order to the end of the queue. */
    fun queueBlock(songs: List<SongEntity>, playlistName: String) {
        if (songs.isEmpty()) return
        val controller = mediaController ?: return
        val items = songs.map { QueueItem(it, playlistName) }
        val mediaItems = songs.map { songToMediaItem(it) }

        if (controller.mediaItemCount == 0) {
            startFresh(songs, items, mediaItems, controller)
        } else {
            val end = controller.mediaItemCount
            mediaItems.forEachIndexed { i, mi -> controller.addMediaItem(end + i, mi) }
            val current = _queueItems.value.toMutableList()
            current.addAll(items)
            _queueItems.value = current
            currentSongs = current.map { it.song }
            updateState()
        }
    }

    /** Queue Shuffle: append playlist in random order to the end of the queue. */
    fun queueShuffle(songs: List<SongEntity>, playlistName: String) {
        queueBlock(songs.shuffled(), playlistName)
    }

    /** Mix Block: insert playlist in original order as a block at a random position. */
    fun mixBlock(songs: List<SongEntity>, playlistName: String) {
        if (songs.isEmpty()) return
        val controller = mediaController ?: return
        val items = songs.map { QueueItem(it, playlistName) }
        val mediaItems = songs.map { songToMediaItem(it) }

        if (controller.mediaItemCount == 0) {
            startFresh(songs, items, mediaItems, controller)
        } else {
            val insertAt = randomInsertPosition(controller)
            mediaItems.forEachIndexed { i, mi -> controller.addMediaItem(insertAt + i, mi) }
            val current = _queueItems.value.toMutableList()
            current.addAll(insertAt, items)
            _queueItems.value = current
            currentSongs = current.map { it.song }
            updateState()
        }
    }

    /** Mix Shuffle: insert each song individually at a random position in the queue. */
    fun mixShuffle(songs: List<SongEntity>, playlistName: String) {
        if (songs.isEmpty()) return
        val controller = mediaController ?: return

        if (controller.mediaItemCount == 0) {
            val shuffled = songs.shuffled()
            val items = shuffled.map { QueueItem(it, playlistName) }
            startFresh(shuffled, items, shuffled.map { songToMediaItem(it) }, controller)
            return
        }

        for (song in songs) {
            val insertAt = randomInsertPosition(controller)
            controller.addMediaItem(insertAt, songToMediaItem(song))
            val current = _queueItems.value.toMutableList()
            current.add(insertAt, QueueItem(song, playlistName))
            _queueItems.value = current
        }
        currentSongs = _queueItems.value.map { it.song }
        updateState()
    }

    private fun randomInsertPosition(controller: MediaController): Int {
        val after = controller.currentMediaItemIndex + 1
        return after + (0..(controller.mediaItemCount - after)).random()
    }

    private fun startFresh(
        songs: List<SongEntity>,
        items: List<QueueItem>,
        mediaItems: List<MediaItem>,
        controller: MediaController
    ) {
        currentSongs = songs
        _queueItems.value = items
        controller.setMediaItems(mediaItems, 0, 0)
        controller.shuffleModeEnabled = false
        controller.repeatMode = Player.REPEAT_MODE_ALL
        controller.prepare()
        controller.play()
        if (_playerState.value.normalizeEnabled) {
            viewModelScope.launch { delay(100); sendCurrentTrackLoudness() }
        }
    }

    fun removeFromQueue(index: Int) {
        val controller = mediaController ?: return
        if (index == controller.currentMediaItemIndex) return
        if (index < 0 || index >= controller.mediaItemCount) return

        controller.removeMediaItem(index)

        val items = _queueItems.value.toMutableList()
        if (index < items.size) {
            items.removeAt(index)
            _queueItems.value = items
            currentSongs = items.map { it.song }
        }
        updateState()
    }

    fun clearQueue() {
        val controller = mediaController ?: return
        controller.stop()
        controller.clearMediaItems()
        _queueItems.value = emptyList()
        currentSongs = emptyList()
        updateState()
    }

    // --- Legacy support: plays a playlist the old way ---

    fun playSongs(songs: List<SongEntity>, startIndex: Int = 0, shuffle: Boolean = false) {
        val controller = mediaController ?: return
        currentSongs = songs
        _queueItems.value = songs.map { QueueItem(it, "") }

        val mediaItems = songs.map { songToMediaItem(it) }

        controller.setMediaItems(mediaItems, startIndex, 0)
        controller.shuffleModeEnabled = shuffle
        controller.repeatMode = Player.REPEAT_MODE_ALL
        controller.prepare()
        controller.play()

        if (_playerState.value.normalizeEnabled) {
            viewModelScope.launch {
                delay(100)
                sendCurrentTrackLoudness()
            }
        }
    }

    // --- Playback controls ---

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

    fun toggleRepeat() {
        val controller = mediaController ?: return
        controller.repeatMode = if (controller.repeatMode == Player.REPEAT_MODE_ALL) {
            Player.REPEAT_MODE_OFF
        } else {
            Player.REPEAT_MODE_ALL
        }
    }

    // --- BPM ---

    fun updateBpmSettings(settings: BpmSettings) {
        _playerState.value = _playerState.value.copy(bpmSettings = settings)
        applyBpmScaling()
    }

    private fun applyBpmScaling() {
        val controller = mediaController ?: return
        val bpm = _playerState.value.bpmSettings
        if (!bpm.enabled) {
            controller.playbackParameters = PlaybackParameters(1f)
            return
        }

        val currentItem = controller.currentMediaItem ?: return
        val mediaId = currentItem.mediaId
        val song = currentSongs.find { it.id.toString() == mediaId }
        val songBpm = song?.bpm

        if (songBpm != null && songBpm > 0f) {
            val rate = bpm.targetBpm / songBpm
            controller.playbackParameters = PlaybackParameters(rate)
        } else {
            controller.playbackParameters = PlaybackParameters(1f)
        }
    }

    // --- Normalization ---

    fun toggleNormalize() {
        val newEnabled = !_playerState.value.normalizeEnabled
        _playerState.value = _playerState.value.copy(normalizeEnabled = newEnabled)

        val controller = mediaController ?: return
        val args = Bundle().apply { putBoolean(MusicPlayerService.KEY_ENABLED, newEnabled) }
        controller.sendCustomCommand(
            SessionCommand(MusicPlayerService.CMD_SET_NORMALIZE, Bundle.EMPTY),
            args
        )

        if (newEnabled) {
            sendCurrentTrackLoudness()
        }
    }

    private fun sendCurrentTrackLoudness() {
        val controller = mediaController ?: return
        if (controller.mediaItemCount == 0) return

        val currentItem = controller.currentMediaItem ?: return
        val mediaId = currentItem.mediaId
        val songEntity = currentSongs.find { it.id.toString() == mediaId }
        val loudnessDb = songEntity?.loudnessDb

        val targetLoudness = loudnessDb ?: MusicPlayerService.TARGET_LOUDNESS_DB
        val args = Bundle().apply { putFloat(MusicPlayerService.KEY_LOUDNESS_DB, targetLoudness) }
        controller.sendCustomCommand(
            SessionCommand(MusicPlayerService.CMD_SET_LOUDNESS, Bundle.EMPTY),
            args
        )
    }

    override fun onCleared() {
        mediaController?.release()
        super.onCleared()
    }
}

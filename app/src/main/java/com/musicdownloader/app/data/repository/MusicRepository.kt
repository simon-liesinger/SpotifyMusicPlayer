package com.musicdownloader.app.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.musicdownloader.app.AppSettings
import com.musicdownloader.app.MusicApp
import com.musicdownloader.app.data.api.AudioAnalyzer
import com.musicdownloader.app.data.api.BandcampApi
import com.musicdownloader.app.data.api.SoundCloudApi
import com.musicdownloader.app.data.api.SpotifyScraper
import com.musicdownloader.app.data.api.TrackInfo
import com.musicdownloader.app.data.api.YouTubeApi
import com.musicdownloader.app.data.db.PlaylistEntity
import com.musicdownloader.app.data.db.PlaylistWithSongs
import com.musicdownloader.app.data.db.SongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class MusicRepository {
    private val db = MusicApp.instance.database
    private val playlistDao = db.playlistDao()
    private val songDao = db.songDao()
    val spotifyScraper = SpotifyScraper()
    private val soundCloudApi = SoundCloudApi()
    private val bandcampApi = BandcampApi()
    private val youTubeApi = YouTubeApi()

    companion object {
        private const val MIN_DURATION_MS = 31_000L // reject free-sample previews (~30 s)
    }

    /** Returns false if the file is too short to be a real song (likely a preview sample). */
    private fun isNotSample(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return true
            ms >= MIN_DURATION_MS
        } catch (_: Exception) { true } finally { retriever.release() }
    }

    // Background scope for non-blocking analysis tasks
    private val analysisScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun getAllPlaylists(): Flow<List<PlaylistEntity>> = playlistDao.getAllPlaylists()

    fun getPlaylistWithSongs(id: Long): Flow<PlaylistWithSongs?> =
        playlistDao.getPlaylistWithSongs(id)

    suspend fun getPlaylistWithSongsOnce(id: Long): PlaylistWithSongs? =
        playlistDao.getPlaylistWithSongsOnce(id)

    suspend fun createPlaylist(name: String, spotifyUrl: String? = null): Long {
        return playlistDao.insert(PlaylistEntity(name = name, spotifyUrl = spotifyUrl))
    }

    suspend fun renamePlaylist(id: Long, newName: String) {
        val playlist = playlistDao.getPlaylistWithSongsOnce(id)?.playlist ?: return
        playlistDao.update(playlist.copy(name = newName))
    }

    suspend fun deletePlaylist(id: Long) {
        val playlistWithSongs = playlistDao.getPlaylistWithSongsOnce(id)
        playlistWithSongs?.songs?.forEach { song ->
            File(song.filePath).delete()
        }
        val musicDir = File(MusicApp.instance.filesDir, "music/$id")
        musicDir.deleteRecursively()
        playlistDao.deleteById(id)
    }

    suspend fun deleteSong(song: SongEntity) {
        File(song.filePath).delete()
        songDao.delete(song)
    }

    suspend fun fetchSpotifyPlaylist(playlistUrl: String): Pair<String, List<TrackInfo>> {
        return spotifyScraper.getPlaylistTracks(playlistUrl)
    }

    enum class TrackSource { SOUNDCLOUD, BANDCAMP, YOUTUBE }

    data class DownloadProgress(
        val currentTrack: Int,
        val totalTracks: Int,
        val trackName: String,
        val status: DownloadStatus,
        val source: TrackSource? = null
    )

    data class DownloadSummary(
        val soundCloudCount: Int = 0,
        val bandcampCount: Int = 0,
        val youTubeCount: Int = 0,
        val notFoundCount: Int = 0
    )

    enum class DownloadStatus {
        SEARCHING,
        SEARCHING_BANDCAMP,
        SEARCHING_YOUTUBE,
        DOWNLOADING,
        DONE,
        FAILED,
        NOT_FOUND
    }

    suspend fun downloadAndSavePlaylist(
        playlistName: String,
        spotifyUrl: String,
        tracks: List<TrackInfo>,
        onProgress: (DownloadProgress) -> Unit
    ): Pair<Long, DownloadSummary> {
        val playlistId = createPlaylist(playlistName, spotifyUrl)
        val summary = downloadTracksToPlaylist(playlistId, tracks, onProgress)
        return playlistId to summary
    }

    suspend fun appendToPlaylist(
        playlistId: Long,
        tracks: List<TrackInfo>,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadSummary {
        return downloadTracksToPlaylist(playlistId, tracks, onProgress)
    }

    /**
     * Download a single song to a playlist, searching SoundCloud then Bandcamp.
     * The query can be a song name + artist, or extracted from a YouTube URL.
     */
    suspend fun downloadSingleTrack(
        playlistId: Long,
        searchQuery: String,
        displayName: String,
        onProgress: (DownloadProgress) -> Unit
    ) {
        val musicDir = File(MusicApp.instance.filesDir, "music/$playlistId")
        musicDir.mkdirs()
        val startIndex = songDao.countForPlaylist(playlistId)

        withContext(Dispatchers.IO) { soundCloudApi.resolveClientId() }

        val safeFilename = displayName.replace(Regex("[^a-zA-Z0-9 \\-_]"), "").trim().take(100)
        val allowYoutube = AppSettings.get().allowYoutube
        var saved = false

        // ── SoundCloud ──────────────────────────────────────────────────────
        onProgress(DownloadProgress(1, 1, displayName, DownloadStatus.SEARCHING))
        val scTrack = soundCloudApi.searchTrack(searchQuery)
        if (scTrack != null) {
            onProgress(DownloadProgress(1, 1, displayName, DownloadStatus.DOWNLOADING, TrackSource.SOUNDCLOUD))
            val outputFile = File(musicDir, "$safeFilename.mp3")
            soundCloudApi.downloadToFile(soundCloudApi.getStreamUrl(scTrack), outputFile)
            if (isNotSample(outputFile)) {
                val id = songDao.insert(SongEntity(playlistId = playlistId, title = scTrack.title,
                    artist = scTrack.user.username, filePath = outputFile.absolutePath,
                    duration = scTrack.duration, artworkUrl = scTrack.artworkUrl, orderIndex = startIndex))
                scheduleAnalysis(id, outputFile)
                onProgress(DownloadProgress(1, 1, displayName, DownloadStatus.DONE, TrackSource.SOUNDCLOUD))
                saved = true
            } else {
                outputFile.delete()
            }
        }

        // ── Bandcamp ────────────────────────────────────────────────────────
        if (!saved) {
            onProgress(DownloadProgress(1, 1, displayName, DownloadStatus.SEARCHING_BANDCAMP))
            val bcTrack = withContext(Dispatchers.IO) { bandcampApi.searchTrack(searchQuery) }
            if (bcTrack != null) {
                onProgress(DownloadProgress(1, 1, displayName, DownloadStatus.DOWNLOADING, TrackSource.BANDCAMP))
                val outputFile = File(musicDir, "$safeFilename.mp3")
                bandcampApi.downloadToFile(bcTrack.streamUrl, outputFile)
                if (isNotSample(outputFile)) {
                    val id = songDao.insert(SongEntity(playlistId = playlistId, title = bcTrack.title,
                        artist = bcTrack.artist, filePath = outputFile.absolutePath,
                        duration = bcTrack.durationMs, artworkUrl = bcTrack.artworkUrl, orderIndex = startIndex))
                    scheduleAnalysis(id, outputFile)
                    onProgress(DownloadProgress(1, 1, displayName, DownloadStatus.DONE, TrackSource.BANDCAMP))
                    saved = true
                } else {
                    outputFile.delete()
                }
            }
        }

        // ── YouTube (optional fallback) ─────────────────────────────────────
        if (!saved && allowYoutube) {
            onProgress(DownloadProgress(1, 1, displayName, DownloadStatus.SEARCHING_YOUTUBE))
            val ytTrack = withContext(Dispatchers.IO) { youTubeApi.searchAndGetTrack(searchQuery) }
            if (ytTrack != null) {
                onProgress(DownloadProgress(1, 1, displayName, DownloadStatus.DOWNLOADING, TrackSource.YOUTUBE))
                val outputFile = File(musicDir, "$safeFilename.m4a")
                youTubeApi.downloadToFile(ytTrack.audioUrl, outputFile)
                if (isNotSample(outputFile)) {
                    val id = songDao.insert(SongEntity(playlistId = playlistId, title = ytTrack.title,
                        artist = displayName, filePath = outputFile.absolutePath,
                        duration = ytTrack.durationMs, artworkUrl = null, orderIndex = startIndex))
                    scheduleAnalysis(id, outputFile)
                    onProgress(DownloadProgress(1, 1, displayName, DownloadStatus.DONE, TrackSource.YOUTUBE))
                    saved = true
                } else {
                    outputFile.delete()
                }
            }
        }

        if (!saved) {
            onProgress(DownloadProgress(1, 1, displayName, DownloadStatus.NOT_FOUND))
        }
    }

    /**
     * Extract a video title from a YouTube URL to use as a search query.
     */
    suspend fun extractYouTubeTitle(url: String): String {
        return withContext(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: throw Exception("Empty YouTube response")

            val titlePattern = Pattern.compile("<title>(.+?)(?:\\s*-\\s*YouTube)?</title>")
            val matcher = titlePattern.matcher(html)
            if (matcher.find()) {
                matcher.group(1)!!.trim()
            } else {
                throw Exception("Could not extract title from YouTube page")
            }
        }
    }

    /**
     * Copy songs to another playlist (duplicates the files).
     */
    suspend fun copySongsToPlaylist(songIds: List<Long>, targetPlaylistId: Long) {
        val songs = songDao.getByIds(songIds)
        val musicDir = File(MusicApp.instance.filesDir, "music/$targetPlaylistId")
        musicDir.mkdirs()
        val startIndex = songDao.countForPlaylist(targetPlaylistId)

        for ((i, song) in songs.withIndex()) {
            val srcFile = File(song.filePath)
            if (!srcFile.exists()) continue

            val destFile = File(musicDir, srcFile.name)
            srcFile.copyTo(destFile, overwrite = true)

            songDao.insert(
                SongEntity(
                    playlistId = targetPlaylistId,
                    title = song.title,
                    artist = song.artist,
                    filePath = destFile.absolutePath,
                    duration = song.duration,
                    artworkUrl = song.artworkUrl,
                    orderIndex = startIndex + i
                )
            )
        }
    }

    /**
     * Delete multiple songs at once.
     */
    suspend fun deleteSongs(songs: List<SongEntity>) {
        songs.forEach { File(it.filePath).delete() }
        songDao.deleteAll(songs)
    }

    /**
     * Import audio files from device storage into a playlist.
     * Accepts individual file URIs or folder URIs (scanned recursively).
     */
    suspend fun importLocalFiles(
        playlistId: Long,
        uris: List<Uri>,
        isFolder: Boolean,
        context: Context,
        onProgress: (DownloadProgress) -> Unit
    ) {
        val musicDir = File(context.filesDir, "music/$playlistId")
        musicDir.mkdirs()
        val startIndex = songDao.countForPlaylist(playlistId)

        // Resolve all audio file URIs (expand folders recursively)
        val fileUris = if (isFolder && uris.size == 1) {
            scanFolderForAudio(uris[0], context)
        } else {
            uris
        }

        if (fileUris.isEmpty()) return

        for ((index, uri) in fileUris.withIndex()) {
            val trackNum = index + 1
            val fileName = getFileName(uri, context) ?: "Track $trackNum"
            onProgress(DownloadProgress(trackNum, fileUris.size, fileName, DownloadStatus.DOWNLOADING))

            try {
                // Extract metadata
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                } catch (_: Exception) {
                    onProgress(DownloadProgress(trackNum, fileUris.size, fileName, DownloadStatus.FAILED))
                    continue
                }

                val rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val rawArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                // Parse "Artist - Title" from filename when ID3 tags are missing
                val nameWithoutExt = fileName.substringBeforeLast(".")
                val title: String
                val artist: String
                if (rawArtist.isNullOrBlank() && nameWithoutExt.contains(" - ")) {
                    artist = nameWithoutExt.substringBefore(" - ").trim()
                    title = rawTitle ?: nameWithoutExt.substringAfter(" - ").trim()
                } else {
                    title = rawTitle ?: nameWithoutExt
                    artist = rawArtist ?: "Unknown"
                }
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val duration = durationStr?.toLongOrNull() ?: 0L
                retriever.release()

                // Copy file to internal storage
                val safeFilename = title
                    .replace(Regex("[^a-zA-Z0-9 \\-_]"), "")
                    .trim()
                    .take(100)
                val ext = fileName.substringAfterLast(".", "mp3")
                val outputFile = File(musicDir, "$safeFilename.$ext")

                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        outputFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw Exception("Cannot read file")
                }

                val songId = songDao.insert(
                    SongEntity(
                        playlistId = playlistId,
                        title = title,
                        artist = artist,
                        filePath = outputFile.absolutePath,
                        duration = duration,
                        orderIndex = startIndex + index
                    )
                )
                // Analyze in background — don't block file import
                scheduleAnalysis(songId, outputFile)

                onProgress(DownloadProgress(trackNum, fileUris.size, title, DownloadStatus.DONE))
            } catch (e: Exception) {
                onProgress(DownloadProgress(trackNum, fileUris.size, fileName, DownloadStatus.FAILED))
            }
        }
    }

    private fun scanFolderForAudio(treeUri: Uri, context: Context): List<Uri> {
        val docFile = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        return scanRecursive(docFile)
    }

    private fun scanRecursive(dir: DocumentFile): List<Uri> {
        val audioExtensions = setOf("mp3", "m4a", "aac", "ogg", "flac", "wav", "opus", "wma")
        val results = mutableListOf<Uri>()
        for (file in dir.listFiles()) {
            if (file.isDirectory) {
                results.addAll(scanRecursive(file))
            } else if (file.type?.startsWith("audio/") == true ||
                (file.name?.substringAfterLast(".")?.lowercase() in audioExtensions)) {
                results.add(file.uri)
            }
        }
        return results
    }

    private fun getFileName(uri: Uri, context: Context): String? {
        val docFile = DocumentFile.fromSingleUri(context, uri)
        return docFile?.name
    }

    /**
     * Schedule loudness + BPM analysis in background (fire-and-forget).
     * Does not block the download flow.
     */
    private fun scheduleAnalysis(songId: Long, file: File) {
        analysisScope.launch {
            try {
                val loudness = AudioAnalyzer.measureLoudness(file)
                if (loudness != null) songDao.updateLoudness(songId, loudness)
            } catch (_: Exception) {}
        }
    }

    /**
     * Analyze a single song's loudness. Call from UI to fill missing data.
     */
    suspend fun analyzeSong(song: SongEntity) {
        val file = File(song.filePath)
        if (!file.exists()) return
        if (song.loudnessDb == null) {
            val loudness = AudioAnalyzer.measureLoudness(file)
            if (loudness != null) songDao.updateLoudness(song.id, loudness)
        }
    }

    private suspend fun downloadTracksToPlaylist(
        playlistId: Long,
        tracks: List<TrackInfo>,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadSummary {
        val musicDir = File(MusicApp.instance.filesDir, "music/$playlistId")
        musicDir.mkdirs()

        val startIndex = songDao.countForPlaylist(playlistId)

        withContext(Dispatchers.IO) {
            soundCloudApi.resolveClientId()
        }

        var scCount = 0
        var bcCount = 0
        var ytCount = 0
        var notFoundCount = 0
        val allowYoutube = AppSettings.get().allowYoutube
        val safeBase = { name: String, artist: String ->
            "$name - $artist".replace(Regex("[^a-zA-Z0-9 \\-_]"), "").trim().take(100)
        }

        for ((index, track) in tracks.withIndex()) {
            val trackNum = index + 1
            var saved = false

            try {
                // ── SoundCloud ──────────────────────────────────────────────
                onProgress(DownloadProgress(trackNum, tracks.size, track.name, DownloadStatus.SEARCHING))
                val scTrack = soundCloudApi.searchTrack(track.searchQuery)
                if (scTrack != null) {
                    onProgress(DownloadProgress(trackNum, tracks.size, track.name, DownloadStatus.DOWNLOADING, TrackSource.SOUNDCLOUD))
                    val outputFile = File(musicDir, "${safeBase(track.name, track.artist)}.mp3")
                    soundCloudApi.downloadToFile(soundCloudApi.getStreamUrl(scTrack), outputFile)
                    if (isNotSample(outputFile)) {
                        val id = songDao.insert(SongEntity(playlistId = playlistId, title = track.name,
                            artist = track.artist, filePath = outputFile.absolutePath,
                            duration = track.durationMs, artworkUrl = track.artworkUrl,
                            orderIndex = startIndex + index))
                        scheduleAnalysis(id, outputFile)
                        scCount++
                        onProgress(DownloadProgress(trackNum, tracks.size, track.name, DownloadStatus.DONE, TrackSource.SOUNDCLOUD))
                        saved = true
                    } else {
                        outputFile.delete()
                    }
                }

                // ── Bandcamp ────────────────────────────────────────────────
                if (!saved) {
                    onProgress(DownloadProgress(trackNum, tracks.size, track.name, DownloadStatus.SEARCHING_BANDCAMP))
                    val bcTrack = withContext(Dispatchers.IO) { bandcampApi.searchTrack(track.searchQuery) }
                    if (bcTrack != null) {
                        onProgress(DownloadProgress(trackNum, tracks.size, track.name, DownloadStatus.DOWNLOADING, TrackSource.BANDCAMP))
                        val outputFile = File(musicDir, "${safeBase(track.name, track.artist)}.mp3")
                        bandcampApi.downloadToFile(bcTrack.streamUrl, outputFile)
                        if (isNotSample(outputFile)) {
                            val id = songDao.insert(SongEntity(playlistId = playlistId, title = track.name,
                                artist = track.artist, filePath = outputFile.absolutePath,
                                duration = track.durationMs, artworkUrl = bcTrack.artworkUrl ?: track.artworkUrl,
                                orderIndex = startIndex + index))
                            scheduleAnalysis(id, outputFile)
                            bcCount++
                            onProgress(DownloadProgress(trackNum, tracks.size, track.name, DownloadStatus.DONE, TrackSource.BANDCAMP))
                            saved = true
                        } else {
                            outputFile.delete()
                        }
                    }
                }

                // ── YouTube (optional fallback) ─────────────────────────────
                if (!saved && allowYoutube) {
                    onProgress(DownloadProgress(trackNum, tracks.size, track.name, DownloadStatus.SEARCHING_YOUTUBE))
                    val ytTrack = withContext(Dispatchers.IO) { youTubeApi.searchAndGetTrack(track.searchQuery) }
                    if (ytTrack != null) {
                        onProgress(DownloadProgress(trackNum, tracks.size, track.name, DownloadStatus.DOWNLOADING, TrackSource.YOUTUBE))
                        val outputFile = File(musicDir, "${safeBase(track.name, track.artist)}.m4a")
                        youTubeApi.downloadToFile(ytTrack.audioUrl, outputFile)
                        if (isNotSample(outputFile)) {
                            val id = songDao.insert(SongEntity(playlistId = playlistId, title = track.name,
                                artist = track.artist, filePath = outputFile.absolutePath,
                                duration = track.durationMs, artworkUrl = track.artworkUrl,
                                orderIndex = startIndex + index))
                            scheduleAnalysis(id, outputFile)
                            ytCount++
                            onProgress(DownloadProgress(trackNum, tracks.size, track.name, DownloadStatus.DONE, TrackSource.YOUTUBE))
                            saved = true
                        } else {
                            outputFile.delete()
                        }
                    }
                }

                if (!saved) {
                    notFoundCount++
                    onProgress(DownloadProgress(trackNum, tracks.size, track.name, DownloadStatus.NOT_FOUND))
                }
            } catch (e: Exception) {
                notFoundCount++
                onProgress(DownloadProgress(trackNum, tracks.size, track.name, DownloadStatus.FAILED))
            }
        }

        return DownloadSummary(scCount, bcCount, ytCount, notFoundCount)
    }
}

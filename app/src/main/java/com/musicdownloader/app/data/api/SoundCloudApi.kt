package com.musicdownloader.app.data.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

// SoundCloud API response models
data class SoundCloudSearchResult(
    val collection: List<SoundCloudTrack>
)

data class SoundCloudTrack(
    val id: Long,
    val title: String,
    val user: SoundCloudUser,
    @SerializedName("artwork_url") val artworkUrl: String?,
    val duration: Long,
    val media: SoundCloudMedia?,
    // Passed alongside client_id on the stream-info endpoint, as the web player does.
    @SerializedName("track_authorization") val trackAuthorization: String? = null,
    val streamable: Boolean? = null,
    val policy: String? = null
)

data class SoundCloudUser(val username: String)

data class SoundCloudMedia(
    val transcodings: List<SoundCloudTranscoding>
)

data class SoundCloudTranscoding(
    val url: String,
    val preset: String,
    val format: SoundCloudFormat
)

data class SoundCloudFormat(
    val protocol: String,
    @SerializedName("mime_type") val mimeType: String
)

data class SoundCloudStreamInfo(val url: String?)

/** A resolved, downloadable stream. HLS streams are m3u8 playlists of MP3 segments. */
data class SoundCloudStream(val url: String, val isHls: Boolean)

private const val TAG = "SoundCloudApi"

class SoundCloudApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    private val gson = Gson()
    private var clientId: String? = null

    // Re-extracting the client_id costs several MB of JS, so only ever do it once
    // as a recovery step — restricted tracks would otherwise trigger it constantly.
    private var clientIdRefreshed = false

    private val userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    // Desktop UA needed for client_id extraction (mobile site uses different JS bundles)
    private val desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * Extract SoundCloud client_id from their JavaScript bundles.
     * Uses desktop user agent because the desktop site serves the classic
     * a-v2.sndcdn.com bundles that contain the client_id.
     */
    suspend fun resolveClientId(forceRefresh: Boolean = false): String {
        if (forceRefresh) clientId = null
        clientId?.let { return it }

        return withContext(Dispatchers.IO) {
            val homeRequest = Request.Builder()
                .url("https://soundcloud.com")
                .header("User-Agent", desktopUserAgent)
                .build()

            val homeResponse = client.newCall(homeRequest).execute()
            if (!homeResponse.isSuccessful) {
                throw IOException("Failed to fetch SoundCloud: ${homeResponse.code}")
            }

            val html = homeResponse.body?.string()
                ?: throw IOException("Empty SoundCloud response")

            // Find script URLs - desktop uses a-v2.sndcdn.com, mobile uses m.sndcdn.com
            val scriptPatterns = listOf(
                Pattern.compile("src=\"(https://a-v2\\.sndcdn\\.com/assets/[^\"]+\\.js)\""),
                Pattern.compile("src=\"(https://m\\.sndcdn\\.com/_next/static/chunks/[^\"]+\\.js)\"")
            )

            val scripts = mutableListOf<String>()
            for (sp in scriptPatterns) {
                val matcher = sp.matcher(html)
                while (matcher.find()) {
                    scripts.add(matcher.group(1)!!)
                }
                if (scripts.isNotEmpty()) break
            }

            if (scripts.isEmpty()) {
                throw IOException("No SoundCloud scripts found - site may have changed")
            }

            // client_id is usually in one of the last few script bundles
            for (scriptUrl in scripts.takeLast(5).reversed()) {
                try {
                    val scriptRequest = Request.Builder()
                        .url(scriptUrl)
                        .header("User-Agent", desktopUserAgent)
                        .build()

                    val scriptResponse = client.newCall(scriptRequest).execute()
                    if (!scriptResponse.isSuccessful) continue

                    val js = scriptResponse.body?.string() ?: continue

                    // Look for client_id patterns in the JS
                    val idPatterns = listOf(
                        Pattern.compile("client_id:\"([a-zA-Z0-9]{32})\""),
                        Pattern.compile("client_id=([a-zA-Z0-9]{32})"),
                        Pattern.compile("\"clientId\":\"([a-zA-Z0-9]{32})\""),
                        Pattern.compile("client_id:\"([a-zA-Z0-9]+)\"")
                    )

                    for (pattern in idPatterns) {
                        val idMatcher = pattern.matcher(js)
                        if (idMatcher.find()) {
                            val id = idMatcher.group(1)!!
                            clientId = id
                            return@withContext id
                        }
                    }
                } catch (_: Exception) {
                    continue
                }
            }

            throw IOException(
                "Could not extract SoundCloud client_id. " +
                "SoundCloud may have changed their JS bundle structure."
            )
        }
    }

    /**
     * Search SoundCloud and return candidates best-first, so the caller can try the
     * next one when a track turns out to be unstreamable (geo-blocked, snipped, dead
     * transcoding) instead of abandoning SoundCloud entirely.
     *
     * Only candidates that look like the *same recording* are returned. The official
     * artist upload is usually both the best match and label-blocked, and the results
     * immediately below it are typically remixes, covers, 8D edits and sped-up
     * versions — so falling through blindly would quietly save the wrong song.
     * When nothing qualifies this returns empty, letting the caller try Bandcamp
     * or YouTube instead of settling for a different recording.
     */
    suspend fun searchTracks(query: TrackQuery): List<SoundCloudTrack> {
        return withContext(Dispatchers.IO) {
            val tracks = runSearch(query.rawQuery, resolveClientId())
                ?: runSearch(query.rawQuery, resolveClientId(forceRefresh = true))
                ?: return@withContext emptyList()

            // Drop results that can never be downloaded before ranking.
            val playable = tracks.filter { it.streamable != false && it.policy != "BLOCK" }

            val accepted = playable.filter {
                TrackMatch.matches(it.title, it.user.username, it.duration, query)
            }
            Log.d(TAG, "search ${query.rawQuery} -> ${tracks.size} hits, " +
                "${playable.size} playable, ${accepted.size} matched")
            if (accepted.isEmpty()) {
                playable.take(5).forEach {
                    Log.d(TAG, "  rejected: ${it.title} / ${it.user.username} (${it.duration}ms)")
                }
            }

            val artistHint = query.artist ?: return@withContext accepted

            val (byArtist, rest) = accepted.partition {
                TrackMatch.artistMatches(it.user.username, artistHint)
            }
            byArtist + rest
        }
    }

    /** Returns null when the search request itself failed (so the caller can retry). */
    private fun runSearch(query: String, id: String): List<SoundCloudTrack>? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://api-v2.soundcloud.com/search/tracks" +
            "?q=$encodedQuery&client_id=$id&limit=10&offset=0"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            gson.fromJson(body, SoundCloudSearchResult::class.java)?.collection ?: emptyList()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Re-fetch a track from the tracks endpoint. Search results occasionally omit
     * `media.transcodings` / `track_authorization`, which are needed to stream.
     */
    private fun fetchFullTrack(trackId: Long, clientId: String): SoundCloudTrack? {
        return try {
            val request = Request.Builder()
                .url("https://api-v2.soundcloud.com/tracks/$trackId?client_id=$clientId")
                .header("User-Agent", userAgent)
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null
            gson.fromJson(response.body?.string() ?: return null, SoundCloudTrack::class.java)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Rank transcodings best-first: progressive MP3 (plain download), then HLS MP3
     * (segments concatenate into a valid MP3). Other codecs (Opus/AAC in fMP4) can't
     * be concatenated into a playable file, so they're excluded — the caller falls
     * back to another source instead of saving a broken file.
     */
    private fun usableTranscodings(track: SoundCloudTrack): List<SoundCloudTranscoding> {
        // "audio/mpegurl" is an ABR master playlist, not MP3 — exclude it explicitly.
        fun isMp3(t: SoundCloudTranscoding) =
            t.format.mimeType.substringBefore(";").trim().equals("audio/mpeg", ignoreCase = true)

        val all = track.media?.transcodings.orEmpty()
            .filter { !it.preset.contains("preview", ignoreCase = true) }
        val progressiveMp3 = all.filter { it.format.protocol == "progressive" && isMp3(it) }
        val progressive = all.filter { it.format.protocol == "progressive" } - progressiveMp3.toSet()
        val hlsMp3 = all.filter { it.format.protocol == "hls" && isMp3(it) }
        return progressiveMp3 + progressive + hlsMp3
    }

    /** Ask the stream-info endpoint for a CDN URL. Returns null on any non-200. */
    private fun requestStreamUrl(
        transcoding: SoundCloudTranscoding,
        clientId: String,
        trackAuthorization: String?
    ): String? {
        return try {
            val sep = if (transcoding.url.contains("?")) "&" else "?"
            val auth = trackAuthorization?.let {
                "&track_authorization=" + URLEncoder.encode(it, "UTF-8")
            } ?: ""
            val request = Request.Builder()
                .url("${transcoding.url}${sep}client_id=$clientId$auth")
                .header("User-Agent", userAgent)
                .header("Referer", "https://soundcloud.com/")
                .header("Origin", "https://soundcloud.com")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null
            gson.fromJson(response.body?.string() ?: return null, SoundCloudStreamInfo::class.java)
                ?.url?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolve a downloadable stream for a track, or null if it can't be streamed.
     *
     * Label-restricted uploads (typically the official artist upload, which is also
     * the best title/artist match) return 404 on every transcoding no matter what
     * credentials are sent. Returning null rather than throwing lets the caller move
     * on to the next search result, which is usually a playable re-upload.
     */
    suspend fun resolveStream(track: SoundCloudTrack): SoundCloudStream? {
        return withContext(Dispatchers.IO) {
            val id = resolveClientId()
            var resolved = track

            if (usableTranscodings(resolved).isEmpty()) {
                fetchFullTrack(track.id, id)?.let { resolved = it }
            }

            for (transcoding in usableTranscodings(resolved)) {
                val url = requestStreamUrl(transcoding, id, resolved.trackAuthorization)
                if (url != null) {
                    return@withContext SoundCloudStream(
                        url = url,
                        isHls = transcoding.format.protocol == "hls"
                    )
                }
            }

            // Every transcoding failed. Usually the track is restricted, but it can also
            // be an expired client_id — refresh once per process and retry before giving up.
            if (!clientIdRefreshed) {
                clientIdRefreshed = true
                val freshId = resolveClientId(forceRefresh = true)
                fetchFullTrack(track.id, freshId)?.let { resolved = it }
                for (transcoding in usableTranscodings(resolved)) {
                    val url = requestStreamUrl(transcoding, freshId, resolved.trackAuthorization)
                    if (url != null) {
                        return@withContext SoundCloudStream(
                            url = url,
                            isHls = transcoding.format.protocol == "hls"
                        )
                    }
                }
            }
            null
        }
    }

    /**
     * Download a resolved stream to a local file. HLS playlists are fetched
     * segment-by-segment and concatenated.
     */
    suspend fun downloadToFile(
        stream: SoundCloudStream,
        outputFile: File,
        onProgress: (bytesRead: Long, contentLength: Long) -> Unit = { _, _ -> }
    ) {
        withContext(Dispatchers.IO) {
            if (stream.isHls) {
                downloadHls(stream.url, outputFile, onProgress)
            } else {
                downloadDirect(stream.url, outputFile, onProgress)
            }
        }
    }

    private fun downloadDirect(
        url: String,
        outputFile: File,
        onProgress: (Long, Long) -> Unit
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Download failed: ${response.code}")
        }

        val body = response.body ?: throw IOException("Empty download response")
        val contentLength = body.contentLength()
        var totalBytesRead = 0L

        body.byteStream().use { input ->
            outputFile.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    onProgress(totalBytesRead, contentLength)
                }
            }
        }
    }

    private fun downloadHls(
        playlistUrl: String,
        outputFile: File,
        onProgress: (Long, Long) -> Unit
    ) {
        val playlistRequest = Request.Builder()
            .url(playlistUrl)
            .header("User-Agent", userAgent)
            .build()

        val playlistResponse = client.newCall(playlistRequest).execute()
        if (!playlistResponse.isSuccessful) {
            throw IOException("HLS playlist failed: ${playlistResponse.code}")
        }

        val segments = (playlistResponse.body?.string() ?: "")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

        if (segments.isEmpty()) throw IOException("HLS playlist contained no segments")

        var totalBytesRead = 0L
        outputFile.outputStream().use { output ->
            for ((index, segmentUrl) in segments.withIndex()) {
                val segmentRequest = Request.Builder()
                    .url(segmentUrl)
                    .header("User-Agent", userAgent)
                    .build()
                val segmentResponse = client.newCall(segmentRequest).execute()
                if (!segmentResponse.isSuccessful) {
                    throw IOException(
                        "HLS segment ${index + 1}/${segments.size} failed: ${segmentResponse.code}"
                    )
                }
                segmentResponse.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                    }
                } ?: throw IOException("Empty HLS segment ${index + 1}")
                // No Content-Length for the whole file; report progress by segment count.
                onProgress(totalBytesRead, totalBytesRead * segments.size / (index + 1))
            }
        }
    }
}

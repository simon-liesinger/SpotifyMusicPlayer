package com.musicdownloader.app.data.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class YouTubeTrack(
    val videoId: String,
    val title: String,
    val audioUrl: String,
    val durationMs: Long,
    /** Size of the audio stream, or 0 when YouTube didn't say. */
    val contentLength: Long = 0,
    /** File extension matching the container the chosen format is in. */
    val extension: String = "m4a"
)

private const val TAG = "YouTubeApi"

// googlevideo serves a bounded range per request, so the stream is pulled down in
// windows of this size.
private const val CHUNK_BYTES = 512L * 1024

class YouTubeApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    private val gson = Gson()
    private val browserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    // YouTube retired the ANDROID/ANDROID_MUSIC InnerTube clients — they now answer
    // the player endpoint with HTTP 400 "Precondition check failed" for every video.
    // The IOS client still returns adaptiveFormats with plain (un-ciphered) URLs, so
    // no signature deciphering is needed.
    private val iosClientVersion = "20.10.4"
    private val iosAgent =
        "com.google.ios.youtube/$iosClientVersion (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X)"

    /**
     * Search YouTube and return the requested recording with a direct audio stream
     * URL, or null if it isn't among the results.
     *
     * YouTube search is dominated by covers, live takes, lyric videos, 8D edits and
     * sped-up versions, so results are filtered through [TrackMatch]. Returning
     * nothing is better than silently handing back someone else's performance.
     */
    suspend fun searchAndGetTrack(query: TrackQuery): YouTubeTrack? =
        withContext(Dispatchers.IO) {
            val videoIds = searchVideoIds(query.rawQuery)
            Log.d(TAG, "search ${query.rawQuery} -> ${videoIds.size} ids")
            if (videoIds.isEmpty()) return@withContext null

            for (videoId in videoIds.take(5)) {
                val track = getAudioTrack(videoId)
                if (track == null) {
                    Log.d(TAG, "  $videoId: no playable audio format")
                    continue
                }
                // The uploader channel isn't a reliable artist field here, so match on
                // the video title alone.
                val ok = TrackMatch.matches(track.title, null, track.durationMs, query)
                Log.d(TAG, "  $videoId: ${track.title} (${track.durationMs}ms) -> ${if (ok) "MATCH" else "reject"}")
                if (ok) return@withContext track
            }
            null
        }

    private fun searchVideoIds(query: String): List<String> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url("https://www.youtube.com/results?search_query=$encoded&sp=EgIQAQ%3D%3D")
            .header("User-Agent", browserAgent)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        val html = client.newCall(request).execute().body?.string() ?: return emptyList()
        // Extract up to 5 video IDs from embedded JSON
        return Regex(""""videoId":"([a-zA-Z0-9_-]{11})"""")
            .findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .take(5)
            .toList()
    }

    private fun getAudioTrack(videoId: String): YouTubeTrack? {
        val body = """
            {"context":{"client":{"clientName":"IOS","clientVersion":"$iosClientVersion",
            "deviceMake":"Apple","deviceModel":"iPhone16,2","osName":"iPhone",
            "osVersion":"18.3.2.22D82","hl":"en"}},"videoId":"$videoId",
            "contentCheckOk":true,"racyCheckOk":true}
        """.trimIndent().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8")
            .post(body)
            .header("User-Agent", iosAgent)
            .header("X-YouTube-Client-Name", "5")
            .header("X-YouTube-Client-Version", iosClientVersion)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.w(TAG, "player endpoint HTTP ${response.code} for $videoId")
            return null
        }
        val root = try {
            gson.fromJson(response.body?.string(), JsonObject::class.java)
        } catch (_: Exception) { return null }

        val videoDetails = root.getAsJsonObject("videoDetails") ?: return null
        val title = videoDetails.getAsJsonPrimitive("title")?.asString ?: "Unknown"
        val durationSec = videoDetails.getAsJsonPrimitive("lengthSeconds")?.asLong ?: 0L

        // Pick the highest-bitrate audio-only format that has a plain url field
        val audioFormat = root.getAsJsonObject("streamingData")
            ?.getAsJsonArray("adaptiveFormats")
            ?.mapNotNull { it.asJsonObject }
            ?.filter { f ->
                f.get("url")?.isJsonNull == false &&
                (f.getAsJsonPrimitive("mimeType")?.asString?.startsWith("audio/") == true)
            }
            ?.maxByOrNull { it.getAsJsonPrimitive("bitrate")?.asInt ?: 0 }
            ?: return null

        val audioUrl = audioFormat.getAsJsonPrimitive("url")?.asString ?: return null
        val mimeType = audioFormat.getAsJsonPrimitive("mimeType")?.asString.orEmpty()

        return YouTubeTrack(
            videoId = videoId,
            title = title,
            audioUrl = audioUrl,
            durationMs = durationSec * 1000,
            contentLength = audioFormat.getAsJsonPrimitive("contentLength")?.asString
                ?.toLongOrNull() ?: 0L,
            // The highest-bitrate format is usually Opus in a WebM container, so
            // naming every download .m4a would mislabel the file on disk.
            extension = if (mimeType.startsWith("audio/webm")) "webm" else "m4a"
        )
    }

    /**
     * Download the track's audio to [outputFile].
     *
     * googlevideo answers 403 to a plain GET *and* to an open-ended `bytes=0-`, and
     * only serves a bounded range — so the stream has to be fetched as a series of
     * explicit windows rather than one continuous read.
     */
    suspend fun downloadToFile(track: YouTubeTrack, outputFile: File) = withContext(Dispatchers.IO) {
        var position = 0L
        outputFile.outputStream().use { out ->
            while (true) {
                val request = Request.Builder()
                    .url(track.audioUrl)
                    .header("User-Agent", iosAgent)
                    .header("Range", "bytes=$position-${position + CHUNK_BYTES - 1}")
                    .build()

                val written = client.newCall(request).execute().use { response ->
                    // 416 means the previous window already reached the end.
                    if (response.code == 416) return@use 0L
                    if (!response.isSuccessful) {
                        throw IOException("YouTube download failed: ${response.code}")
                    }
                    val body = response.body ?: throw IOException("Empty YouTube response")
                    body.byteStream().copyTo(out)
                }

                position += written
                if (written == 0L) break
                if (track.contentLength > 0 && position >= track.contentLength) break
                // No declared length: a short window means the stream is exhausted.
                if (track.contentLength <= 0 && written < CHUNK_BYTES) break
            }
        }
        // A truncated file must never reach the playlist: it would look like a
        // successful download and play as a song that cuts off partway through.
        if (position == 0L) throw IOException("YouTube returned no audio data")
        if (track.contentLength > 0 && position < track.contentLength) {
            throw IOException(
                "YouTube served only $position of ${track.contentLength} bytes"
            )
        }
        Log.d(TAG, "downloaded ${track.title}: $position bytes")
    }
}

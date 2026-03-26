package com.musicdownloader.app.data.api

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
    val durationMs: Long
)

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
    private val ytMusicAgent =
        "com.google.android.apps.youtube.music/5.28.1 (Linux; U; Android 11) gzip"

    /**
     * Search YouTube and return a track with a direct audio stream URL, or null.
     * If artistHint is provided, prefer results whose title contains the expected artist name.
     */
    suspend fun searchAndGetTrack(query: String, artistHint: String? = null): YouTubeTrack? =
        withContext(Dispatchers.IO) {
            val videoIds = searchVideoIds(query)
            if (videoIds.isEmpty()) return@withContext null

            if (artistHint == null) return@withContext getAudioTrack(videoIds.first())

            val primaryArtist = artistHint.split(",").first().trim().lowercase()
            // Try up to 3 results, prefer one whose title contains the expected artist,
            // but always fall back to the first fetchable result rather than returning null.
            var firstFetched: YouTubeTrack? = null
            for (videoId in videoIds.take(3)) {
                val track = getAudioTrack(videoId) ?: continue
                if (firstFetched == null) firstFetched = track
                if (track.title.lowercase().contains(primaryArtist)) return@withContext track
            }
            firstFetched
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
            {"context":{"client":{"clientName":"ANDROID_MUSIC","clientVersion":"5.28.1",
            "androidSdkVersion":30,"hl":"en"}},"videoId":"$videoId",
            "contentCheckOk":true,"racyCheckOk":true}
        """.trimIndent().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8")
            .post(body)
            .header("User-Agent", ytMusicAgent)
            .header("X-YouTube-Client-Name", "21")
            .header("X-YouTube-Client-Version", "5.28.1")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null
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

        return YouTubeTrack(
            videoId = videoId,
            title = title,
            audioUrl = audioUrl,
            durationMs = durationSec * 1000
        )
    }

    /** Download audio to an .m4a file. */
    suspend fun downloadToFile(audioUrl: String, outputFile: File) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(audioUrl)
            .header("User-Agent", ytMusicAgent)
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("YouTube download failed: ${response.code}")
        val body = response.body ?: throw IOException("Empty YouTube response")
        body.byteStream().use { input -> outputFile.outputStream().use { input.copyTo(it) } }
    }
}

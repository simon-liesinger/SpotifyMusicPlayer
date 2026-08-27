package com.musicdownloader.app.data.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class BandcampTrack(
    val title: String,
    val artist: String,
    val streamUrl: String,
    val durationMs: Long,
    val artworkUrl: String?
)

class BandcampApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    private val gson = Gson()
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * Search Bandcamp for the requested recording, or null if it isn't there.
     *
     * Bandcamp is heavy on covers, tribute albums and live sets, so results are
     * filtered through [TrackMatch] — a result that isn't the same recording is
     * skipped rather than returned as a stand-in.
     */
    suspend fun searchTrack(query: TrackQuery): BandcampTrack? {
        return withContext(Dispatchers.IO) {
            val encodedQuery = URLEncoder.encode(query.rawQuery, "UTF-8")
            val searchUrl = "https://bandcamp.com/search?q=$encodedQuery&item_type=t"

            val request = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", userAgent)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val html = response.body?.string() ?: return@withContext null

            // Extract up to 5 track URLs from search results
            val trackUrlPattern = Pattern.compile(
                """href="(https://[^"]+\.bandcamp\.com/track/[^"?]+)"""
            )
            val matcher = trackUrlPattern.matcher(html)
            val trackUrls = mutableListOf<String>()
            while (matcher.find() && trackUrls.size < 5) {
                trackUrls.add(matcher.group(1)!!)
            }

            if (trackUrls.isEmpty()) return@withContext null

            // Return the first result that is actually the requested recording.
            // No blind fallback: a non-matching result is a different song.
            for (url in trackUrls) {
                val track = extractTrackData(url) ?: continue
                if (TrackMatch.matches(track.title, track.artist, track.durationMs, query)) {
                    return@withContext track
                }
            }
            null
        }
    }

    private fun extractTrackData(trackUrl: String): BandcampTrack? {
        val request = Request.Builder()
            .url(trackUrl)
            .header("User-Agent", userAgent)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null

        val html = response.body?.string() ?: return null

        // Find data-tralbum attribute (HTML-entity-encoded JSON)
        val tralbumPattern = Pattern.compile("""data-tralbum="([^"]+)"""")
        val matcher = tralbumPattern.matcher(html)
        if (!matcher.find()) return null

        val encodedJson = matcher.group(1)!!
        val json = decodeHtmlEntities(encodedJson)

        val data: JsonObject
        try {
            data = gson.fromJson(json, JsonObject::class.java)
        } catch (_: Exception) {
            return null
        }

        val trackInfo = data.getAsJsonArray("trackinfo")
            ?.firstOrNull()?.asJsonObject ?: return null

        val file = trackInfo.getAsJsonObject("file") ?: return null
        val streamUrl = file.getAsJsonPrimitive("mp3-128")?.asString ?: return null

        val title = trackInfo.getAsJsonPrimitive("title")?.asString ?: "Unknown"
        val artist = data.getAsJsonPrimitive("artist")?.asString ?: "Unknown"
        val durationSec = trackInfo.getAsJsonPrimitive("duration")?.asDouble ?: 0.0

        // Build artwork URL from art_id
        val artId = data.getAsJsonPrimitive("art_id")?.asLong
        val artworkUrl = if (artId != null) "https://f4.bcbits.com/img/a${artId}_10.jpg" else null

        return BandcampTrack(
            title = title,
            artist = artist,
            streamUrl = streamUrl,
            durationMs = (durationSec * 1000).toLong(),
            artworkUrl = artworkUrl
        )
    }

    /**
     * Download audio from a Bandcamp stream URL to a local file.
     */
    suspend fun downloadToFile(streamUrl: String, outputFile: File) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(streamUrl)
                .header("User-Agent", userAgent)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IOException("Bandcamp download failed: ${response.code}")
            }

            val body = response.body ?: throw IOException("Empty Bandcamp response")
            body.byteStream().use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun decodeHtmlEntities(s: String): String {
        return s.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
    }
}

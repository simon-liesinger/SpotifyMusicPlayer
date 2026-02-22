package com.musicdownloader.app.data.api

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
    val media: SoundCloudMedia?
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

data class SoundCloudStreamInfo(val url: String)

class SoundCloudApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    private val gson = Gson()
    private var clientId: String? = null

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
    suspend fun resolveClientId(): String {
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
     * Search SoundCloud for a track matching the query.
     */
    suspend fun searchTrack(query: String): SoundCloudTrack? {
        return withContext(Dispatchers.IO) {
            val id = resolveClientId()
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://api-v2.soundcloud.com/search/tracks" +
                "?q=$encodedQuery&client_id=$id&limit=5&offset=0"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null
            val result = gson.fromJson(body, SoundCloudSearchResult::class.java)
            result.collection.firstOrNull()
        }
    }

    /**
     * Get the direct stream URL for a SoundCloud track.
     * Prefers progressive MP3 for direct download compatibility.
     */
    suspend fun getStreamUrl(track: SoundCloudTrack): String {
        return withContext(Dispatchers.IO) {
            val id = resolveClientId()
            val transcodings = track.media?.transcodings
                ?: throw IOException("No audio streams available for this track")

            // Prefer progressive MP3 (direct download), fall back to HLS
            val transcoding = transcodings.find {
                it.format.protocol == "progressive" && it.format.mimeType == "audio/mpeg"
            } ?: transcodings.find {
                it.format.protocol == "progressive"
            } ?: transcodings.first()

            val streamInfoUrl = "${transcoding.url}?client_id=$id"
            val request = Request.Builder()
                .url(streamInfoUrl)
                .header("User-Agent", userAgent)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IOException("Failed to get stream URL: ${response.code}")
            }

            val body = response.body?.string()
                ?: throw IOException("Empty stream info response")
            val streamInfo = gson.fromJson(body, SoundCloudStreamInfo::class.java)
            streamInfo.url
        }
    }

    /**
     * Download audio from a stream URL to a local file.
     */
    suspend fun downloadToFile(
        streamUrl: String,
        outputFile: File,
        onProgress: (bytesRead: Long, contentLength: Long) -> Unit = { _, _ -> }
    ) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(streamUrl)
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
    }
}

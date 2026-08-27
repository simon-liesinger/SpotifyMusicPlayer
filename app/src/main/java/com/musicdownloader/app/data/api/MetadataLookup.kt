package com.musicdownloader.app.data.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Turns free text ("unwritten", "unwritten natasha bedingfield") into the canonical
 * title, artist and duration of the recording the user meant.
 *
 * Playlist downloads get this metadata from Spotify and can therefore tell a cover
 * from the real track. A single-song add has only what the user typed, and covers
 * are frequently uploaded under the bare song title with no giveaway word — so
 * without a lookup the duration check has nothing to compare against and the
 * covers get through.
 *
 * Uses the iTunes Search API: public, no key, and it indexes the commercial
 * release rather than user uploads, which is exactly the reference we want.
 */
class MetadataLookup(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val gson = Gson()

    /**
     * Resolve [freeText] to a fully-specified query. Falls back to a raw-text query
     * when the song can't be identified — an unknown or very obscure track should
     * still be downloadable, just with only version-word filtering to protect it.
     */
    suspend fun resolve(freeText: String): TrackQuery = withContext(Dispatchers.IO) {
        val fallback = TrackQuery(rawQuery = freeText)
        try {
            val url = "https://itunes.apple.com/search?term=" +
                URLEncoder.encode(freeText, "UTF-8") + "&entity=song&limit=5"
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) return@withContext fallback

            val body = response.body?.string() ?: return@withContext fallback
            val results = gson.fromJson(body, JsonObject::class.java)
                ?.getAsJsonArray("results") ?: return@withContext fallback

            // If the user asked for a remix or live version, don't "correct" them to
            // the studio original — only accept a result matching their intent.
            val askedFor = TrackMatch.versionWords(freeText)

            for (element in results) {
                val item = element.asJsonObject ?: continue
                val title = item.getAsJsonPrimitive("trackName")?.asString ?: continue
                val artist = item.getAsJsonPrimitive("artistName")?.asString ?: continue
                val duration = item.getAsJsonPrimitive("trackTimeMillis")?.asLong ?: continue

                if ((TrackMatch.versionWords(title) - askedFor).isNotEmpty()) continue
                if (!plausibleFor(title, freeText)) continue

                return@withContext TrackQuery(
                    // Search by the canonical name — better hit rate than raw text.
                    rawQuery = "$title $artist",
                    title = title,
                    artist = artist,
                    durationMs = duration
                )
            }
            fallback
        } catch (_: Exception) {
            fallback
        }
    }

    /**
     * Guard against a lookup that returns something unrelated: most of the resolved
     * title's words should be in what the user typed. Without this, a bad match
     * would filter out every real result and the song would fail entirely.
     */
    private fun plausibleFor(title: String, freeText: String): Boolean {
        val typed = TrackMatch.normalize(freeText)
        val words = TrackMatch.normalize(title).split(" ").filter { it.length > 2 }
        if (words.isEmpty()) return true
        return words.count { typed.contains(it) } >= (words.size + 1) / 2
    }
}

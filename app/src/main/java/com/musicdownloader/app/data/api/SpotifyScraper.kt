package com.musicdownloader.app.data.api

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

// App-level track info extracted from Spotify
data class TrackInfo(
    val name: String,
    val artist: String,
    val durationMs: Long,
    val artworkUrl: String?,
    val searchQuery: String
)

/**
 * Scrapes Spotify playlist pages to extract track data directly from HTML.
 * No API credentials or Premium subscription needed.
 *
 * Spotify embeds playlist data in a <script id="initialState" type="text/plain"> tag
 * as base64-encoded JSON containing the full track listing.
 * The initial page load includes up to 30 tracks; for larger playlists some tracks
 * may be omitted (Spotify paginates the rest via client-side JS).
 */
class SpotifyScraper(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    private val gson = Gson()
    val spotifyApi = SpotifyApi()

    /** Last API error message, if the API was tried and failed. */
    var lastApiError: String? = null
        private set

    private val userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * Fetch a Spotify playlist and extract all track info.
     * Tries the Spotify Web API first (gets ALL tracks), falls back to HTML scraping (~30 tracks).
     */
    suspend fun getPlaylistTracks(playlistUrl: String): Pair<String, List<TrackInfo>> {
        val playlistId = extractPlaylistId(playlistUrl)
            ?: throw IllegalArgumentException("Invalid Spotify playlist URL")

        lastApiError = null

        // Try API first if configured (gets all tracks with pagination)
        if (spotifyApi.isConfigured()) {
            try {
                val result = spotifyApi.getPlaylistTracks(playlistId)
                if (result.second.isNotEmpty()) {
                    return result
                }
            } catch (e: Exception) {
                lastApiError = e.message ?: "Unknown API error"
                // Fall through to HTML scraping
            }
        }

        return scrapePlaylistFromHtml(playlistId)
    }

    private suspend fun scrapePlaylistFromHtml(playlistId: String): Pair<String, List<TrackInfo>> {
        return withContext(Dispatchers.IO) {
            val url = "https://open.spotify.com/playlist/$playlistId"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IOException("Failed to load playlist page (${response.code})")
            }

            val html = response.body?.string()
                ?: throw IOException("Empty response from Spotify")

            parsePlaylistFromHtml(html, playlistId)
        }
    }

    private fun parsePlaylistFromHtml(html: String, playlistId: String): Pair<String, List<TrackInfo>> {
        // Extract the base64 content from <script id="initialState" type="text/plain">...</script>
        // Spotify uses camelCase "initialState" (not kebab-case)
        val statePattern = Pattern.compile(
            """<script\s+id="initialState"\s+type="text/plain">\s*([A-Za-z0-9+/=\s]+?)\s*</script>""",
            Pattern.DOTALL
        )
        val stateMatcher = statePattern.matcher(html)

        if (!stateMatcher.find()) {
            // Fallback: try to extract from meta tags / og tags
            return parseFromMetaTags(html)
        }

        val base64Data = stateMatcher.group(1)!!.replace("\\s".toRegex(), "")
        val jsonStr: String
        try {
            val decoded = Base64.decode(base64Data, Base64.DEFAULT)
            jsonStr = String(decoded, Charsets.UTF_8)
        } catch (e: Exception) {
            throw IOException("Failed to decode playlist data: ${e.message}")
        }

        val root: JsonObject
        try {
            root = gson.fromJson(jsonStr, JsonObject::class.java)
        } catch (e: Exception) {
            throw IOException("Failed to parse playlist JSON: ${e.message}")
        }

        return extractTracksFromState(root, playlistId)
    }

    private fun extractTracksFromState(root: JsonObject, playlistId: String): Pair<String, List<TrackInfo>> {
        val entities = root.getAsJsonObject("entities")
            ?: throw IOException("No entities in playlist data")
        val items = entities.getAsJsonObject("items")
            ?: throw IOException("No items in playlist data")

        // Find the playlist entity - try exact key first, then search
        val playlistKey = "spotify:playlist:$playlistId"
        var playlistEntity = items.getAsJsonObject(playlistKey)

        if (playlistEntity == null) {
            // Search for any playlist key
            for ((key, value) in items.entrySet()) {
                if (key.startsWith("spotify:playlist:") && value.isJsonObject) {
                    playlistEntity = value.asJsonObject
                    break
                }
            }
        }

        if (playlistEntity == null) {
            throw IOException("Playlist not found in page data")
        }

        // Get playlist name
        val playlistName = playlistEntity.getAsJsonPrimitive("name")?.asString
            ?: playlistEntity.getAsJsonObject("content")
                ?.getAsJsonPrimitive("name")?.asString
            ?: "Playlist"

        // Get track items - try different paths
        val trackItems = findTrackItems(playlistEntity)
        if (trackItems.isEmpty()) {
            throw IOException("No tracks found in playlist data")
        }

        val tracks = mutableListOf<TrackInfo>()
        for (item in trackItems) {
            try {
                val trackInfo = extractTrackInfo(item)
                if (trackInfo != null) {
                    tracks.add(trackInfo)
                }
            } catch (_: Exception) {
                // Skip tracks we can't parse
            }
        }

        if (tracks.isEmpty()) {
            throw IOException("Could not parse any tracks from playlist")
        }

        return Pair(playlistName, tracks)
    }

    private fun findTrackItems(playlistEntity: JsonObject): List<JsonElement> {
        // Path: content.items[]
        val content = playlistEntity.getAsJsonObject("content")
        if (content != null) {
            val items = content.getAsJsonArray("items")
            if (items != null && items.size() > 0) {
                return items.toList()
            }
        }

        // Path: tracks.items[]
        val tracks = playlistEntity.getAsJsonObject("tracks")
        if (tracks != null) {
            val items = tracks.getAsJsonArray("items")
            if (items != null && items.size() > 0) {
                return items.toList()
            }
        }

        return emptyList()
    }

    private fun extractTrackInfo(item: JsonElement): TrackInfo? {
        if (!item.isJsonObject) return null
        val obj = item.asJsonObject

        // Try path: itemV2.data
        val trackData = obj.getAsJsonObject("itemV2")?.getAsJsonObject("data")
            ?: obj.getAsJsonObject("item")?.getAsJsonObject("data")
            ?: obj.getAsJsonObject("track")
            ?: obj.getAsJsonObject("data")
            ?: return null

        val name = trackData.getAsJsonPrimitive("name")?.asString ?: return null

        // Extract artist(s)
        val artist = extractArtists(trackData)
        if (artist.isBlank()) return null

        // Extract duration
        val durationMs = trackData.getAsJsonObject("duration")
            ?.getAsJsonPrimitive("totalMilliseconds")?.asLong
            ?: trackData.getAsJsonPrimitive("duration_ms")?.asLong
            ?: trackData.getAsJsonPrimitive("durationMs")?.asLong
            ?: 0L

        // Extract artwork URL
        val artworkUrl = extractArtworkUrl(trackData)

        return TrackInfo(
            name = name,
            artist = artist,
            durationMs = durationMs,
            artworkUrl = artworkUrl,
            searchQuery = "$name ${artist.split(",").first().trim()}"
        )
    }

    private fun extractArtists(trackData: JsonObject): String {
        // Path: artists.items[].profile.name
        val artistsObj = trackData.getAsJsonObject("artists")
        if (artistsObj != null) {
            val artistItems = artistsObj.getAsJsonArray("items")
            if (artistItems != null && artistItems.size() > 0) {
                return artistItems.mapNotNull { item ->
                    item.asJsonObject?.getAsJsonObject("profile")
                        ?.getAsJsonPrimitive("name")?.asString
                }.joinToString(", ")
            }
        }

        // Path: artists[] (direct array with name field)
        val artistsArray = trackData.getAsJsonArray("artists")
        if (artistsArray != null && artistsArray.size() > 0) {
            return artistsArray.mapNotNull { item ->
                item.asJsonObject?.getAsJsonPrimitive("name")?.asString
            }.joinToString(", ")
        }

        return ""
    }

    private fun extractArtworkUrl(trackData: JsonObject): String? {
        // Path: albumOfTrack.coverArt.sources[].url (prefer ~300px)
        val album = trackData.getAsJsonObject("albumOfTrack")
            ?: trackData.getAsJsonObject("album")

        if (album != null) {
            val coverArt = album.getAsJsonObject("coverArt")
            if (coverArt != null) {
                val sources = coverArt.getAsJsonArray("sources")
                if (sources != null && sources.size() > 0) {
                    // Prefer medium resolution (~300px)
                    val medium = sources.firstOrNull { src ->
                        val w = src.asJsonObject?.getAsJsonPrimitive("width")?.asInt ?: 0
                        w in 200..400
                    }
                    val chosen = medium ?: sources.first()
                    return chosen.asJsonObject?.getAsJsonPrimitive("url")?.asString
                }
            }

            // Path: album.images[].url
            val images = album.getAsJsonArray("images")
            if (images != null && images.size() > 0) {
                return images.first().asJsonObject?.getAsJsonPrimitive("url")?.asString
            }
        }

        return null
    }

    /**
     * Fallback: extract basic info from Open Graph meta tags.
     * This gives us the playlist name but NOT individual tracks.
     */
    private fun parseFromMetaTags(html: String): Pair<String, List<TrackInfo>> {
        val titlePattern = Pattern.compile("""<meta\s+property="og:title"\s+content="([^"]+)"""")
        val titleMatcher = titlePattern.matcher(html)
        val playlistName = if (titleMatcher.find()) {
            titleMatcher.group(1)!!
        } else {
            "Playlist"
        }

        // Try to find track data in any other embedded JSON
        val tracks = tryAlternateJsonParsing(html)
        if (tracks.isNotEmpty()) {
            return Pair(playlistName, tracks)
        }

        throw IOException(
            "Could not extract tracks from Spotify page. " +
            "Spotify may have changed their page structure."
        )
    }

    private fun tryAlternateJsonParsing(html: String): List<TrackInfo> {
        // Look for JSON-LD with track listing
        val ldPattern = Pattern.compile(
            """<script\s+type="application/ld\+json">\s*(\{.+?\})\s*</script>""",
            Pattern.DOTALL
        )
        val ldMatcher = ldPattern.matcher(html)

        while (ldMatcher.find()) {
            try {
                val json = gson.fromJson(ldMatcher.group(1), JsonObject::class.java)
                val trackList = json.getAsJsonArray("track") ?: continue
                return trackList.mapNotNull { element ->
                    val track = element.asJsonObject ?: return@mapNotNull null
                    val name = track.getAsJsonPrimitive("name")?.asString ?: return@mapNotNull null
                    val artist = track.getAsJsonObject("byArtist")
                        ?.getAsJsonPrimitive("name")?.asString ?: "Unknown"
                    val duration = parseDuration(
                        track.getAsJsonPrimitive("duration")?.asString
                    )
                    TrackInfo(
                        name = name,
                        artist = artist,
                        durationMs = duration,
                        artworkUrl = null,
                        searchQuery = "$name $artist"
                    )
                }
            } catch (_: Exception) {
                continue
            }
        }

        return emptyList()
    }

    /**
     * Parse ISO 8601 duration (e.g. "PT3M25S") to milliseconds.
     */
    private fun parseDuration(iso: String?): Long {
        if (iso == null) return 0L
        val pattern = Pattern.compile("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?")
        val m = pattern.matcher(iso)
        if (!m.matches()) return 0L
        val hours = m.group(1)?.toLongOrNull() ?: 0
        val minutes = m.group(2)?.toLongOrNull() ?: 0
        val seconds = m.group(3)?.toLongOrNull() ?: 0
        return (hours * 3600 + minutes * 60 + seconds) * 1000
    }

    private fun extractPlaylistId(url: String): String? {
        // Match playlist ID from various URL formats
        val regex = Regex("playlist/([a-zA-Z0-9]+)")
        return regex.find(url)?.groupValues?.get(1)
    }
}

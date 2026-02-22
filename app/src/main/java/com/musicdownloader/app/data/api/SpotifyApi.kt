package com.musicdownloader.app.data.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.musicdownloader.app.MusicApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Spotify Web API client using Client Credentials flow.
 *
 * Users provide their own Client ID and Secret (from developer.spotify.com),
 * which are cached in SharedPreferences. This allows fetching ALL tracks
 * from a playlist with pagination (the HTML scraper only gets ~30).
 */
class SpotifyApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        private const val API_BASE = "https://api.spotify.com/v1"
        private const val PREFS_NAME = "spotify_api_prefs"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_CLIENT_SECRET = "client_secret"
    }

    private val gson = Gson()
    private var accessToken: String? = null
    private var tokenExpiresAt: Long = 0L

    private val prefs by lazy {
        MusicApp.instance.getSharedPreferences(PREFS_NAME, 0)
    }

    fun isConfigured(): Boolean {
        val id = prefs.getString(KEY_CLIENT_ID, null)
        val secret = prefs.getString(KEY_CLIENT_SECRET, null)
        return !id.isNullOrBlank() && !secret.isNullOrBlank()
    }

    fun configure(clientId: String, clientSecret: String) {
        prefs.edit()
            .putString(KEY_CLIENT_ID, clientId.trim())
            .putString(KEY_CLIENT_SECRET, clientSecret.trim())
            .apply()
        // Invalidate cached token since credentials changed
        accessToken = null
        tokenExpiresAt = 0L
    }

    fun clearCredentials() {
        prefs.edit().clear().apply()
        accessToken = null
        tokenExpiresAt = 0L
    }

    private suspend fun getAccessToken(): String {
        accessToken?.let { token ->
            if (System.currentTimeMillis() < tokenExpiresAt - 60_000) {
                return token
            }
        }

        return withContext(Dispatchers.IO) {
            val clientId = prefs.getString(KEY_CLIENT_ID, null)
                ?: throw IOException("Spotify API not configured")
            val clientSecret = prefs.getString(KEY_CLIENT_SECRET, null)
                ?: throw IOException("Spotify API not configured")

            val body = "grant_type=client_credentials"
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())

            val request = Request.Builder()
                .url(TOKEN_URL)
                .post(body)
                .header("Authorization", Credentials.basic(clientId, clientSecret))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IOException("Spotify auth failed (${response.code})")
            }

            val json = response.body?.string()
                ?: throw IOException("Empty auth response")
            val tokenResponse = gson.fromJson(json, TokenResponse::class.java)

            accessToken = tokenResponse.accessToken
            tokenExpiresAt = System.currentTimeMillis() + (tokenResponse.expiresIn * 1000L)
            tokenResponse.accessToken
        }
    }

    suspend fun getPlaylistTracks(playlistId: String): Pair<String, List<TrackInfo>> {
        return withContext(Dispatchers.IO) {
            val token = getAccessToken()

            val fields = "name,tracks(items(track(name,artists(name),duration_ms,album(images))),next,total)"
            val url = "$API_BASE/playlists/$playlistId?fields=$fields"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IOException("Spotify API error (${response.code})")
            }

            val json = response.body?.string()
                ?: throw IOException("Empty API response")
            val playlist = gson.fromJson(json, PlaylistResponse::class.java)

            val allTracks = mutableListOf<TrackInfo>()
            allTracks.addAll(playlist.tracks.items.mapNotNull { it.toTrackInfo() })

            // Paginate until all tracks are fetched
            var nextUrl = playlist.tracks.next
            while (nextUrl != null) {
                val pageRequest = Request.Builder()
                    .url(nextUrl)
                    .header("Authorization", "Bearer $token")
                    .build()

                val pageResponse = client.newCall(pageRequest).execute()
                if (!pageResponse.isSuccessful) break

                val pageJson = pageResponse.body?.string() ?: break
                val page = gson.fromJson(pageJson, TracksPage::class.java)
                allTracks.addAll(page.items.mapNotNull { it.toTrackInfo() })
                nextUrl = page.next
            }

            Pair(playlist.name, allTracks)
        }
    }

    private fun PlaylistItem.toTrackInfo(): TrackInfo? {
        val track = this.track ?: return null

        val artist = track.artists.joinToString(", ") { it.name }
        if (artist.isBlank()) return null

        val artworkUrl = track.album?.images
            ?.sortedBy { it.width ?: 0 }
            ?.firstOrNull { (it.width ?: 0) >= 200 }?.url
            ?: track.album?.images?.lastOrNull()?.url

        return TrackInfo(
            name = track.name,
            artist = artist,
            durationMs = track.durationMs,
            artworkUrl = artworkUrl,
            searchQuery = "${track.name} ${track.artists.first().name}"
        )
    }
}

// Spotify API response models
private data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("expires_in") val expiresIn: Int
)

private data class PlaylistResponse(
    val name: String,
    val tracks: TracksPage
)

private data class TracksPage(
    val items: List<PlaylistItem>,
    val next: String?,
    val total: Int
)

private data class PlaylistItem(
    val track: SpotifyTrack?
)

private data class SpotifyTrack(
    val name: String,
    val artists: List<SpotifyArtist>,
    @SerializedName("duration_ms") val durationMs: Long,
    val album: SpotifyAlbum?
)

private data class SpotifyArtist(val name: String)

private data class SpotifyAlbum(val images: List<SpotifyImage>?)

private data class SpotifyImage(val url: String, val width: Int?, val height: Int?)

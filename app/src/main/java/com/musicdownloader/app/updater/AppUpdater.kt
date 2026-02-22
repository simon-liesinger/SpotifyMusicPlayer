package com.musicdownloader.app.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.musicdownloader.app.MusicApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val version: String,
    val downloadUrl: String,
    val currentVersion: String,
    val hasUpdate: Boolean
)

class AppUpdater(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    companion object {
        const val GITHUB_OWNER = "simon-liesinger"
        const val GITHUB_REPO = "SpotifyMusicPlayer"
        private const val API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    }

    private val gson = Gson()

    fun getCurrentVersion(): String {
        return try {
            val context = MusicApp.instance
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    suspend fun checkForUpdate(): UpdateInfo {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(API_URL)
                .header("Accept", "application/vnd.github+json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw Exception("Failed to check for updates (${response.code})")
            }

            val json = response.body?.string() ?: throw Exception("Empty response")
            val release = gson.fromJson(json, GitHubRelease::class.java)

            val latestVersion = release.tagName.removePrefix("v")
            val currentVersion = getCurrentVersion()

            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                ?: throw Exception("No APK found in latest release")

            UpdateInfo(
                version = latestVersion,
                downloadUrl = apkAsset.browserDownloadUrl,
                currentVersion = currentVersion,
                hasUpdate = latestVersion != currentVersion
            )
        }
    }

    suspend fun downloadAndInstall(downloadUrl: String, context: Context) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(downloadUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw Exception("Download failed (${response.code})")
            }

            val apkFile = File(context.cacheDir, "update.apk")
            response.body?.byteStream()?.use { input ->
                apkFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw Exception("Empty download")

            withContext(Dispatchers.Main) {
                installApk(context, apkFile)
            }
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }
}

private data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String,
    val assets: List<GitHubAsset>
)

private data class GitHubAsset(
    val name: String,
    @SerializedName("browser_download_url") val browserDownloadUrl: String
)

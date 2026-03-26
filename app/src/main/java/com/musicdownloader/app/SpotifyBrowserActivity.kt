package com.musicdownloader.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.musicdownloader.app.ui.theme.SpotifyMusicPlayerTheme

class SpotifyBrowserActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val RESULT_TRACKS_JSON = "tracks_json"

        // JS injected after page load:
        // 1. Intercepts fetch() calls that carry paginated track data
        // 2. Auto-scrolls so those calls are triggered
        private val INJECT_SCRIPT = """
            (function() {
                const bridge = window.SpotifyBridge;
                if (!bridge || window._injected) return;
                window._injected = true;

                // Intercept fetch to capture Spotify's pagination API responses
                const _fetch = window.fetch;
                window.fetch = function() {
                    const result = _fetch.apply(this, arguments);
                    try {
                        const url = (typeof arguments[0] === 'string')
                            ? arguments[0]
                            : (arguments[0]?.url || '');
                        if (url.includes('fetchPlaylist') || url.includes('getPlaylist')) {
                            result.then(function(r) {
                                try { r.clone().text().then(function(t) { bridge.onApiChunk(t); }); }
                                catch(e) {}
                            });
                        }
                    } catch(e) {}
                    return result;
                };

                // Auto-scroll to load paginated tracks
                function scrollLoop() {
                    const prev = window.scrollY;
                    window.scrollBy(0, 1200);
                    setTimeout(function() {
                        if (window.scrollY > prev) scrollLoop();
                    }, 600);
                }
                setTimeout(scrollLoop, 1500);
            })();
        """.trimIndent()
    }

    private val chunks = mutableListOf<String>()
    private var extraTrackCount = 0
    private var webViewRef: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val playlistUrl = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }

        setContent {
            SpotifyMusicPlayerTheme {
                var extraCount by remember { mutableIntStateOf(0) }
                var pageTitle by remember { mutableStateOf("Loading…") }

                Column(Modifier.fillMaxSize()) {
                    // Instruction banner
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        pageTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        maxLines = 1
                                    )
                                    Text(
                                        "Log in if prompted, then scroll to load all tracks.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                OutlinedButton(
                                    onClick = { webViewRef?.loadUrl(playlistUrl) },
                                    modifier = Modifier.padding(start = 8.dp)
                                ) { Text("Reload") }
                            }
                            if (extraCount > 0) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "+$extraCount additional tracks loaded",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // WebView
                    Box(Modifier.weight(1f)) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    webViewRef = this

                                    // Use a desktop Chrome UA — Spotify's web player renders
                                    // properly with desktop mode; mobile UAs often fail in WebView
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.databaseEnabled = true
                                    settings.useWideViewPort = true
                                    settings.loadWithOverviewMode = true
                                    @Suppress("DEPRECATION")
                                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    settings.userAgentString =
                                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                        "Chrome/124.0.0.0 Safari/537.36"

                                    // Allow third-party cookies (required for Spotify login)
                                    CookieManager.getInstance().setAcceptCookie(true)
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                                    addJavascriptInterface(object : Any() {
                                        @JavascriptInterface
                                        fun onApiChunk(json: String) {
                                            val parsed = tryParseChunk(json)
                                            if (parsed > 0) {
                                                extraTrackCount += parsed
                                                runOnUiThread { extraCount = extraTrackCount }
                                            }
                                        }
                                    }, "SpotifyBridge")

                                    webViewClient = object : WebViewClient() {
                                        // Allow all navigation — Spotify OAuth may go through
                                        // Google/Facebook, and "Liked Songs" etc. are on
                                        // different Spotify paths we shouldn't block
                                        override fun onPageFinished(view: WebView, url: String) {
                                            view.evaluateJavascript(INJECT_SCRIPT, null)
                                        }
                                    }
                                    webChromeClient = object : WebChromeClient() {
                                        override fun onReceivedTitle(view: WebView, title: String) {
                                            runOnUiThread { pageTitle = title }
                                        }
                                    }
                                    loadUrl(playlistUrl)
                                }
                            }
                        )
                    }

                    // Done button
                    Button(
                        onClick = { finishWithResult() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text("Import ${if (extraTrackCount > 0) "(+$extraTrackCount tracks)" else "tracks"}")
                    }
                }
            }
        }
    }

    /**
     * Try to extract track count from an intercepted Spotify API JSON chunk.
     * Returns the number of new tracks found.
     */
    private fun tryParseChunk(json: String): Int {
        return try {
            chunks.add(json)
            // Count items in the JSON (rough heuristic — exact parsing happens in ViewModel)
            val matches = Regex(""""name"\s*:\s*"[^"]{2,}"""").findAll(json).count()
            // Approximate: each track has ~3–5 "name" fields (track, album, artists)
            maxOf(0, matches / 3)
        } catch (_: Exception) { 0 }
    }

    private fun finishWithResult() {
        val result = Intent().apply {
            putStringArrayListExtra(RESULT_TRACKS_JSON, ArrayList(chunks))
        }
        setResult(RESULT_OK, result)
        finish()
    }

    override fun onBackPressed() {
        finishWithResult()
    }
}

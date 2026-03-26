package com.musicdownloader.app.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

private val INJECT_SCRIPT = """
    (function() {
        const bridge = window.SpotifyBridge;
        if (!bridge) return;

        function isSpotifyApi(url) {
            return url && (
                url.includes('api-partner.spotify.com') ||
                url.includes('spclient.wg.spotify.com') ||
                url.includes('api.spotify.com')
            );
        }

        if (!window._fetchWrapped) {
            window._fetchWrapped = true;
            const _fetch = window.fetch;
            window.fetch = function() {
                const result = _fetch.apply(this, arguments);
                try {
                    const url = (typeof arguments[0] === 'string')
                        ? arguments[0]
                        : (arguments[0]?.url || '');
                    if (isSpotifyApi(url)) {
                        result.then(function(r) {
                            try { r.clone().text().then(function(t) { bridge.onApiChunk(t); }); }
                            catch(e) {}
                        });
                    }
                } catch(e) {}
                return result;
            };
        }

        if (!window._xhrWrapped) {
            window._xhrWrapped = true;
            const _open = XMLHttpRequest.prototype.open;
            const _send = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(method, url) {
                this._spotifyUrl = url;
                return _open.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function() {
                if (isSpotifyApi(this._spotifyUrl)) {
                    const xhr = this;
                    xhr.addEventListener('load', function() {
                        try { bridge.onApiChunk(xhr.responseText); } catch(e) {}
                    });
                }
                return _send.apply(this, arguments);
            };
        }

        if (!window._scrollStarted) {
            window._scrollStarted = true;
            function scrollLoop() {
                const prev = window.scrollY;
                window.scrollBy(0, 1200);
                setTimeout(function() {
                    if (window.scrollY > prev) scrollLoop();
                }, 600);
            }
            setTimeout(scrollLoop, 2000);
        }
    })();
""".trimIndent()

/**
 * Full-screen WebView overlay for loading all Spotify playlist tracks.
 * Runs within the same Activity as ImportScreen so ViewModel state is preserved.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyBrowserOverlay(url: String, onDone: (chunks: List<String>) -> Unit) {
    val chunks = remember { mutableListOf<String>() }
    var extraCount by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf("Loading…") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    BackHandler { onDone(chunks.toList()) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = RoundedCornerShape(0.dp)
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
                            onClick = { webViewRef?.loadUrl(url) },
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

            Box(Modifier.weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewRef = this

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

                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            addJavascriptInterface(object : Any() {
                                @JavascriptInterface
                                fun onApiChunk(json: String) {
                                    chunks.add(json)
                                    val matches = Regex(""""name"\s*:\s*"[^"]{2,}"""").findAll(json).count()
                                    val approx = maxOf(0, matches / 3)
                                    if (approx > 0) {
                                        extraCount += approx
                                    }
                                }
                            }, "SpotifyBridge")

                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String) {
                                    view.evaluateJavascript(INJECT_SCRIPT, null)
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onReceivedTitle(view: WebView, title: String) {
                                    pageTitle = title
                                }
                            }
                            loadUrl(url)
                        }
                    }
                )
            }

            Button(
                onClick = { onDone(chunks.toList()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Import ${if (extraCount > 0) "(+$extraCount tracks)" else "tracks"}")
            }
        }
    }
}

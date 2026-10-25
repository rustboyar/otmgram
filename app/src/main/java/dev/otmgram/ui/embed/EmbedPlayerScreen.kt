package dev.otmgram.ui.embed

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.otmgram.R
import dev.otmgram.core.log.AppLog
import dev.otmgram.ui.media.openLink
import dev.otmgram.ui.theme.IconBtn
import dev.otmgram.ui.theme.OtmIcons
import dev.otmgram.ui.theme.OtmType
import dev.otmgram.ui.theme.TextAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plays a [MediaLink] in the site's own embed player, full screen. Nothing reaches the site
 * before this opens. Its cookies and storage are dropped on leaving, so visits aren't tied
 * together; leaving the player (a tap on the author, say) opens the site's app or the browser.
 */
@Composable
fun EmbedPlayerScreen(url: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val link = remember(url) { MediaLink.parse(url) }
    // A short TikTok link names no video: follow it to the page it stands for.
    val embed by produceState<Embed>(Embed.Loading, link) {
        value = when {
            link == null -> Embed.Failed
            link.embedUrl != null -> Embed.Ready(link.embedUrl!!)
            else -> followRedirects(link.url)?.let(MediaLink::parse)?.embedUrl?.let(Embed::Ready) ?: Embed.Failed
        }
    }
    var fullScreen by remember { mutableStateOf<FullScreen?>(null) }
    BackHandler(fullScreen != null) { fullScreen?.exit?.onCustomViewHidden() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(OtmIcons.Back, stringResource(R.string.back), onBack, tint = Color.White, tag = "back")
                Text(link?.site?.name.orEmpty(), style = OtmType.Name, color = Color.White, modifier = Modifier.weight(1f))
                TextAction(stringResource(R.string.link_open_elsewhere), { openLink(context, url) }, Modifier.testTag("openElsewhere"), color = Color.White)
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                when (val state = embed) {
                    Embed.Loading -> {}
                    Embed.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.link_cant_play), style = OtmType.Body, color = Color.White, textAlign = TextAlign.Center)
                        TextAction(stringResource(R.string.link_open_elsewhere), { openLink(context, url) }, color = Color.White)
                    }
                    is Embed.Ready -> Player(state.url, onFullScreen = { fullScreen = it })
                }
            }
        }
        fullScreen?.let { shown ->
            AndroidView({ shown.view.apply { (parent as? ViewGroup)?.removeView(this) } }, Modifier.fillMaxSize().background(Color.Black))
        }
    }
}

private sealed interface Embed {
    data object Loading : Embed
    data object Failed : Embed
    data class Ready(val url: String) : Embed
}

/** The player's own full-screen view (a video's ⛶ button), shown over everything. */
private class FullScreen(val view: View, val exit: WebChromeClient.CustomViewCallback)

// The players are JavaScript apps; the page holds nothing but the site's own frame.
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun Player(embedUrl: String, onFullScreen: (FullScreen?) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val webView = remember {
        WebView(context).apply {
            // Not wrapped to the page's height (the default in Compose): the page fills the view.
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                // Anything that would replace the player page leaves the app's player.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val target = request.url.toString()
                    if (request.url.scheme in setOf("http", "https")) openLink(context, target)
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) = onFullScreen(FullScreen(view, callback))

                override fun onHideCustomView() = onFullScreen(null)
            }
            // The site's frame inside a page of the app's own: YouTube refuses to play in a
            // WebView without a page to name as the embedder (error 153).
            loadDataWithBaseURL(EMBEDDER, page(embedUrl), "text/html", "utf-8", null)
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> webView.onPause()
                Lifecycle.Event.ON_RESUME -> webView.onResume()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            webView.destroy()
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
    }
    AndroidView({ webView }, Modifier.fillMaxSize().testTag("embedPlayer"))
}

private fun page(embedUrl: String): String {
    val src = embedUrl.replace("&", "&amp;").replace("\"", "&quot;")
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        |<style>html,body{margin:0;height:100%;background:#000}iframe{border:0;width:100%;height:100%;display:block}</style></head>
        |<body><iframe src="$src" allow="autoplay; encrypted-media; fullscreen; picture-in-picture" allowfullscreen
        |referrerpolicy="strict-origin-when-cross-origin"></iframe></body></html>""".trimMargin()
}

/** The page the players see as embedding them: the app, by its package name. */
private const val EMBEDDER = "https://dev.otmgram/"

/** Where a short link leads, following redirects without loading the page; null if it leads nowhere known. */
internal suspend fun followRedirects(url: String): String? = withContext(Dispatchers.IO) {
    var current = url
    repeat(MAX_REDIRECTS) {
        val connection = (URL(current).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val location = connection.run { responseCode; getHeaderField("Location") } ?: return@withContext null
            current = URL(URL(current), location).toString()
            if (MediaLink.parse(current)?.id != null) return@withContext current
        } catch (e: IOException) {
            AppLog.w("Embed", "Following a short link failed: ${e.javaClass.simpleName}")
            return@withContext null
        } finally {
            connection.disconnect()
        }
    }
    null
}

private const val MAX_REDIRECTS = 5
private const val TIMEOUT_MS = 10_000

/** A phone browser's: TikTok sends other clients elsewhere. */
private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

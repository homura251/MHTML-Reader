package com.example.mhtmllens.browser

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.mhtmllens.mhtml.MhtmlParser
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale

private data class BrowserTag(
    var archiveIdentity: Int = 0,
    var charset: String = "",
    var scripts: Boolean = false,
    var search: String = "",
    var findStep: Int = Int.MIN_VALUE
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ArchiveWebView(
    archive: MhtmlParser.Archive,
    modifier: Modifier = Modifier,
    charsetOverride: String? = null,
    javaScriptEnabled: Boolean = false,
    searchQuery: String = "",
    findStep: Int = 0,
    findForward: Boolean = true,
    onFindResult: (active: Int, total: Int) -> Unit = { _, _ -> },
    onBlockedLink: (String) -> Unit = {}
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.TRANSPARENT)
                settings.apply {
                    javaScriptEnabled = javaScriptEnabled
                    domStorageEnabled = javaScriptEnabled
                    allowFileAccess = false
                    allowContentAccess = false
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    mediaPlaybackRequiresUserGesture = true
                }
                webViewClient = ArchiveClient(archive, onBlockedLink)
                setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
                    onFindResult(activeMatchOrdinal, numberOfMatches)
                }
                tag = BrowserTag()
            }
        },
        update = { webView ->
            val tag = (webView.tag as? BrowserTag) ?: BrowserTag().also { webView.tag = it }
            val chosenCharset = charsetOverride ?: archive.rootCharset
            val archiveIdentity = System.identityHashCode(archive)

            webView.settings.javaScriptEnabled = javaScriptEnabled
            webView.settings.domStorageEnabled = javaScriptEnabled
            val currentClient = webView.webViewClient
            if (currentClient !is ArchiveClient || currentClient.archive !== archive) {
                webView.webViewClient = ArchiveClient(archive, onBlockedLink)
            }

            if (tag.archiveIdentity != archiveIdentity || tag.charset != chosenCharset || tag.scripts != javaScriptEnabled) {
                tag.archiveIdentity = archiveIdentity
                tag.charset = chosenCharset
                tag.scripts = javaScriptEnabled
                tag.search = ""
                tag.findStep = Int.MIN_VALUE
                loadPartAsDocument(webView, archive.root, archive.rootUrl, chosenCharset)
            }

            if (tag.search != searchQuery) {
                tag.search = searchQuery
                if (searchQuery.isBlank()) webView.clearMatches() else webView.findAllAsync(searchQuery)
            }
            if (tag.findStep != findStep) {
                if (tag.findStep != Int.MIN_VALUE && searchQuery.isNotBlank()) webView.findNext(findForward)
                tag.findStep = findStep
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.clearHistory()
            webView.destroy()
        }
    )
}

private class ArchiveClient(
    val archive: MhtmlParser.Archive,
    private val onBlockedLink: (String) -> Unit
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        return intercept(request.url.toString())
    }

    @Suppress("DEPRECATION")
    override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? = intercept(url)

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        return handleNavigation(view, request.url.toString())
    }

    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = handleNavigation(view, url)

    private fun handleNavigation(view: WebView, url: String): Boolean {
        if (url.startsWith("about:") || url.startsWith("data:") || url.startsWith("blob:")) return false

        val rootWithoutFragment = archive.rootUrl?.substringBefore('#')
        if (rootWithoutFragment != null && url.substringBefore('#') == rootWithoutFragment && '#' in url) {
            return false
        }

        val archived = archive.findPart(url)
        if (archived != null && archived.mimeType.equals("text/html", ignoreCase = true)) {
            loadPartAsDocument(view, archived, archived.contentLocation ?: url, archived.charsetName)
            return true
        }

        // Any navigation outside the archive is stopped here. This also prevents
        // file://, content://, intent:// and custom schemes from escaping the
        // offline viewer without an explicit user action in the host UI.
        onBlockedLink(url)
        return true
    }

    private fun intercept(url: String): WebResourceResponse? {
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase(Locale.ROOT) }.getOrNull()
        val part = archive.findPart(url)
        if (part != null) return responseFor(part)

        // MHTML viewer is offline by design. An archive cannot silently call the network.
        if (scheme == "http" || scheme == "https") {
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                404,
                "Not in archive",
                mapOf("Cache-Control" to "no-store"),
                ByteArrayInputStream(ByteArray(0))
            )
        }
        return null
    }

    private fun responseFor(part: MhtmlParser.Part): WebResourceResponse {
        return if (part.mimeType == "text/html" || part.mimeType == "text/css") {
            val (text, _) = MhtmlParser.decodePartText(part)
            val normalized = if (part.mimeType == "text/html") {
                forceUtf8Html(text)
            } else {
                text.replace(Regex("^\\s*@charset\\s+[\"'][^\"']+[\"']\\s*;", RegexOption.IGNORE_CASE), "@charset \"UTF-8\";")
            }
            WebResourceResponse(
                part.mimeType,
                "UTF-8",
                ByteArrayInputStream(normalized.toByteArray(StandardCharsets.UTF_8))
            )
        } else {
            WebResourceResponse(
                part.mimeType.ifBlank { "application/octet-stream" },
                part.charsetName,
                ByteArrayInputStream(part.bytes)
            )
        }
    }
}

private fun loadPartAsDocument(
    webView: WebView,
    part: MhtmlParser.Part,
    baseUrl: String?,
    charsetName: String?
) {
    val html = if (charsetName == null) {
        MhtmlParser.decodePartText(part).first
    } else {
        val charset = runCatching { Charset.forName(charsetName) }.getOrDefault(StandardCharsets.UTF_8)
        String(part.bytes, charset)
    }
    val utf8Html = forceUtf8Html(html)
    val base64 = Base64.encodeToString(utf8Html.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    webView.loadDataWithBaseURL(
        baseUrl ?: "https://mhtml.invalid/",
        base64,
        "text/html",
        "base64",
        null
    )
}

/**
 * WebView no longer has to guess the original character encoding. We decode the
 * source bytes ourselves and re-emit a UTF-8 document with an explicit, first meta.
 */
private fun forceUtf8Html(html: String): String {
    val withoutCharsetMeta = html.replace(
        Regex("<meta\\b[^>]*(?:charset\\s*=|http-equiv\\s*=\\s*['\"]?content-type)[^>]*>", RegexOption.IGNORE_CASE),
        ""
    )
    val head = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)
    val match = head.find(withoutCharsetMeta)
    return if (match != null) {
        withoutCharsetMeta.replaceRange(match.range.last + 1, match.range.last + 1, "<meta charset=\"utf-8\">")
    } else {
        "<meta charset=\"utf-8\">$withoutCharsetMeta"
    }
}

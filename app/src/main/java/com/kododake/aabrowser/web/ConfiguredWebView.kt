package com.kododake.aabrowser.web

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.kododake.aabrowser.R
import com.kododake.aabrowser.model.UserAgentProfile

data class BrowserCallbacks(
    val onUrlChange: (String) -> Unit = {},
    val onTitleChange: (String?) -> Unit = {},
    val onProgressChange: (Int) -> Unit = {},
    val onShowDownloadPrompt: (Uri) -> Unit = {},
    val onError: (Int, String?) -> Unit = { _, _ -> },
    val onCleartextNavigationRequested: (
        Uri,
        allowOnce: () -> Unit,
        allowHostPermanently: () -> Unit,
        cancel: () -> Unit
    ) -> Unit = { _, _, _, cancel -> cancel() },
    val onEnterFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit = { _, _ -> },
    val onExitFullscreen: () -> Unit = {},
    val onPermissionRequest: (PermissionRequest) -> Unit = { it.deny() },
    val onGeolocationPermission: (origin: String?, grant: (Boolean) -> Unit) -> Unit = { _, grant -> grant(false) },
    val onPageStarted: () -> Unit = {},
    val onPageFinished: (String?) -> Unit = {}
)

fun configureWebView(
    webView: WebView,
    callbacks: BrowserCallbacks = BrowserCallbacks(),
    useDesktopMode: Boolean = false,
    userAgentProfile: UserAgentProfile = UserAgentProfile.ANDROID_CHROME
) {
    with(webView) {
        setBackgroundColor(Color.TRANSPARENT)

        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = true

        WebView.setWebContentsDebuggingEnabled(false)

        val originalUserAgent = settings.userAgentString
        setTag(R.id.webview_original_user_agent_tag, originalUserAgent)

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true

            setSupportMultipleWindows(true)

            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowContentAccess = true
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                offscreenPreRaster = true
            }
        }

        applyUserAgent(userAgentProfile, useDesktopMode)
        val scale = context.resources.displayMetrics.density * 100
        setInitialScale(scale.toInt())

        CookieManager.getInstance().also {
            it.setAcceptCookie(true)
            it.setAcceptThirdPartyCookies(this, true)
        }

        //setLayerType(View.LAYER_TYPE_HARDWARE, null)

        var showingLocalError = false
        var connectionRechecked = false
        var connectionRecheckPosted = false
        var loadFailed = false
        var navigationGeneration = 0
        var recheckGeneration = -1
        var recheckRunnable: Runnable? = null

        fun showLocalErrorPage(view: WebView, failedUrl: String, code: Int, message: String) {
            if (showingLocalError) return
            if (failedUrl.startsWith("https://local.aabrowser/")) return
            showingLocalError = true
            callbacks.onError(code, message)
            view.stopLoading()
            val html = try {
                localErrorDocument(view, failedUrl, code, message)
            } catch (_: Exception) {
                fallbackErrorDocument()
            }
            view.post {
                try {
                    view.loadDataWithBaseURL(
                        "https://local.aabrowser/error/",
                        html,
                        "text/html",
                        "utf-8",
                        failedUrl.ifBlank { null }
                    )
                } catch (_: Exception) {
                }
            }
        }

        // A brief drop should not replace the map. The failed load is the first check;
        // one more load a few seconds later is the second. The offline screen appears
        // only when that second check also fails.
        fun considerMainFrameError(view: WebView, failedUrl: String, code: Int, message: String) {
            if (connectionRecheckPosted) return
            if (isConnectivityError(code, message) && !connectionRechecked && failedUrl.isNotBlank()) {
                connectionRecheckPosted = true
                connectionRechecked = true
                view.stopLoading()
                val generation = navigationGeneration
                val retry = Runnable {
                    connectionRecheckPosted = false
                    if (generation != navigationGeneration || !view.isAttachedToWindow) return@Runnable
                    recheckGeneration = generation
                    try {
                        view.loadUrl(failedUrl)
                    } catch (_: Exception) {
                        showLocalErrorPage(view, failedUrl, code, message)
                    }
                }
                recheckRunnable = retry
                view.postDelayed(retry, CONNECTION_RECHECK_MS)
                return
            }
            showLocalErrorPage(view, failedUrl, code, message)
        }

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (handleCleartextIfNeeded(view, uri, callbacks, onPageStart = false)) return true
                return handleUri(view, uri)
            }

            private fun handleUri(view: WebView, uri: Uri?): Boolean {
                uri ?: return false
                val scheme = uri.scheme?.lowercase()
                if (scheme == null || scheme in setOf("http", "https", "about", "file", "data", "javascript")) {
                    return false
                }
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return MapModelAssets.intercept(view.context, request)
                    ?: super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                callbacks.onPageStarted()
                loadFailed = false
                val stringUrl = url ?: return
                if (navigationGeneration == recheckGeneration) {
                    recheckGeneration = -1
                } else if (!stringUrl.startsWith("https://local.aabrowser/")) {
                    navigationGeneration++
                    connectionRecheckPosted = false
                    recheckRunnable?.let { view.removeCallbacks(it) }
                }
                if (!stringUrl.startsWith("https://local.aabrowser/")) showingLocalError = false
                val uri = Uri.parse(stringUrl)
                val scheme = uri.scheme?.lowercase()

                if (scheme == "http") {
                    val allowedOnce = getTag(R.id.webview_allow_once_uri_tag) as? String
                    if (allowedOnce == stringUrl) {
                        setTag(R.id.webview_allow_once_uri_tag, null)
                    } else if (handleCleartextIfNeeded(view, uri, callbacks, onPageStart = true)) {
                        return
                    }
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (!loadFailed && url != null && !url.startsWith("https://local.aabrowser/")) {
                    connectionRechecked = false
                    connectionRecheckPosted = false
                }
                view.evaluateJavascript(SpeechRecognitionBridge.POLYFILL_JS, null)
                callbacks.onPageFinished(url)
                url?.let(callbacks.onUrlChange)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (!request.isForMainFrame) return
                loadFailed = true
                considerMainFrameError(
                    view,
                    request.url?.toString().orEmpty(),
                    error.errorCode,
                    error.description?.toString().orEmpty()
                )
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                if (!request.isForMainFrame) return
                val code = errorResponse.statusCode
                if (code in 400..599 && code != 429) {
                    loadFailed = true
                    showLocalErrorPage(
                        view,
                        request.url?.toString().orEmpty(),
                        code,
                        errorResponse.reasonPhrase.orEmpty()
                    )
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                val primary = try { error.primaryError } catch (_: Exception) { -1 }
                handler.cancel()
                loadFailed = true
                showLocalErrorPage(view, error.url ?: "", primary, "SSL error: $primary")
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                callbacks.onProgressChange(newProgress)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                callbacks.onTitleChange(title)
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view != null && callback != null) {
                    callbacks.onEnterFullscreen(view, callback)
                } else {
                    super.onShowCustomView(view, callback)
                }
            }

            override fun onHideCustomView() {
                callbacks.onExitFullscreen()
                super.onHideCustomView()
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (callback == null) return
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    callback.invoke(origin, true, true)
                    return
                }
                callbacks.onGeolocationPermission(origin) { allow ->
                    callback.invoke(origin, allow, allow)
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return

                val allowed = setOf(
                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID,
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE
                )

                val grantable = request.resources.filter { it in allowed }.toTypedArray()

                if (grantable.isEmpty()) {
                    request.deny()
                    return
                }

                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in grantable) {
                    callbacks.onPermissionRequest(request)
                } else {
                    this@with.post { request.grant(grantable) }
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                return false
            }
        }

        setDownloadListener(DownloadListener { url, _, _, _, _ ->
            val uri = url?.takeIf { it.isNotBlank() }?.toUri() ?: return@DownloadListener
            callbacks.onShowDownloadPrompt(uri)
        })
    }
}

private fun handleCleartextIfNeeded(view: WebView, uri: Uri?, callbacks: BrowserCallbacks, onPageStart: Boolean = false): Boolean {
    uri ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme != "http") return false

    val allowedOnce = view.getTag(R.id.webview_allow_once_uri_tag) as? String
    if (allowedOnce == uri.toString()) {
        view.setTag(R.id.webview_allow_once_uri_tag, null)
        return false
    }

    val host = uri.host?.lowercase()
    if (com.kododake.aabrowser.data.BrowserPreferences.isHostAllowedCleartext(view.context, host)) return false
    if (onPageStart) view.stopLoading()
    val allowOnce = {
        view.setTag(R.id.webview_allow_once_uri_tag, uri.toString())
        view.post { view.loadUrl(uri.toString()) }
        kotlin.Unit
    }
    val allowHost = {
        view.context?.let { ctx ->
            val hostToStore = uri.host?.lowercase()
            if (hostToStore != null) com.kododake.aabrowser.data.BrowserPreferences.addAllowedCleartextHost(ctx, hostToStore)
        }
        view.setTag(R.id.webview_allow_once_uri_tag, uri.toString())
        view.post { view.loadUrl(uri.toString()) }
        kotlin.Unit
    }
    val cancel = {
        if (onPageStart) view.stopLoading()
        kotlin.Unit
    }
    callbacks.onCleartextNavigationRequested(uri, allowOnce, allowHost, cancel)
    return true
}

fun WebView.updateDesktopMode(enable: Boolean, profile: UserAgentProfile) {
    applyUserAgent(profile, enable)
    reload()
}

fun WebView.updateUserAgentProfile(profile: UserAgentProfile, desktop: Boolean) {
    applyUserAgent(profile, desktop)
    reload()
}

fun WebView.releaseCompletely() {
    stopLoading()
    webChromeClient = WebChromeClient()
    webViewClient = WebViewClient()
    destroy()
}

private fun WebView.applyUserAgent(profile: UserAgentProfile, desktop: Boolean) {
    setTag(R.id.webview_user_agent_profile_tag, profile.storageKey)
    settings.userAgentString = buildUserAgent(profile, desktop)
    settings.useWideViewPort = desktop
    settings.loadWithOverviewMode = desktop
}

private fun buildUserAgent(profile: UserAgentProfile, desktop: Boolean): String {
    return when (profile) {
        UserAgentProfile.ANDROID_CHROME -> if (desktop) WINDOWS_CHROME_UA else MOBILE_CHROME_UA
        UserAgentProfile.SAFARI -> if (desktop) SAFARI_MAC_UA else SAFARI_IOS_UA
    }
}

private const val CONNECTION_RECHECK_MS = 3000L
private const val CHROME_VERSION = "144.0.0.0"

private fun isConnectivityError(code: Int, message: String): Boolean {
    val text = message.lowercase()
    if (text.contains("err_cert") || text.contains("ssl")) return false
    if (text.contains("err_internet_disconnected") ||
        text.contains("err_name_not_resolved") ||
        text.contains("err_name_resolution") ||
        text.contains("err_connection_") ||
        text.contains("err_address_unreachable") ||
        text.contains("err_network_changed") ||
        text.contains("err_network_access_denied") ||
        text.contains("err_timed_out") ||
        text.contains("err_timeout") ||
        text.contains("err_proxy") ||
        text.contains("net::err_")
    ) {
        return true
    }
    return code == WebViewClient.ERROR_HOST_LOOKUP ||
        code == WebViewClient.ERROR_CONNECT ||
        code == WebViewClient.ERROR_IO ||
        code == WebViewClient.ERROR_TIMEOUT
}
private const val MOBILE_CHROME_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${CHROME_VERSION} Mobile Safari/537.36"
private const val WINDOWS_CHROME_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${CHROME_VERSION} Safari/537.36"
private const val SAFARI_MAC_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"
private const val SAFARI_IOS_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

private var errorPageTemplate: String? = null

private fun localErrorDocument(view: WebView, failedUrl: String, code: Int, message: String): String {
    val template = errorPageTemplate ?: view.context.assets.open("error.html").bufferedReader().use { it.readText() }
        .also { errorPageTemplate = it }
    return template
        .replace("__FAILED_URL__", jsEscape(failedUrl))
        .replace("__ERROR_CODE__", jsEscape(code.toString()))
        .replace("__ERROR_MESSAGE__", jsEscape(message.ifBlank { "No connection" }))
}

private fun fallbackErrorDocument(): String {
    return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>No connection</title></head><body style="margin:0;height:100vh;display:flex;align-items:center;justify-content:center;background:#0c121c;color:#e8eaed;font-family:system-ui,sans-serif;text-align:center;padding:24px"><div><h1 style="font-weight:750">No connection</h1><p>This page needs a network connection. Check the signal, then try again.</p></div></body></html>"""
}

private fun jsEscape(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", " ")
        .replace("\n", " ")
        .replace("</", "<\\/")
}
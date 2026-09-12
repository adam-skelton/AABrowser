package com.kododake.aabrowser.car

import android.webkit.JavascriptInterface

class CarJsBridge(
    private val onMain: (block: () -> Unit) -> Unit,
    private val notifyInputFocused: (value: String) -> Unit,
    private val notifySearchSuggestions: (query: String, items: List<SearchSuggestion>) -> Unit,
    private val requestOpenKeyboard: () -> Unit,
    private val requestGoBack: () -> Unit,
    private val notifyDebugOverlay: (Boolean) -> Unit,
    private val notifyMapReady: () -> Unit = {},
    private val resolveCarApiLevel: () -> Int,
    private val traceStore: TraceStore? = null
) {
    @JavascriptInterface
    fun onInputFocused(value: String?) {
        onMain { notifyInputFocused(value.orEmpty()) }
    }

    @JavascriptInterface
    fun onSearchSuggestions(json: String?) {
        onMain {
            val parsed = SearchSuggestion.parse(json)
            notifySearchSuggestions(parsed.first, parsed.second)
        }
    }

    @JavascriptInterface
    fun openKeyboard() {
        onMain { requestOpenKeyboard() }
    }

    @JavascriptInterface
    fun goBack() {
        onMain { requestGoBack() }
    }

    @JavascriptInterface
    fun setDebugOverlay(visible: Boolean) {
        onMain { notifyDebugOverlay(visible) }
    }

    @JavascriptInterface
    fun mapReady() {
        onMain { notifyMapReady() }
    }

    @JavascriptInterface
    fun getCarAppApiLevel(): Int {
        return resolveCarApiLevel().coerceAtLeast(0)
    }

    // Recorded drive traces (the page records/replays; the host only persists). These run
    // on the WebView's JS bridge thread; file I/O there is fine for these small files.

    @JavascriptInterface
    fun listTraces(): String {
        return traceStore?.list() ?: "[]"
    }

    @JavascriptInterface
    fun readTrace(id: String?): String? {
        if (id.isNullOrBlank()) return null
        return traceStore?.read(id)
    }

    @JavascriptInterface
    fun saveTrace(id: String?, traceJson: String?, metaJson: String?): Boolean {
        if (id.isNullOrBlank() || traceJson.isNullOrEmpty()) return false
        return traceStore?.save(id, traceJson, metaJson ?: "{}") ?: false
    }

    @JavascriptInterface
    fun deleteTrace(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        return traceStore?.delete(id) ?: false
    }
}

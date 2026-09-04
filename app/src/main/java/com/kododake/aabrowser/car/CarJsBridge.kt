package com.kododake.aabrowser.car

import android.webkit.JavascriptInterface

class CarJsBridge(
    private val onMain: (block: () -> Unit) -> Unit,
    private val notifyInputFocused: (value: String) -> Unit,
    private val requestOpenKeyboard: () -> Unit,
    private val requestGoBack: () -> Unit
) {
    @JavascriptInterface
    fun onInputFocused(value: String?) {
        onMain { notifyInputFocused(value.orEmpty()) }
    }

    @JavascriptInterface
    fun openKeyboard() {
        onMain { requestOpenKeyboard() }
    }

    @JavascriptInterface
    fun goBack() {
        onMain { requestGoBack() }
    }
}

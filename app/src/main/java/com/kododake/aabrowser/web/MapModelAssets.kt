package com.kododake.aabrowser.web

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.util.Locale

object MapModelAssets {
    private val files = mapOf(
        "forte.glb" to "models/forte.glb",
        "cerato.glb" to "models/cerato.glb",
        "forte-preview.glb" to "models/forte-preview.glb",
        "heading-arrow.glb" to "models/heading-arrow.glb"
    )

    private val corsHeaders = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
        "Access-Control-Allow-Headers" to "*",
        "Access-Control-Max-Age" to "86400",
        "Cache-Control" to "public, max-age=31536000, immutable",
        "Content-Type" to "model/gltf-binary"
    )

    fun intercept(context: Context, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url ?: return null
        val host = url.host ?: return null
        if (!host.equals("adam-skelton.github.io", ignoreCase = true)) return null
        val path = url.path ?: return null
        if (!path.contains("/AABrowser", ignoreCase = true)) return null
        val name = path.substringAfterLast('/').lowercase(Locale.US)
        val assetPath = files[name] ?: return null
        val method = request.method.uppercase(Locale.US)
        if (method == "OPTIONS") {
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                204,
                "No Content",
                corsHeaders,
                ByteArray(0).inputStream()
            )
        }
        if (method != "GET" && method != "HEAD") return null
        return try {
            val stream = if (method == "HEAD") {
                ByteArray(0).inputStream()
            } else {
                context.assets.open(assetPath)
            }
            WebResourceResponse(
                "model/gltf-binary",
                null,
                200,
                "OK",
                corsHeaders,
                stream
            )
        } catch (_: Exception) {
            null
        }
    }
}

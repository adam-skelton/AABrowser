package com.kododake.aabrowser.web

import android.content.Context
import androidx.webkit.WebViewAssetLoader
import java.io.File

object LocalWebContent {
    const val DOMAIN = "appassets.androidplatform.net"
    const val ASSET_INDEX_URL = "https://$DOMAIN/assets/web/index.html"
    const val FILES_INDEX_URL = "https://$DOMAIN/files/index.html"

    fun filesRoot(context: Context): File {
        val appContext = context.applicationContext
        val base = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        return File(base, "web").apply { mkdirs() }
    }

    fun startUrl(context: Context): String {
        val override = File(filesRoot(context), "index.html")
        return if (override.isFile) FILES_INDEX_URL else ASSET_INDEX_URL
    }

    fun assetLoader(context: Context): WebViewAssetLoader {
        val appContext = context.applicationContext
        return WebViewAssetLoader.Builder()
            .setDomain(DOMAIN)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appContext))
            .addPathHandler(
                "/files/",
                WebViewAssetLoader.InternalStoragePathHandler(appContext, filesRoot(appContext))
            )
            .build()
    }
}

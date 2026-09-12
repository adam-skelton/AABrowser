package com.kododake.aabrowser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.color.DynamicColors
import com.kododake.aabrowser.analytics.UmamiTracker
import com.kododake.aabrowser.car.CarJsBridge
import com.kododake.aabrowser.car.CarWebViewHost
import com.kododake.aabrowser.car.MapBootOverlay
import com.kododake.aabrowser.car.MotionFeed
import com.kododake.aabrowser.car.TraceStore
import com.kododake.aabrowser.databinding.ActivityMainBinding
import com.kododake.aabrowser.web.BrowserCallbacks
import com.kododake.aabrowser.web.configureWebView
import com.kododake.aabrowser.web.releaseCompletely

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val umamiTracker: UmamiTracker by lazy { UmamiTracker(applicationContext) }

    private var webView: android.webkit.WebView? = null
    private var currentUrl: String = CarWebViewHost.START_URL
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var pendingGeoGrant: ((Boolean) -> Unit)? = null
    private var locationStarted = false
    private var lastLocation: Location? = null
    private var hadGoodGpsFix = false
    private val motionFeed by lazy {
        MotionFeed(applicationContext) { js -> runOnUiThread { webView?.evaluateJavascript(js, null) } }
    }

    private val locationListener = LocationListener { location -> injectPhoneLocation(location) }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val allow = granted.values.any { it }
        pendingGeoGrant?.invoke(allow)
        pendingGeoGrant = null
        if (allow) startPhoneLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MapBootOverlay.startBounce(binding.mapBootOverlay.root)

        umamiTracker.trackEvent("app_open")

        val disp = currentDisplayOrNull()
        val best = disp?.supportedModes?.maxWithOrNull(
            compareBy({ it.refreshRate }, { it.physicalWidth.toLong() * it.physicalHeight })
        )
        best?.let { mode ->
            val attrs = window.attributes
            attrs.preferredDisplayModeId = mode.modeId
            window.attributes = attrs
        }

        setupBackPressHandling()
        ensureLocationPermissionIfNeeded()
        setupUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractBrowsableUrl(intent)?.let { url ->
            currentUrl = url
            webView?.loadUrl(url)
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        if (hasLocationPermission()) startPhoneLocation()
    }

    override fun onPause() {
        exitFullscreen()
        stopPhoneLocation()
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        exitFullscreen()
        stopPhoneLocation()
        binding.webView.releaseCompletely()
        webView = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayOrNull(): Display? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            windowManager.defaultDisplay
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureLocationPermissionIfNeeded() {
        if (hasLocationPermission()) {
            startPhoneLocation()
            return
        }
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun startPhoneLocation() {
        if (locationStarted || !hasLocationPermission()) return
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = CarWebViewHost.locationProviders(manager)
        if (providers.isEmpty()) return
        runCatching {
            for (provider in providers) {
                manager.requestLocationUpdates(provider, 1000L, 1f, locationListener, Looper.getMainLooper())
            }
            locationStarted = true
            motionFeed.start()
            providers.firstNotNullOfOrNull { provider -> manager.getLastKnownLocation(provider) }
                ?.let(::injectPhoneLocation)
        }
    }

    private fun stopPhoneLocation() {
        motionFeed.stop()
        if (!locationStarted) return
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        runCatching { manager.removeUpdates(locationListener) }
        locationStarted = false
        hadGoodGpsFix = false
    }

    private fun injectPhoneLocation(location: Location) {
        // Same GPS-only gate as the car host: coarse/network fixes only bootstrap the map.
        val good = CarWebViewHost.isGoodGpsFix(location)
        if (!good && hadGoodGpsFix) return
        if (good) hadGoodGpsFix = true
        lastLocation = location
        val view = webView ?: return
        view.evaluateJavascript(CarWebViewHost.gpsInjectJs(location), null)
    }

    private fun setupUi() {
        val intentUrl = extractBrowsableUrl(intent)
        val initialUrl = intentUrl ?: CarWebViewHost.START_URL
        currentUrl = initialUrl

        val browserCallbacks = BrowserCallbacks(
            onUrlChange = { url ->
                runOnUiThread {
                    currentUrl = url
                    if (CarWebViewHost.isCarMapUrl(url)) {
                        applyCarPreview(url)
                    } else {
                        dismissMapBootOverlay()
                    }
                }
            },
            onEnterFullscreen = { view, callback ->
                runOnUiThread { enterFullscreen(view, callback) }
            },
            onExitFullscreen = {
                runOnUiThread { exitFullscreen(true) }
            },
            onGeolocationPermission = { _, grant ->
                runOnUiThread {
                    if (hasLocationPermission()) {
                        grant(true)
                        startPhoneLocation()
                    } else {
                        pendingGeoGrant = grant
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                }
            }
        )

        webView = binding.webView
        webView?.let { view ->
            configureWebView(view, browserCallbacks, true)
            view.addJavascriptInterface(
                CarJsBridge(
                    onMain = { block -> runOnUiThread(block) },
                    notifyInputFocused = {},
                    notifySearchSuggestions = { _, _ -> },
                    requestOpenKeyboard = {},
                    requestGoBack = { runOnUiThread { webView?.goBack() } },
                    notifyDebugOverlay = {},
                    notifyMapReady = { dismissMapBootOverlay() },
                    resolveCarApiLevel = { 0 },
                    traceStore = TraceStore(applicationContext)
                ),
                CarWebViewHost.BRIDGE_NAME
            )
            view.loadUrl(initialUrl)
        }
    }

    private fun dismissMapBootOverlay() {
        val overlay = binding.mapBootOverlay.root
        if (overlay.visibility != View.VISIBLE) return
        overlay.animate()
            .alpha(0f)
            .setDuration(280L)
            .withEndAction { overlay.visibility = View.GONE }
            .start()
    }

    private fun applyCarPreview(url: String?) {
        if (!CarWebViewHost.isCarMapUrl(url)) return
        val view = webView ?: return
        CarWebViewHost.applyCarChrome(view)
        lastLocation?.let { injectPhoneLocation(it) }
        view.postDelayed({
            if (CarWebViewHost.isCarMapUrl(currentUrl)) {
                webView?.let { CarWebViewHost.applyCarChrome(it) }
            }
        }, 120L)
        view.postDelayed({
            if (CarWebViewHost.isCarMapUrl(currentUrl)) {
                webView?.let { CarWebViewHost.applyCarChrome(it) }
            }
        }, 400L)
    }

    private fun setupBackPressHandling() {
        onBackPressedDispatcher.addCallback(this) {
            when {
                customView != null -> exitFullscreen()
                webView?.canGoBack() == true -> webView?.goBack()
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    private fun extractBrowsableUrl(intent: Intent?): String? {
        val data = intent?.data ?: return null
        return if (data.scheme?.lowercase() in listOf("http", "https")) data.toString() else null
    }

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        (view.parent as? ViewGroup)?.removeView(view)
        customView = view
        customViewCallback = callback
        binding.webView.visibility = View.INVISIBLE
        binding.fullscreenContainer.apply {
            visibility = View.VISIBLE
            removeAllViews()
            addView(view, FrameLayout.LayoutParams(-1, -1))
            bringToFront()
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, binding.fullscreenContainer).hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun exitFullscreen(fromWebChrome: Boolean = false) {
        if (customView == null) return
        binding.fullscreenContainer.apply {
            removeAllViews()
            visibility = View.GONE
        }
        binding.webView.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, binding.root).show(WindowInsetsCompat.Type.systemBars())
        val callback = customViewCallback
        customView = null
        customViewCallback = null
        if (!fromWebChrome) callback?.onCustomViewHidden()
    }
}

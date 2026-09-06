package com.kododake.aabrowser.car

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.kododake.aabrowser.R
import com.kododake.aabrowser.web.BrowserCallbacks
import com.kododake.aabrowser.web.configureWebView
import com.kododake.aabrowser.web.releaseCompletely
import org.json.JSONObject

class CarWebViewHost(
    private val carContext: CarContext
) : SurfaceCallback {

    var inputFocusListener: ((String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val endDragRunnable = Runnable { endDrag() }
    private val jsBridge = CarJsBridge(
        onMain = ::onMain,
        notifyInputFocused = { value -> notifyInputFocused(value) },
        requestOpenKeyboard = { notifyInputFocused("") },
        requestGoBack = { goBack() }
    )

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var webView: WebView? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

    private var dragging = false
    private var dragX = 0f
    private var dragY = 0f
    private var dragDownTime = 0L
    private var lastInputNotifyAt = 0L
    private var suppressFocusUntil = 0L
    private var pendingJs: String? = null
    private var restoreAttempt = 0
    private val restoreMapRunnable = Runnable { restoreMapRenderer() }

    fun register() {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    fun goBack() {
        onMain { webView?.let { if (it.canGoBack()) it.goBack() } }
    }

    fun reload() {
        onMain { webView?.reload() }
    }

    fun readFocusedInputValue(callback: (String) -> Unit) {
        onMain {
            val view = webView
            if (view == null) {
                callback("")
                return@onMain
            }
            view.evaluateJavascript(CHECK_FOCUS_JS) { result ->
                callback(parseFocusValue(result) ?: "")
            }
        }
    }

    fun setInputText(text: String, submit: Boolean) {
        onMain {
            if (submit) {
                suppressFocusUntil = SystemClock.uptimeMillis() + SUBMIT_FOCUS_SUPPRESS_MS
            }
            val quoted = JSONObject.quote(text)
            evaluateOrQueue(
                SET_INPUT_JS.replace("TEXT_PLACEHOLDER", quoted)
                    .replace("SUBMIT_PLACEHOLDER", submit.toString())
            )
        }
    }

    fun destroy() {
        onMain {
            runCatching {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
            }
            tearDownSurface(destroyWebView = true)
        }
    }

    fun recenterOnUser() {
        onMain {
            webView?.evaluateJavascript(
                "window.__aaRecenter && window.__aaRecenter();",
                null
            )
        }
    }

    fun onForegrounded() {
        onMain { scheduleMapRestore() }
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        onMain { attachSurface(surfaceContainer) }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        onMain { tearDownSurface(destroyWebView = false) }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        onMain { webView?.invalidate() }
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        onMain { webView?.invalidate() }
    }

    override fun onClick(x: Float, y: Float) {
        onMain { dispatchClick(x, y) }
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        onMain { dispatchScroll(distanceX, distanceY) }
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        onMain {
            endDrag()
            webView?.flingScroll(velocityX.toInt(), velocityY.toInt())
        }
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        onMain {
            val view = webView ?: return@onMain
            when {
                scaleFactor > 1.01f || scaleFactor < 0.99f ->
                    view.evaluateJavascript(
                        "window.__aaScaleMap && window.__aaScaleMap($scaleFactor);",
                        null
                    )
            }
        }
    }

    private fun attachSurface(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface
        val width = surfaceContainer.width
        val height = surfaceContainer.height
        val dpi = surfaceContainer.dpi
        if (surface == null || width <= 0 || height <= 0 || dpi <= 0) {
            Log.w(TAG, "Ignoring invalid surface ${width}x${height} dpi=$dpi")
            return
        }

        tearDownSurface(destroyWebView = false)
        surfaceWidth = width
        surfaceHeight = height

        val displayManager = carContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val createdDisplay = createVirtualDisplay(displayManager, width, height, dpi, surface)
            ?: return
        virtualDisplay = createdDisplay

        val carPresentation = Presentation(
            carContext,
            createdDisplay.display,
            android.R.style.Theme_Black_NoTitleBar_Fullscreen
        )
        presentation = carPresentation
        carPresentation.window?.apply {
            setFormat(PixelFormat.RGBA_8888)
            addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val container = carPresentation.layoutInflater.inflate(R.layout.presentation_browser, null) as ViewGroup
        val hosted = webView ?: createWebView(carContext).also { webView = it }
        (hosted.parent as? ViewGroup)?.removeView(hosted)
        container.addView(
            hosted,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        try {
            carPresentation.setContentView(container)
            carPresentation.show()
            hosted.onResume()
            hosted.resumeTimers()
            pendingJs?.let { script ->
                hosted.evaluateJavascript(script, null)
                pendingJs = null
            }
            scheduleMapRestore()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to show presentation", error)
            tearDownSurface(destroyWebView = false)
        }
    }

    private fun createVirtualDisplay(
        displayManager: DisplayManager,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface
    ): VirtualDisplay? {
        val flagSets = intArrayOf(
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            0
        )
        for (flags in flagSets) {
            try {
                return displayManager.createVirtualDisplay(
                    VIRTUAL_DISPLAY_NAME,
                    width,
                    height,
                    dpi,
                    surface,
                    flags
                )
            } catch (error: Exception) {
                Log.w(TAG, "Virtual display flags=$flags failed: ${error.message}")
            }
        }
        Log.e(TAG, "Failed to create virtual display")
        return null
    }

    private fun createWebView(context: Context): WebView {
        return WebView(context).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            setNestedScrollingEnabled(true)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            configureWebView(
                webView = this,
                callbacks = BrowserCallbacks(
                    onUrlChange = { evaluateJavascript(FOCUS_HOOK_JS, null) }
                ),
                useDesktopMode = true
            )
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            setInitialScale(100)
            addJavascriptInterface(jsBridge, BRIDGE_NAME)
            loadUrl(START_URL)
        }
    }

    private fun tearDownSurface(destroyWebView: Boolean) {
        mainHandler.removeCallbacks(endDragRunnable)
        mainHandler.removeCallbacks(restoreMapRunnable)
        restoreAttempt = 0
        endDrag()
        val hosted = webView
        if (hosted != null) {
            if (destroyWebView) {
                hosted.onPause()
            }
            (hosted.parent as? ViewGroup)?.removeView(hosted)
        }
        runCatching { presentation?.dismiss() }
        presentation = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        if (destroyWebView && hosted != null) {
            hosted.removeJavascriptInterface(BRIDGE_NAME)
            hosted.releaseCompletely()
            webView = null
        }
    }

    private fun scheduleMapRestore() {
        mainHandler.removeCallbacks(restoreMapRunnable)
        restoreAttempt = 0
        mainHandler.post(restoreMapRunnable)
    }

    private fun restoreMapRenderer() {
        val view = webView ?: return
        if (!view.isAttachedToWindow) {
            return
        }
        view.resumeTimers()
        view.onResume()
        view.requestLayout()
        view.invalidate()
        view.evaluateJavascript(RESTORE_MAP_JS, null)

        restoreAttempt += 1
        if (restoreAttempt < 4) {
            val delay = if (restoreAttempt == 1) 80L else 250L
            mainHandler.postDelayed(restoreMapRunnable, delay)
        }
    }

    private fun dispatchClick(x: Float, y: Float) {
        val view = webView ?: return
        endDrag()
        view.requestFocus()
        val downTime = SystemClock.uptimeMillis()
        dispatchTouch(view, MotionEvent.ACTION_DOWN, x, y, downTime, downTime)
        dispatchTouch(view, MotionEvent.ACTION_UP, x, y, downTime, downTime + CLICK_DURATION_MS)
        mainHandler.postDelayed({
            view.evaluateJavascript(CHECK_FOCUS_JS) { result ->
                val value = parseFocusValue(result, requireFocused = true) ?: return@evaluateJavascript
                notifyInputFocused(value)
            }
        }, FOCUS_CHECK_DELAY_MS)
    }

    private fun dispatchScroll(distanceX: Float, distanceY: Float) {
        val view = webView ?: return
        mainHandler.removeCallbacks(endDragRunnable)
        if (!dragging) {
            dragging = true
            dragX = (surfaceWidth / 2).toFloat().coerceAtLeast(1f)
            dragY = (surfaceHeight / 2).toFloat().coerceAtLeast(1f)
            dragDownTime = SystemClock.uptimeMillis()
            dispatchTouch(view, MotionEvent.ACTION_DOWN, dragX, dragY, dragDownTime, dragDownTime)
        }
        dragX = (dragX - distanceX).coerceIn(0f, surfaceWidth.toFloat().coerceAtLeast(1f))
        dragY = (dragY - distanceY).coerceIn(0f, surfaceHeight.toFloat().coerceAtLeast(1f))
        dispatchTouch(view, MotionEvent.ACTION_MOVE, dragX, dragY, dragDownTime, SystemClock.uptimeMillis())
        mainHandler.postDelayed(endDragRunnable, DRAG_END_DELAY_MS)
    }

    private fun endDrag() {
        if (!dragging) return
        val view = webView
        dragging = false
        if (view != null) {
            dispatchTouch(view, MotionEvent.ACTION_UP, dragX, dragY, dragDownTime, SystemClock.uptimeMillis())
        }
    }

    private fun dispatchTouch(
        view: View,
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long
    ) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun evaluateOrQueue(js: String) {
        val view = webView
        if (view != null && view.isAttachedToWindow) {
            view.evaluateJavascript(js, null)
        } else {
            pendingJs = js
        }
    }

    private fun notifyInputFocused(value: String) {
        val now = SystemClock.uptimeMillis()
        if (now < suppressFocusUntil) return
        if (now - lastInputNotifyAt < INPUT_NOTIFY_DEBOUNCE_MS) return
        lastInputNotifyAt = now
        inputFocusListener?.invoke(value)
    }

    private fun parseFocusValue(result: String?, requireFocused: Boolean = false): String? {
        if (result.isNullOrBlank() || result == "null") return if (requireFocused) null else ""
        return runCatching {
            val json = JSONObject(result)
            if (requireFocused && !json.optBoolean("focused")) {
                null
            } else if (!json.optBoolean("focused", true)) {
                ""
            } else {
                json.optString("value")
            }
        }.getOrNull()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    companion object {
        private const val TAG = "AABrowserCar"
        private const val VIRTUAL_DISPLAY_NAME = "aa-browser-map"
        const val START_URL = "https://adam-skelton.github.io/AABrowser/"
        const val BRIDGE_NAME = "Car"
        private const val CLICK_DURATION_MS = 40L
        private const val FOCUS_CHECK_DELAY_MS = 180L
        private const val DRAG_END_DELAY_MS = 90L
        private const val INPUT_NOTIFY_DEBOUNCE_MS = 600L
        private const val SUBMIT_FOCUS_SUPPRESS_MS = 1500L

        private const val RESTORE_MAP_JS = """
            (function() {
              try {
                window.dispatchEvent(new Event('resize'));
                if (typeof window.__aaRestoreMap === 'function') window.__aaRestoreMap();
              } catch (err) {}
            })();
        """

        private const val FOCUS_HOOK_JS = """
            (function() {
              if (window.__aaBrowserInputHook) return;
              window.__aaBrowserInputHook = true;
              function isTextEntry(el) {
                if (!el || el === document.body || el === document.documentElement) return false;
                if (el.closest && el.closest('#searchHost')) return true;
                var tag = (el.tagName || '').toLowerCase();
                if (tag === 'gmp-place-autocomplete') return true;
                if (el.isContentEditable) return true;
                if (tag === 'textarea') return true;
                if (tag === 'input') {
                  var type = (el.getAttribute('type') || 'text').toLowerCase();
                  return ['hidden','checkbox','radio','button','submit','reset','file','image','range','color'].indexOf(type) < 0;
                }
                var role = (el.getAttribute('role') || '').toLowerCase();
                return role === 'combobox' || role === 'searchbox' || role === 'textbox';
              }
              function searchValue(el) {
                var ac = document.querySelector('gmp-place-autocomplete');
                if (ac && ac.value != null) return String(ac.value);
                return (el && el.value != null) ? String(el.value) : '';
              }
              document.addEventListener('focusin', function(e) {
                var el = e.target;
                if (!isTextEntry(el)) return;
                try { Car.onInputFocused(searchValue(el)); } catch (err) {}
              }, true);
              document.addEventListener('click', function(e) {
                var el = e.target;
                if (!el || !el.closest || !el.closest('#searchHost')) return;
                try { Car.onInputFocused(searchValue(el)); } catch (err) {}
              }, true);
            })();
        """

        private const val CHECK_FOCUS_JS = """
            (function() {
              function isTextEntry(el) {
                if (!el || el === document.body || el === document.documentElement) return false;
                if (el.closest && el.closest('#searchHost')) return true;
                var tag = (el.tagName || '').toLowerCase();
                if (tag === 'gmp-place-autocomplete') return true;
                if (el.isContentEditable) return true;
                if (tag === 'textarea') return true;
                if (tag === 'input') {
                  var type = (el.getAttribute('type') || 'text').toLowerCase();
                  return ['hidden','checkbox','radio','button','submit','reset','file','image','range','color'].indexOf(type) < 0;
                }
                var role = (el.getAttribute('role') || '').toLowerCase();
                return role === 'combobox' || role === 'searchbox' || role === 'textbox';
              }
              var el = document.activeElement;
              if (!isTextEntry(el)) return {focused:false, value:''};
              var ac = document.querySelector('gmp-place-autocomplete');
              var value = (ac && ac.value != null) ? ac.value : ((el.value != null) ? el.value : (el.textContent || ''));
              return {focused:true, value:String(value)};
            })();
        """

        private const val SET_INPUT_JS = """
            (function() {
              var text = TEXT_PLACEHOLDER;
              var submit = SUBMIT_PLACEHOLDER;
              if (typeof window.__aaSetSearchText === 'function') {
                window.__aaSetSearchText(text);
                if (submit && typeof window.__aaSubmitSearch === 'function') {
                  window.__aaSubmitSearch(text);
                }
                return true;
              }
              function isTextEntry(el) {
                if (!el || el === document.body || el === document.documentElement) return false;
                if (el.isContentEditable) return true;
                var tag = (el.tagName || '').toLowerCase();
                if (tag === 'textarea') return true;
                if (tag === 'input') {
                  var type = (el.getAttribute('type') || 'text').toLowerCase();
                  return ['hidden','checkbox','radio','button','submit','reset','file','image','range','color'].indexOf(type) < 0;
                }
                var role = (el.getAttribute('role') || '').toLowerCase();
                return role === 'combobox' || role === 'searchbox' || role === 'textbox';
              }
              var el = document.activeElement;
              if (!isTextEntry(el)) {
                el = document.querySelector('textarea[name="q"], input[name="q"], input[type="search"], textarea, input:not([type]), input[type="text"]');
                if (el) el.focus();
              }
              if (!isTextEntry(el)) return false;
              if (document.activeElement !== el) el.focus();
              if (el.isContentEditable) {
                el.textContent = text;
                el.dispatchEvent(new InputEvent('input', {bubbles:true, data:text, inputType:'insertReplacementText'}));
              } else {
                var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                if (desc && desc.set) desc.set.call(el, text); else el.value = text;
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
              }
              if (submit) {
                el.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true, cancelable:true}));
                el.dispatchEvent(new KeyboardEvent('keyup', {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true, cancelable:true}));
                if (el.form && typeof el.form.requestSubmit === 'function') {
                  try { el.form.requestSubmit(); } catch (err) {}
                }
              }
              return true;
            })();
        """
    }
}

package com.kododake.aabrowser.car

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.appcompat.content.res.AppCompatResources
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.kododake.aabrowser.R

class BrowserCarScreen(
    carContext: CarContext,
    private val webHost: CarWebViewHost
) : Screen(carContext) {

    private var keyboardVisible = false

    init {
        webHost.inputFocusListener = { value -> openKeyboard(value) }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                webHost.inputFocusListener = null
            }
        })
    }

    override fun onGetTemplate(): Template {
        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(searchAction())
                    .build()
            )
            .setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.PAN)
                    .build()
            )
            .setPanModeListener { _ -> }
            .build()
    }

    private fun searchAction(): Action {
        return Action.Builder()
            .setIcon(searchPinIcon())
            .setTitle(carContext.getString(R.string.car_action_search))
            .setOnClickListener { openKeyboard("") }
            .build()
    }

    // Rasterise the multi-colour pin so Android Auto cannot template-tint a vector.
    private fun searchPinIcon(): CarIcon {
        val drawable = AppCompatResources.getDrawable(carContext, R.drawable.gmaps_pin)
        if (drawable == null) {
            return CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.gmaps_pin)).build()
        }
        val srcW = drawable.intrinsicWidth.coerceAtLeast(1)
        val srcH = drawable.intrinsicHeight.coerceAtLeast(1)
        val maxPx = (48 * carContext.resources.displayMetrics.density).toInt().coerceAtLeast(48)
        val scale = maxPx.toFloat() / maxOf(srcW, srcH)
        val w = (srcW * scale).toInt().coerceAtLeast(1)
        val h = (srcH * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }

    private fun openKeyboard(initialText: String) {
        if (keyboardVisible) return
        keyboardVisible = true
        val keyboardScreen = CarKeyboardScreen(
            carContext = carContext,
            initialText = initialText,
            webHost = webHost,
            onTextChanged = { text -> webHost.setInputText(text, submit = false) },
            onSubmitted = { text -> webHost.setInputText(text, submit = true) }
        )
        keyboardScreen.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                keyboardVisible = false
            }
        })
        screenManager.push(keyboardScreen)
    }
}

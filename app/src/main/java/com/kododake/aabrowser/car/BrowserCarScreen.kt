package com.kododake.aabrowser.car

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
                    .addAction(
                        Action.Builder()
                            .setIcon(
                                CarIcon.Builder(
                                    IconCompat.createWithResource(carContext, R.mipmap.ic_launcher)
                                ).build()
                            )
                            .setOnClickListener { }
                            .build()
                    )
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

    private fun openKeyboard(initialText: String) {
        if (keyboardVisible) return
        keyboardVisible = true
        val keyboardScreen = CarKeyboardScreen(
            carContext = carContext,
            initialText = initialText,
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

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
        val actions = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.car_action_back))
                    .setIcon(
                        CarIcon.Builder(
                            IconCompat.createWithResource(carContext, R.drawable.arrow_back_24px)
                        ).build()
                    )
                    .setOnClickListener { webHost.goBack() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.car_action_reload))
                    .setIcon(
                        CarIcon.Builder(
                            IconCompat.createWithResource(carContext, R.drawable.refresh_24px)
                        ).build()
                    )
                    .setOnClickListener { webHost.reload() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.car_action_keyboard))
                    .setIcon(
                        CarIcon.Builder(
                            IconCompat.createWithResource(carContext, R.drawable.search_24px)
                        ).build()
                    )
                    .setOnClickListener {
                        webHost.readFocusedInputValue { openKeyboard(it) }
                    }
                    .build()
            )
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(actions)
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

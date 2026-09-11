package com.kododake.aabrowser.car

import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.kododake.aabrowser.R

object MapBootOverlay {
    fun startBounce(overlay: View) {
        val pin = overlay.findViewById<View>(R.id.mapBootPin) ?: return
        if (pin.tag == "map-boot-bounce") return
        pin.tag = "map-boot-bounce"
        ObjectAnimator.ofFloat(pin, View.TRANSLATION_Y, 0f, -dp(pin, 10f)).apply {
            duration = 550L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun dp(view: View, value: Float): Float {
        return value * view.resources.displayMetrics.density
    }
}

package com.kododake.aabrowser.car

import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.kododake.aabrowser.R

object MapBootOverlay {
    fun startBounce(overlay: View) {
        val pin = overlay.findViewById<View>(R.id.mapBootPin) ?: return
        if (pin.tag is ObjectAnimator) return
        val bounce = ObjectAnimator.ofFloat(pin, View.TRANSLATION_Y, 0f, -dp(pin, 10f)).apply {
            duration = 550L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }
        pin.tag = bounce
        bounce.start()
    }

    fun stopBounce(overlay: View) {
        val pin = overlay.findViewById<View>(R.id.mapBootPin) ?: return
        (pin.tag as? ObjectAnimator)?.cancel()
        pin.tag = null
        pin.translationY = 0f
    }

    private fun dp(view: View, value: Float): Float {
        return value * view.resources.displayMetrics.density
    }
}

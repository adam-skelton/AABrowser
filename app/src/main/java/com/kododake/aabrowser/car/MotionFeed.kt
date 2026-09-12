package com.kododake.aabrowser.car

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/**
 * World-frame horizontal acceleration for the page's speed filter (Vela's MotionProvider):
 * TYPE_LINEAR_ACCELERATION (gravity already removed) rotated into East/North with
 * TYPE_ROTATION_VECTOR, low-passed, and handed to `window.__aaInjectMotion(east, north)` at
 * a few hertz. Between 1 Hz GPS fixes the page integrates it so the drawn car brakes and
 * launches with the real one. Either sensor missing -> nothing is emitted and the page
 * coasts at constant speed, exactly as before.
 */
class MotionFeed(
    context: Context,
    private val emit: (js: String) -> Unit
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rotation = FloatArray(9)
    private var haveRotation = false
    private var east = 0f
    private var north = 0f
    private var lastEmitMs = 0L
    private var running = false

    val available: Boolean get() = accelSensor != null && rotationSensor != null

    fun start() {
        val manager = sensorManager ?: return
        if (running || !available) return
        running = true
        manager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        manager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager?.unregisterListener(this)
        haveRotation = false
        east = 0f
        north = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                haveRotation = true
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (!haveRotation) return
                // Device -> world: rows of the rotation matrix are the world axes (x = East,
                // y = North); the vertical component is dropped.
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val e0 = rotation[0] * ax + rotation[1] * ay + rotation[2] * az
                val n0 = rotation[3] * ax + rotation[4] * ay + rotation[5] * az
                // Low-pass (~0.2 s at the UI rate): braking is sustained, vibration is not.
                east += (e0 - east) * LP_ALPHA
                north += (n0 - north) * LP_ALPHA
                val now = SystemClock.uptimeMillis()
                if (now - lastEmitMs < EMIT_INTERVAL_MS) return
                lastEmitMs = now
                emit("window.__aaInjectMotion && window.__aaInjectMotion($east,$north);")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private companion object {
        const val LP_ALPHA = 0.25f
        const val EMIT_INTERVAL_MS = 100L
    }
}

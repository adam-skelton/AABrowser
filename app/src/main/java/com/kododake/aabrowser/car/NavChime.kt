package com.kododake.aabrowser.car

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

/**
 * Short turn chime on the navigation-guidance stream. WebAudio inside the car
 * WebView stays silent, so the page asks the host to play this instead.
 */
object NavChime {
    private const val TAG = "NavChime"
    private const val SAMPLE_RATE = 22050
    private val executor = Executors.newSingleThreadExecutor()

    fun play(context: Context, side: String?) {
        val app = context.applicationContext
        val which = when (side) {
            "left", "right" -> side
            else -> "both"
        }
        executor.execute {
            try {
                playBlocking(app, which)
            } catch (error: Exception) {
                Log.w(TAG, "Turn chime failed", error)
            }
        }
    }

    private fun playBlocking(context: Context, side: String) {
        val pcm = tonePcm(side)
        val bytes = ByteArray(pcm.size * 2)
        var o = 0
        for (sample in pcm) {
            bytes[o++] = (sample.toInt() and 0xff).toByte()
            bytes[o++] = ((sample.toInt() shr 8) and 0xff).toByte()
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val min = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, bytes.size))
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            Log.w(TAG, "AudioTrack did not initialize")
            return
        }
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        manager.requestAudioFocus(focus)
        try {
            track.play()
            track.write(bytes, 0, bytes.size)
            val durationMs = pcm.size * 500L / SAMPLE_RATE + 120L
            Thread.sleep(durationMs)
        } finally {
            runCatching { track.stop() }
            track.release()
            runCatching { manager.abandonAudioFocusRequest(focus) }
        }
    }

    private fun tonePcm(side: String): ShortArray {
        val notes = intArrayOf(370, 494)
        val toneSamples = (0.11 * SAMPLE_RATE).toInt()
        val gapSamples = (0.13 * SAMPLE_RATE).toInt()
        val total = notes.size * (toneSamples + gapSamples)
        val pcm = ShortArray(total * 2)
        var offset = 0
        for (i in notes.indices) {
            val freq = notes[i]
            val peak = if (i == 0) 0.42 else 0.5
            for (n in 0 until toneSamples) {
                val u = n.toDouble() / toneSamples
                val env = when {
                    u < 0.08 -> u / 0.08
                    else -> (1.0 - u) / 0.92
                }.coerceIn(0.0, 1.0)
                val wave = sin(2.0 * PI * freq * n / SAMPLE_RATE)
                val sample = (wave * peak * env * 32767.0).toInt().coerceIn(-32767, 32767)
                val left = if (side == "right") 0 else sample
                val right = if (side == "left") 0 else sample
                pcm[(offset + n) * 2] = left.toShort()
                pcm[(offset + n) * 2 + 1] = right.toShort()
            }
            offset += toneSamples + gapSamples
        }
        return pcm
    }
}

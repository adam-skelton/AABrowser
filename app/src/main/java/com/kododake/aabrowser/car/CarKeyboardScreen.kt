package com.kododake.aabrowser.car

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.SearchTemplate.SearchCallback
import androidx.car.app.model.Template
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.kododake.aabrowser.R
import java.util.Locale

class CarKeyboardScreen(
    carContext: CarContext,
    private val initialText: String,
    private val webHost: CarWebViewHost,
    private val onTextChanged: (String) -> Unit,
    private val onSubmitted: (String) -> Unit
) : Screen(carContext) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var typedText = initialText
    private var suggestions: List<SearchSuggestion> = emptyList()
    private var listening = false
    private var voiceHint: String? = null
    private var heardText = ""
    private var lastPartialAt = 0L
    private var speechRecognizer: SpeechRecognizer? = null
    // Once a letter has arrived, later refreshes must not push text back into
    // the open keyboard. Doing that drops the input connection and the host
    // kills the app.
    private var userEditing = false
    private var forceSearchText = false
    private var retainSearchText = false
    private var micTone: ToneGenerator? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                webHost.searchSuggestionsListener = { query, items ->
                    val current = typedText.trim()
                    val matches = query.equals(current, ignoreCase = true) || (query.isEmpty() && current.isEmpty())
                    if (matches) {
                        suggestions = items
                        invalidate()
                    }
                }
                onTextChanged(typedText)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                releaseRecognizer()
                stopMicSession()
                try {
                    micTone?.release()
                } catch (_: Exception) {
                }
                micTone = null
                if (webHost.searchSuggestionsListener != null) {
                    webHost.searchSuggestionsListener = null
                }
            }
        })
    }

    override fun onGetTemplate(): Template {
        val callback = object : SearchCallback {
            override fun onSearchTextChanged(searchText: String) {
                // Rebuilding the template while listening makes the host echo an
                // empty field, which used to wipe the words already heard.
                if (listening) return
                if (retainSearchText) {
                    if (searchText == typedText || searchText.isEmpty()) return
                    retainSearchText = false
                }
                userEditing = true
                typedText = searchText
                onTextChanged(searchText)
            }

            override fun onSearchSubmitted(searchText: String) {
                typedText = searchText
                onSubmitted(searchText)
            }
        }

        val pushText = forceSearchText || !userEditing || retainSearchText
        val builder = SearchTemplate.Builder(callback)
            .setHeaderAction(Action.BACK)
            .setShowKeyboardByDefault(!listening && !retainSearchText && (forceSearchText || !userEditing))
            .setSearchHint(searchHintText())
            .setItemList(suggestionList())
            .setActionStrip(searchActions())
        if (pushText) {
            builder.setInitialSearchText(typedText)
            forceSearchText = false
        }
        return builder.build()
    }

    // One strip action only. A second action (Clear) is rejected while the
    // keyboard is up, which crashed the app on the first letter.
    private fun searchActions(): ActionStrip {
        return ActionStrip.Builder().addAction(voiceAction()).build()
    }

    private fun voiceAction(): Action {
        return Action.Builder()
            .setTitle(carContext.getString(
                if (listening) R.string.car_keyboard_listening else R.string.car_keyboard_voice
            ))
            .setIcon(
                CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_mic)).build()
            )
            .setOnClickListener {
                if (listening) finishVoice(null) else startVoice()
            }
            .build()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun startVoice() {
        val permission = Manifest.permission.RECORD_AUDIO
        val appContext = carContext.applicationContext
        if (ContextCompat.checkSelfPermission(appContext, permission) != PackageManager.PERMISSION_GRANTED) {
            carContext.requestPermissions(listOf(permission)) { granted, _ ->
                if (granted.contains(permission)) onMain { startVoice() }
            }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            voiceHint = carContext.getString(R.string.car_keyboard_voice_unavailable)
            invalidate()
            return
        }
        releaseRecognizer()
        listening = true
        heardText = ""
        voiceHint = null
        // Android Auto keeps this process in the background, and the mic is
        // blocked there unless a microphone foreground service is running.
        startMicSession()
        try {
            speechRecognizer = createRecognizer(appContext).also { recognizer ->
                recognizer.setRecognitionListener(voiceListener)
                val language = Locale.getDefault().toLanguageTag()
                recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
                    if (language.isNotBlank()) {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
                    }
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                })
            }
            playMicTone(starting = true)
        } catch (_: Exception) {
            listening = false
            heardText = ""
            speechRecognizer = null
            stopMicSession()
            voiceHint = carContext.getString(R.string.car_keyboard_voice_unavailable)
        }
        invalidate()
    }

    private fun searchHintText(): String {
        if (listening && heardText.isNotBlank()) return heardText
        voiceHint?.let { return it }
        return carContext.getString(
            if (listening) R.string.car_keyboard_listening else R.string.car_keyboard_hint
        )
    }

    private fun createRecognizer(context: android.content.Context): SpeechRecognizer {
        val component = recognitionService(context)
        return if (component != null) {
            SpeechRecognizer.createSpeechRecognizer(context, component)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }

    private fun recognitionService(context: android.content.Context): ComponentName? {
        val services = context.packageManager.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE),
            0
        )
        val match = services.firstOrNull {
            it.serviceInfo?.packageName == "com.google.android.googlequicksearchbox"
        } ?: services.firstOrNull()
        val info = match?.serviceInfo ?: return null
        if (info.packageName.isNullOrBlank() || info.name.isNullOrBlank()) return null
        return ComponentName(info.packageName, info.name)
    }

    private fun startMicSession() {
        val appContext = carContext.applicationContext
        try {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, CarVoiceCaptureService::class.java)
            )
        } catch (_: Exception) {
        }
    }

    private fun stopMicSession() {
        val appContext = carContext.applicationContext
        try {
            appContext.startService(
                Intent(appContext, CarVoiceCaptureService::class.java)
                    .setAction(CarVoiceCaptureService.ACTION_STOP)
            )
        } catch (_: Exception) {
            try {
                appContext.stopService(Intent(appContext, CarVoiceCaptureService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private fun finishVoice(text: String?) {
        listening = false
        releaseRecognizer()
        stopMicSession()
        val spoken = text?.trim().orEmpty().ifBlank { heardText.trim() }
        heardText = ""
        if (spoken.isNotBlank()) {
            typedText = spoken
            voiceHint = null
            userEditing = true
            retainSearchText = true
            forceSearchText = true
            onTextChanged(typedText)
        } else {
            retainSearchText = false
        }
        playMicTone(starting = false)
        invalidate()
    }

    private fun playMicTone(starting: Boolean) {
        try {
            micTone?.release()
        } catch (_: Exception) {
        }
        micTone = null
        try {
            val generator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            micTone = generator
            val tone = if (starting) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_BEEP2
            generator.startTone(tone, 180)
            mainHandler.postDelayed({
                if (micTone !== generator) return@postDelayed
                try {
                    generator.release()
                } catch (_: Exception) {
                }
                micTone = null
            }, 500L)
        } catch (_: Exception) {
            micTone = null
        }
    }

    private fun transcript(bundle: Bundle?): String {
        if (bundle == null) return ""
        val stable = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (stable.isNotEmpty()) return stable
        return bundle.getString("android.speech.extra.UNSTABLE_TEXT")?.trim().orEmpty()
    }

    private fun releaseRecognizer() {
        val recognizer = speechRecognizer ?: return
        speechRecognizer = null
        try {
            recognizer.destroy()
        } catch (_: Exception) {
        }
    }

    private val voiceListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = transcript(partialResults)
            if (text.isEmpty()) return
            onMain {
                if (!listening || text == heardText) return@onMain
                heardText = text
                typedText = text
                val now = SystemClock.uptimeMillis()
                if (now - lastPartialAt < 200L) return@onMain
                lastPartialAt = now
                forceSearchText = true
                invalidate()
            }
        }

        override fun onResults(results: Bundle?) {
            val text = transcript(results)
            onMain { finishVoice(text) }
        }

        override fun onError(error: Int) {
            onMain {
                if (!listening) return@onMain
                val missed = error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (missed && heardText.isNotBlank()) {
                    finishVoice(heardText)
                    return@onMain
                }
                listening = false
                heardText = ""
                releaseRecognizer()
                stopMicSession()
                playMicTone(starting = false)
                voiceHint = carContext.getString(
                    if (missed) R.string.car_keyboard_voice_missed
                    else R.string.car_keyboard_voice_unavailable
                )
                invalidate()
            }
        }
    }

    private fun listLimit(): Int {
        return runCatching {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(6).coerceIn(1, 6)
    }

    private fun clipRowText(value: String): String {
        return value.replace(Regex("\\s+"), " ").trim().take(80)
    }

    private fun suggestionList(): ItemList {
        val builder = ItemList.Builder()
        var room = listLimit()
        if (typedText.isNotBlank() && room > 0) {
            builder.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_keyboard_clear))
                    .addText(carContext.getString(R.string.car_keyboard_clear_hint))
                    .setOnClickListener {
                        typedText = ""
                        suggestions = emptyList()
                        voiceHint = null
                        retainSearchText = false
                        forceSearchText = true
                        onTextChanged("")
                        invalidate()
                    }
                    .build()
            )
            room -= 1
        }
        if (suggestions.isEmpty() && typedText.isBlank()) {
            builder.setNoItemsMessage(carContext.getString(R.string.car_keyboard_empty))
            return builder.build()
        }
        suggestions.take(room).forEach { hit ->
            val title = clipRowText(hit.title).ifBlank { return@forEach }
            val row = Row.Builder().setTitle(title)
            val subtitle = clipRowText(hit.subtitle)
            if (subtitle.isNotBlank()) row.addText(subtitle)
            runCatching { row.setImage(suggestionIcon(hit.placeId), Row.IMAGE_TYPE_ICON) }
            row.setOnClickListener {
                webHost.chooseSearchSuggestion(hit.placeId, hit.title)
                screenManager.pop()
            }
            builder.addItem(row.build())
        }
        return builder.build()
    }

    /**
     * Leading glyph for a result row. Browse-catalog entries ("__map:<category>") get their
     * category icon in the same accent colour the web UI uses; place predictions get a pin.
     */
    private fun suggestionIcon(placeId: String): CarIcon {
        val category = placeId.removePrefix("__map:").takeIf { placeId.startsWith("__map:") }
        val (res, color) = when (category) {
            "custom" -> R.drawable.ic_cat_map to 0xFF0F9D58
            "gas_station" -> R.drawable.ic_cat_fuel to 0xFFF9AB00
            "restaurant" -> R.drawable.ic_cat_restaurant to 0xFFEA4335
            "cafe" -> R.drawable.ic_cat_cafe to 0xFFA142F4
            "supermarket" -> R.drawable.ic_cat_supermarket to 0xFF34A853
            "parking" -> R.drawable.ic_cat_parking to 0xFF1A73E8
            "pharmacy" -> R.drawable.ic_cat_pharmacy to 0xFF129EAF
            "atm" -> R.drawable.ic_cat_atm to 0xFF188038
            "hospital" -> R.drawable.ic_cat_hospital to 0xFFD93025
            "lodging" -> R.drawable.ic_cat_hotel to 0xFF5F6368
            else -> R.drawable.ic_cat_place to 0L
        }
        val builder = CarIcon.Builder(IconCompat.createWithResource(carContext, res))
        if (color != 0L) {
            // Slightly lighter variant keeps the tint legible on the host's dark surfaces.
            builder.setTint(CarColor.createCustom(color.toInt(), lighten(color.toInt())))
        }
        return builder.build()
    }

    private fun lighten(color: Int): Int {
        val r = ((color shr 16) and 0xFF)
        val g = ((color shr 8) and 0xFF)
        val b = (color and 0xFF)
        fun up(c: Int) = (c + (255 - c) * 0.35f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (up(r) shl 16) or (up(g) shl 8) or up(b)
    }
}

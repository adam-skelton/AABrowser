package com.kododake.aabrowser.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
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
    private var speechRecognizer: SpeechRecognizer? = null
    // Once a letter has arrived, later refreshes must not push text back into
    // the open keyboard. Doing that drops the input connection and the host
    // kills the app.
    private var userEditing = false
    private var forceSearchText = false

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
                if (webHost.searchSuggestionsListener != null) {
                    webHost.searchSuggestionsListener = null
                }
            }
        })
    }

    override fun onGetTemplate(): Template {
        val callback = object : SearchCallback {
            override fun onSearchTextChanged(searchText: String) {
                userEditing = true
                typedText = searchText
                onTextChanged(searchText)
            }

            override fun onSearchSubmitted(searchText: String) {
                typedText = searchText
                onSubmitted(searchText)
            }
        }

        val pushText = forceSearchText || !userEditing
        val builder = SearchTemplate.Builder(callback)
            .setHeaderAction(Action.BACK)
            .setShowKeyboardByDefault(!listening && (forceSearchText || !userEditing))
            .setSearchHint(voiceHint ?: carContext.getString(
                if (listening) R.string.car_keyboard_listening else R.string.car_keyboard_hint
            ))
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
        voiceHint = null
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also { recognizer ->
                recognizer.setRecognitionListener(voiceListener)
                recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
                })
            }
        } catch (_: Exception) {
            listening = false
            speechRecognizer = null
            voiceHint = carContext.getString(R.string.car_keyboard_voice_unavailable)
        }
        invalidate()
    }

    private fun finishVoice(text: String?) {
        listening = false
        releaseRecognizer()
        if (!text.isNullOrBlank()) {
            typedText = text.trim()
            voiceHint = null
            userEditing = true
            forceSearchText = true
            onTextChanged(typedText)
        }
        invalidate()
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
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isEmpty()) return
            onMain { typedText = text }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            onMain { finishVoice(text.ifEmpty { typedText }) }
        }

        override fun onError(error: Int) {
            onMain {
                if (!listening) return@onMain
                listening = false
                releaseRecognizer()
                voiceHint = carContext.getString(R.string.car_keyboard_voice_unavailable)
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

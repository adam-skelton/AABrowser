package com.kododake.aabrowser.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.car.app.CarContext
import androidx.car.app.Screen
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
    private val onSubmitted: (String) -> Unit,
    private val onClearRequest: () -> Unit = {}
) : Screen(carContext) {

    private var typedText = initialText
    private var suggestions: List<SearchSuggestion> = emptyList()
    private var listening = false
    private var voiceHint: String? = null
    private var speechRecognizer: SpeechRecognizer? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                webHost.searchSuggestionsListener = { query, items ->
                    val catalog = items.any { it.placeId.startsWith("__map") }
                    if (query.isEmpty() || query.equals(typedText.trim(), ignoreCase = true) || catalog) {
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
                typedText = searchText
                onTextChanged(searchText)
            }

            override fun onSearchSubmitted(searchText: String) {
                typedText = searchText
                onSubmitted(searchText)
            }
        }

        val builder = SearchTemplate.Builder(callback)
            .setHeaderAction(Action.BACK)
            .setShowKeyboardByDefault(!listening)
            .setSearchHint(voiceHint ?: carContext.getString(
                if (listening) R.string.car_keyboard_listening else R.string.car_keyboard_hint
            ))
            .setInitialSearchText(typedText)
            .setItemList(suggestionList())
            .setActionStrip(searchActions())
        return builder.build()
    }

    // The mic drawn on the car keyboard is the host's button. It does not deliver
    // audio to the app, so Voice on this screen runs dictation and fills the box.
    private fun searchActions(): ActionStrip {
        val strip = ActionStrip.Builder().addAction(voiceAction())
        if (typedText.isNotBlank() && !listening) {
            strip.addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.car_keyboard_clear))
                    .setOnClickListener {
                        typedText = ""
                        voiceHint = null
                        onTextChanged("")
                        onClearRequest()
                    }
                    .build()
            )
        }
        return strip.build()
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

    private fun startVoice() {
        val permission = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(carContext, permission) != PackageManager.PERMISSION_GRANTED) {
            carContext.requestPermissions(listOf(permission)) { granted, _ ->
                if (granted.contains(permission)) startVoice()
            }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(carContext)) {
            voiceHint = carContext.getString(R.string.car_keyboard_voice_unavailable)
            invalidate()
            return
        }
        releaseRecognizer()
        listening = true
        voiceHint = null
        invalidate()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(carContext).also { recognizer ->
            recognizer.setRecognitionListener(voiceListener)
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            })
        }
    }

    private fun finishVoice(text: String?) {
        listening = false
        releaseRecognizer()
        if (!text.isNullOrBlank()) {
            typedText = text.trim()
            voiceHint = null
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
            if (text.isNotEmpty()) typedText = text
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
                .ifEmpty { typedText }
            finishVoice(text)
        }

        override fun onError(error: Int) {
            if (!listening) return
            listening = false
            releaseRecognizer()
            invalidate()
        }
    }

    private fun suggestionList(): ItemList {
        val builder = ItemList.Builder()
        if (typedText.isNotBlank()) {
            builder.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_keyboard_clear))
                    .addText(carContext.getString(R.string.car_keyboard_clear_hint))
                    .setOnClickListener {
                        typedText = ""
                        onTextChanged("")
                        onClearRequest()
                    }
                    .build()
            )
        }
        if (suggestions.isEmpty() && typedText.isBlank()) {
            builder.setNoItemsMessage(carContext.getString(R.string.car_keyboard_empty))
            return builder.build()
        }
        suggestions.take(12).forEach { hit ->
            val row = Row.Builder().setTitle(hit.title)
            if (hit.subtitle.isNotBlank()) {
                row.addText(hit.subtitle)
            }
            row.setImage(suggestionIcon(hit.placeId), Row.IMAGE_TYPE_ICON)
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

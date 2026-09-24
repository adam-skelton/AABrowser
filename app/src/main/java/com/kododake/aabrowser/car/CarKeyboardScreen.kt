package com.kododake.aabrowser.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.appcompat.content.res.AppCompatResources
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.SearchTemplate.SearchCallback
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.DrawableCompat
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
            .setShowKeyboardByDefault(true)
            .setSearchHint(carContext.getString(R.string.car_keyboard_hint))
            .setInitialSearchText(typedText)
            .setItemList(suggestionList())
        if (typedText.isNotBlank()) {
            builder.setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(carContext.getString(R.string.car_keyboard_clear))
                            .setOnClickListener {
                                typedText = ""
                                onTextChanged("")
                                onClearRequest()
                            }
                            .build()
                    )
                    .build()
            )
        }
        return builder.build()
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
            row.setImage(suggestionIcon(hit.placeId), Row.IMAGE_TYPE_LARGE)
            row.setOnClickListener {
                webHost.chooseSearchSuggestion(hit.placeId, hit.title)
                screenManager.pop()
            }
            builder.addItem(row.build())
        }
        return builder.build()
    }

    /**
     * Leading glyph for a result row. The host tints [Row.IMAGE_TYPE_ICON] white and
     * ignores custom [androidx.car.app.model.CarColor]s, so the colour is painted into
     * a bitmap and sent as a large image, which the host leaves alone.
     */
    private fun suggestionIcon(placeId: String): CarIcon {
        val category = placeId.removePrefix("__map:").takeIf { placeId.startsWith("__map:") }
        val (res, color) = when (category) {
            "custom" -> R.drawable.ic_cat_map to 0xFF0F9D58.toInt()
            "gas_station" -> R.drawable.ic_cat_fuel to 0xFFF9AB00.toInt()
            "restaurant" -> R.drawable.ic_cat_restaurant to 0xFFEA4335.toInt()
            "cafe" -> R.drawable.ic_cat_cafe to 0xFFA142F4.toInt()
            "supermarket" -> R.drawable.ic_cat_supermarket to 0xFF34A853.toInt()
            "parking" -> R.drawable.ic_cat_parking to 0xFF1A73E8.toInt()
            "pharmacy" -> R.drawable.ic_cat_pharmacy to 0xFF129EAF.toInt()
            "atm" -> R.drawable.ic_cat_atm to 0xFF188038.toInt()
            "hospital" -> R.drawable.ic_cat_hospital to 0xFFD93025.toInt()
            "lodging" -> R.drawable.ic_cat_hotel to 0xFF5F6368.toInt()
            else -> R.drawable.ic_cat_place to 0xFF1A73E8.toInt()
        }
        iconCache[res]?.let { return it }
        val icon = coloredChipIcon(res, color)
        iconCache[res] = icon
        return icon
    }

    private fun coloredChipIcon(res: Int, color: Int): CarIcon {
        val density = carContext.resources.displayMetrics.density
        val size = (48 * density).toInt().coerceIn(48, 128)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val radius = size * 0.22f
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), radius, radius, paint)
        val drawable = AppCompatResources.getDrawable(carContext, res)?.mutate()
        if (drawable != null) {
            DrawableCompat.setTint(drawable, 0xFFFFFFFF.toInt())
            val inset = (size * 0.22f).toInt()
            drawable.setBounds(inset, inset, size - inset, size - inset)
            drawable.draw(canvas)
        }
        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }

    private companion object {
        val iconCache = HashMap<Int, CarIcon>()
    }
}

package com.kododake.aabrowser.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.SearchTemplate.SearchCallback
import androidx.car.app.model.Template
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

    private var typedText = initialText
    private var suggestions: List<SearchSuggestion> = emptyList()

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                webHost.searchSuggestionsListener = { query, items ->
                    if (query.isEmpty() || query.equals(typedText.trim(), ignoreCase = true)) {
                        suggestions = items
                        invalidate()
                    }
                }
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
                onSubmitted(searchText)
                screenManager.pop()
            }
        }

        return SearchTemplate.Builder(callback)
            .setHeaderAction(Action.BACK)
            .setShowKeyboardByDefault(true)
            .setSearchHint(carContext.getString(R.string.car_keyboard_hint))
            .setInitialSearchText(typedText)
            .setItemList(suggestionList())
            .build()
    }

    private fun suggestionList(): ItemList {
        val builder = ItemList.Builder()
        if (suggestions.isEmpty()) {
            builder.setNoItemsMessage(carContext.getString(R.string.car_keyboard_empty))
            return builder.build()
        }
        suggestions.take(6).forEach { hit ->
            val row = Row.Builder().setTitle(hit.title)
            if (hit.subtitle.isNotBlank()) {
                row.addText(hit.subtitle)
            }
            row.setOnClickListener {
                webHost.chooseSearchSuggestion(hit.placeId, hit.title)
                screenManager.pop()
            }
            builder.addItem(row.build())
        }
        return builder.build()
    }
}

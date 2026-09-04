package com.kododake.aabrowser.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.SearchTemplate.SearchCallback
import androidx.car.app.model.Template
import com.kododake.aabrowser.R

class CarKeyboardScreen(
    carContext: CarContext,
    private val initialText: String,
    private val onTextChanged: (String) -> Unit,
    private val onSubmitted: (String) -> Unit
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val callback = object : SearchCallback {
            override fun onSearchTextChanged(searchText: String) {
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
            .setInitialSearchText(initialText)
            .setItemList(
                ItemList.Builder()
                    .setNoItemsMessage(carContext.getString(R.string.car_keyboard_empty))
                    .build()
            )
            .build()
    }
}

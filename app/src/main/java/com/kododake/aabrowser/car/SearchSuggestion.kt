package com.kododake.aabrowser.car

import org.json.JSONObject

data class SearchSuggestion(
    val placeId: String,
    val title: String,
    val subtitle: String
) {
    companion object {
        fun parse(json: String?): Pair<String, List<SearchSuggestion>> {
            if (json.isNullOrBlank() || json == "null") return "" to emptyList()
            return runCatching {
                val obj = JSONObject(json)
                val query = obj.optString("query")
                val items = obj.optJSONArray("items") ?: return query to emptyList()
                val hits = buildList {
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val title = item.optString("title").trim()
                        val placeId = item.optString("placeId").trim()
                        if (title.isEmpty() && placeId.isEmpty()) continue
                        add(
                            SearchSuggestion(
                                placeId = placeId,
                                title = title.ifBlank { placeId },
                                subtitle = item.optString("subtitle").trim()
                            )
                        )
                    }
                }
                query to hits
            }.getOrElse { "" to emptyList() }
        }
    }
}

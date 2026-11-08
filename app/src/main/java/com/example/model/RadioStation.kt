package com.example.model

import org.json.JSONObject

/**
 * Represents an external audio / radio station discovered from the user's Xtream subscription.
 * Typically either an ALWAN or beIN SPORTS audio commentary stream.
 */
data class RadioStation(
    val id: String,
    val name: String,
    val categoryName: String,
    val group: String, // "ALWAN" or "BEIN_SPORTS"
    val containerExtension: String = "ts",
    val number: Int = 0,
    val logoUrl: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("categoryName", categoryName)
        put("group", group)
        put("containerExtension", containerExtension)
        put("number", number)
        put("logoUrl", logoUrl)
    }

    companion object {
        fun fromJson(json: JSONObject): RadioStation = RadioStation(
            id = json.optString("id", "").trim(),
            name = json.optString("name", "").trim(),
            categoryName = json.optString("categoryName", "").trim(),
            group = json.optString("group", "BEIN_SPORTS").trim(),
            containerExtension = json.optString("containerExtension", "ts").trim().removePrefix("."),
            number = json.optInt("number", 0),
            logoUrl = json.optString("logoUrl", "").trim()
        )
    }
}

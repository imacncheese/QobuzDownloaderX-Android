package com.qbdlx.mobile.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Qobuz returns IDs sometimes as JSON numbers and sometimes as strings
 * (e.g. album.id is a string on /album/get but a number inside search results).
 * Normalising here keeps the rest of the code free of that quirk.
 */
fun JsonElement?.asStringOrNull(): String? {
    if (this == null || this is JsonNull) return null
    val prim = this as? JsonPrimitive ?: return null
    val raw = prim.content
    return raw.ifBlank { null }
}

fun JsonElement?.asLongOrNull(): Long? = asStringOrNull()?.toLongOrNull()

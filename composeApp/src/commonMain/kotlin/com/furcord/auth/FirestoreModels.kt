package com.furcord.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Lenient JSON decoder for Firestore REST API.
 * - ignoreUnknownKeys: extra fields in response are ignored
 * - isLenient: relaxed parsing (unquoted keys, etc.)
 * - coerceInputValues: missing optional fields fall back to defaults
 */
internal val fsJson = Json {
    ignoreUnknownKeys = true
    isLenient         = true
    coerceInputValues = true
}

/**
 * A single Firestore field value. Firestore REST wraps every value in a typed object.
 * Example: {"stringValue": "hello"} or {"integerValue": "1234567890"}
 * Note: integerValue is always returned as a JSON string by Firestore REST.
 */
@Serializable
data class FsValue(
    @SerialName("stringValue")  val string:  String?       = null,
    @SerialName("integerValue") val integer: String?       = null,
    @SerialName("booleanValue") val boolean: Boolean?      = null,
    @SerialName("arrayValue")   val array:   FsArrayValue? = null,
    @SerialName("mapValue")     val map:     FsMapValue?   = null,
)

@Serializable
data class FsArrayValue(
    @SerialName("values") val values: List<FsValue> = emptyList(),
)

@Serializable
data class FsMapValue(
    @SerialName("fields") val fields: Map<String, FsValue> = emptyMap(),
)

/**
 * A single Firestore document.
 * - name: full resource path, e.g. "projects/.../documents/servers/abc123"
 * - fields: map of field name → FsValue
 */
@Serializable
data class FsDocument(
    @SerialName("name")   val name:   String               = "",
    @SerialName("fields") val fields: Map<String, FsValue> = emptyMap(),
)

/**
 * Response from GET /collection (list documents).
 * {"documents": [...]}
 */
@Serializable
data class FsListResponse(
    @SerialName("documents") val documents: List<FsDocument> = emptyList(),
)

/**
 * One item from a POST :runQuery response.
 * The array may contain items without a "document" key (no-match markers).
 * [{"document": {...}}, {}, {"document": {...}}]
 */
@Serializable
data class FsQueryItem(
    @SerialName("document") val document: FsDocument? = null,
)

// ── Convenience extensions on Map<String, FsValue> ──────────────────────────

/** Returns the stringValue or empty string if missing/null. */
internal fun Map<String, FsValue>.str(key: String): String = this[key]?.string ?: ""

/** Returns the integerValue parsed as Long, or 0 if missing/null. */
internal fun Map<String, FsValue>.lng(key: String): Long = this[key]?.integer?.toLongOrNull() ?: 0L

/** Returns the booleanValue or false if missing/null. */
internal fun Map<String, FsValue>.bool(key: String): Boolean = this[key]?.boolean ?: false

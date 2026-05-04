package com.furcord.auth

import java.io.File

/**
 * Basit anahtar-değer kalıcı ayar deposu.
 * Dosya: ~/.furcord_prefs  (her satır key=value)
 */
object AppPrefs {

    private val file: File
        get() = File(System.getProperty("user.home"), ".furcord_prefs")

    private fun readAll(): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        runCatching {
            file.readLines().forEach { line ->
                val idx = line.indexOf('=')
                if (idx > 0) map[line.substring(0, idx)] = line.substring(idx + 1)
            }
        }
        return map
    }

    private fun writeAll(map: Map<String, String>) {
        runCatching {
            file.writeText(map.entries.joinToString("\n") { "${it.key}=${it.value}" })
        }
    }

    fun get(key: String, default: String = ""): String = readAll()[key] ?: default

    fun set(key: String, value: String) {
        val map = readAll()
        map[key] = value
        writeAll(map)
    }

    // ── Typed helpers ─────────────────────────────────────────────────────────
    fun getFloat(key: String, default: Float = 1f): Float =
        get(key).toFloatOrNull() ?: default

    fun setFloat(key: String, value: Float) = set(key, value.toString())

    // ── Well-known keys ───────────────────────────────────────────────────────
    var micGain: Float
        get()      = getFloat("micGain", 1f).coerceIn(0f, 4f)
        set(value) { setFloat("micGain", value.coerceIn(0f, 4f)) }
}

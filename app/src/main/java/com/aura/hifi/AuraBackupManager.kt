package com.aura.hifi

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Portable, versioned backup for AURA's settings, library preferences and saved queue.
 * Audio files themselves are never copied; playlist entries retain their content URIs.
 */
object AuraBackupManager {
    private const val FORMAT = "AURA_HIFI_BACKUP"
    private const val VERSION = 1
    private val stores = listOf("aurix_settings", "aurix_library", "aura_playback_state")

    fun write(context: Context, uri: Uri) {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
        val storeObject = JSONObject()

        stores.forEach { storeName ->
            val entries = JSONObject()
            context.getSharedPreferences(storeName, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                val entry = JSONObject()
                when (value) {
                    is Boolean -> entry.put("type", "boolean").put("value", value)
                    is Int -> entry.put("type", "int").put("value", value)
                    is Long -> entry.put("type", "long").put("value", value)
                    is Float -> entry.put("type", "float").put("value", value.toDouble())
                    is String -> entry.put("type", "string").put("value", value)
                    is Set<*> -> {
                        val array = JSONArray()
                        value.filterIsInstance<String>().forEach { array.put(it) }
                        entry.put("type", "string_set").put("value", array)
                    }
                    else -> return@forEach
                }
                entries.put(key, entry)
            }
            storeObject.put(storeName, entries)
        }
        root.put("stores", storeObject)
        val output = context.contentResolver.openOutputStream(uri)
            ?: throw java.io.IOException("A kiválasztott fájl nem írható.")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(root.toString(2)) }
    }

    fun read(context: Context, uri: Uri): JSONObject {
        val source = context.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("A fájl nem olvasható.")
        val root = source.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        require(root.optString("format") == FORMAT) { "Ez nem AURA biztonsági mentés." }
        require(root.optInt("version", 0) in 1..VERSION) { "Nem támogatott mentésverzió." }
        val savedStores = root.optJSONObject("stores")
            ?: throw IllegalArgumentException("Hiányzó mentési adatok.")
        require(stores.any { savedStores.has(it) }) { "Nem található visszaállítható AURA-adat." }
        return savedStores
    }

    fun restore(context: Context, savedStores: JSONObject) {
        stores.forEach { storeName ->
            val entries = savedStores.optJSONObject(storeName) ?: return@forEach
            val editor = context.getSharedPreferences(storeName, Context.MODE_PRIVATE).edit().clear()
            val keys = entries.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entry = entries.optJSONObject(key) ?: continue
                when (entry.optString("type")) {
                    "boolean" -> editor.putBoolean(key, entry.optBoolean("value"))
                    "int" -> editor.putInt(key, entry.optInt("value"))
                    "long" -> editor.putLong(key, entry.optLong("value"))
                    "float" -> editor.putFloat(key, entry.optDouble("value").toFloat())
                    "string" -> entry.opt("value")?.let { if (it is String) editor.putString(key, it) }
                    "string_set" -> {
                        val array = entry.optJSONArray("value") ?: JSONArray()
                        val values = linkedSetOf<String>()
                        for (index in 0 until array.length()) {
                            array.optString(index).takeIf { it.isNotBlank() }?.let { values.add(it) }
                        }
                        editor.putStringSet(key, values)
                    }
                }
            }
            check(editor.commit()) { "Nem sikerült menteni a(z) $storeName beállításait." }
        }
    }
}

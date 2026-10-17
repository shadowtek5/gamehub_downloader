package com.gamehub.downloader.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class DownloadRecord(
    val connectionId: String,
    val romId: Long,
    val systemKey: String,
    val fileName: String,
    val folderLabel: String,
    val completedAt: Long,
)

private val Context.historyStore by preferencesDataStore(name = "history")

/** Remembers which games were downloaded so the library can badge them. */
class DownloadHistory(private val context: Context) {
    private val key = stringPreferencesKey("records")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(DownloadRecord.serializer())

    val records: Flow<List<DownloadRecord>> = context.historyStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()
    }

    suspend fun add(record: DownloadRecord) {
        context.historyStore.edit { prefs ->
            val list = prefs[key]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()
            val rest = list.filterNot { it.connectionId == record.connectionId && it.romId == record.romId }
            prefs[key] = json.encodeToString(serializer, rest + record)
        }
    }

    suspend fun remove(connectionId: String, romId: Long) {
        context.historyStore.edit { prefs ->
            val list = prefs[key]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()
            prefs[key] = json.encodeToString(serializer, list.filterNot { it.connectionId == connectionId && it.romId == romId })
        }
    }

    suspend fun clear(connectionId: String? = null) {
        context.historyStore.edit { prefs ->
            if (connectionId == null) prefs.remove(key)
            else {
                val list = prefs[key]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()
                prefs[key] = json.encodeToString(serializer, list.filterNot { it.connectionId == connectionId })
            }
        }
    }
}

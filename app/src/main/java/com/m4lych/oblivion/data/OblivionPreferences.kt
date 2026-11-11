package com.m4lych.oblivion.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.m4lych.oblivion.domain.ServerAddressValidator
import java.nio.charset.StandardCharsets
import java.util.Base64

private val Context.oblivionDataStore by preferencesDataStore(name = "oblivion_preferences")

data class SavedServer(val id: String, val name: String, val address: String)
data class SkinPreviewSelection(val fileName: String = "", val tintArgb: Int = DEFAULT_SKIN_TINT) {
    companion object { val DEFAULT_SKIN_TINT: Int = 0xFF30233D.toInt() }
}

object SavedServerCodec {
    private const val MAX_SERVERS = 100
    private val idPattern = Regex("^[A-Za-z0-9-]{1,64}$")
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(servers: List<SavedServer>): String = servers.takeLast(MAX_SERVERS)
        .filter(::isValid)
        .joinToString("\n") { server ->
            "${server.id}|${encodeField(server.name.trim())}|${encodeField(server.address.trim())}"
        }

    fun decode(value: String?): List<SavedServer> {
        if (value.isNullOrBlank()) return emptyList()
        val result = LinkedHashMap<String, SavedServer>()
        value.lineSequence().take(MAX_SERVERS).forEach { line ->
            val fields = line.split('|', limit = 3)
            if (fields.size != 3 || !idPattern.matches(fields[0])) return@forEach
            val server = runCatching {
                SavedServer(fields[0], decodeField(fields[1]), decodeField(fields[2]))
            }.getOrNull() ?: return@forEach
            if (isValid(server)) result[server.id] = server
        }
        return result.values.toList()
    }

    private fun isValid(server: SavedServer): Boolean =
        idPattern.matches(server.id) && server.name.trim().length in 1..40 &&
            server.address.length <= 255 && ServerAddressValidator.isValid(server.address)

    private fun encodeField(value: String): String =
        encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeField(value: String): String =
        String(decoder.decode(value), StandardCharsets.UTF_8)
}

class OblivionPreferences(private val context: Context) {
    private val displayNameKey = stringPreferencesKey("display_name")
    private val telemetryKey = booleanPreferencesKey("optional_analytics")
    private val lowMotionKey = booleanPreferencesKey("reduced_motion")
    private val serversKey = stringPreferencesKey("saved_servers_v1")
    private val skinPreviewKey = stringPreferencesKey("skin_preview_file")
    private val skinTintKey = intPreferencesKey("skin_preview_tint")
    val displayName: Flow<String> = context.oblivionDataStore.data.map { it[displayNameKey] ?: "Wanderer" }
    val optionalAnalytics: Flow<Boolean> = context.oblivionDataStore.data.map { it[telemetryKey] ?: false }
    val reducedMotion: Flow<Boolean> = context.oblivionDataStore.data.map { it[lowMotionKey] ?: false }
    val servers: Flow<List<SavedServer>> = context.oblivionDataStore.data.map { SavedServerCodec.decode(it[serversKey]) }
    val skinPreview: Flow<SkinPreviewSelection> = context.oblivionDataStore.data.map {
        SkinPreviewSelection(it[skinPreviewKey].orEmpty(), it[skinTintKey] ?: SkinPreviewSelection.DEFAULT_SKIN_TINT)
    }

    suspend fun saveDisplayName(value: String) { context.oblivionDataStore.edit { it[displayNameKey] = value } }
    suspend fun setOptionalAnalytics(value: Boolean) { context.oblivionDataStore.edit { it[telemetryKey] = value } }
    suspend fun setReducedMotion(value: Boolean) { context.oblivionDataStore.edit { it[lowMotionKey] = value } }
    suspend fun saveSkinPreview(fileName: String, tintArgb: Int) {
        require(Regex("^skin_[A-Za-z0-9_-]{1,64}\\.png$").matches(fileName))
        context.oblivionDataStore.edit { preferences ->
            preferences[skinPreviewKey] = fileName
            preferences[skinTintKey] = tintArgb
        }
    }
    suspend fun saveServer(server: SavedServer) {
        require(server.name.trim().length in 1..40 && ServerAddressValidator.isValid(server.address))
        context.oblivionDataStore.edit { preferences ->
            val updated = SavedServerCodec.decode(preferences[serversKey]).filterNot { it.id == server.id } + server
            preferences[serversKey] = SavedServerCodec.encode(updated)
        }
    }
    suspend fun removeServer(id: String) {
        context.oblivionDataStore.edit { preferences ->
            preferences[serversKey] = SavedServerCodec.encode(SavedServerCodec.decode(preferences[serversKey]).filterNot { it.id == id })
        }
    }
}

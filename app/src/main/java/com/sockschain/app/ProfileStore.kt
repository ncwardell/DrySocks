package com.sockschain.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sockschain.core.Profile
import com.sockschain.core.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

private val Context.store: DataStore<Preferences> by preferencesDataStore("profiles")

/**
 * Profiles, as one JSON list, and which one is selected.
 *
 * Proxy passwords are stored in the clear in app-private storage, which is excluded from
 * backup. Moving them into the Keystore is on the list in docs/design.md.
 */
class ProfileStore(context: Context) {
    private val store = context.applicationContext.store

    val profiles: Flow<List<Profile>> = store.data.map { decode(it[PROFILES]) }
    val selectedId: Flow<String?> = store.data.map { it[SELECTED] }

    suspend fun selected(): Profile? {
        val prefs = store.data.first()
        val all = decode(prefs[PROFILES])
        return all.firstOrNull { it.id == prefs[SELECTED] } ?: all.firstOrNull()
    }

    /** Replaces every profile at once; for migrations. */
    suspend fun replaceAll(all: List<Profile>) = store.edit { it[PROFILES] = encode(all) }

    suspend fun all(): List<Profile> = decode(store.data.first()[PROFILES])

    suspend fun get(id: String): Profile? = decode(store.data.first()[PROFILES]).firstOrNull { it.id == id }

    suspend fun save(profile: Profile) = store.edit { prefs ->
        val all = decode(prefs[PROFILES])
        val i = all.indexOfFirst { it.id == profile.id }
        prefs[PROFILES] = encode(if (i < 0) all + profile else all.toMutableList().also { it[i] = profile })
        if (prefs[SELECTED] == null) prefs[SELECTED] = profile.id
    }

    suspend fun delete(id: String) = store.edit { prefs ->
        val rest = decode(prefs[PROFILES]).filter { it.id != id }
        prefs[PROFILES] = encode(rest)
        if (prefs[SELECTED] == id) {
            rest.firstOrNull()?.let { prefs[SELECTED] = it.id } ?: prefs.remove(SELECTED)
        }
    }

    suspend fun select(id: String) = store.edit { it[SELECTED] = id }

    private companion object {
        val PROFILES = stringPreferencesKey("profiles")
        val SELECTED = stringPreferencesKey("selected")
        val serializer = ListSerializer(Profile.serializer())

        fun decode(s: String?): List<Profile> =
            if (s == null) emptyList() else runCatching { json.decodeFromString(serializer, s) }.getOrDefault(emptyList())
                .map { if (it.dns == Profile.OLD_DEFAULT_DNS) it.copy(dns = Profile.DEFAULT_DNS) else it }

        fun encode(list: List<Profile>) = json.encodeToString(serializer, list)
    }
}

package com.sockschain.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sockschain.core.ScrapeSource
import com.sockschain.core.encodeSources
import com.sockschain.core.parseSources
import com.sockschain.engine.mobile.Mobile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sourceStore: DataStore<Preferences> by preferencesDataStore("sources")

/** The scraper's list sources. Until edited, the engine's built-in defaults. */
class SourceStore(context: Context) {
    private val store = context.applicationContext.sourceStore

    val sources: Flow<List<ScrapeSource>> = store.data.map { prefs ->
        prefs[KEY]?.let { runCatching { parseSources(it) }.getOrNull() } ?: defaults()
    }

    suspend fun save(list: List<ScrapeSource>) = store.edit { it[KEY] = encodeSources(list) }

    private companion object {
        val KEY = stringPreferencesKey("sources")
        fun defaults() = parseSources(Mobile.defaultSources())
    }
}

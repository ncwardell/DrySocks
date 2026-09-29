package io.github.ncwardell.drysocks

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.ncwardell.drysocks.core.BlockRules
import io.github.ncwardell.drysocks.engine.mobile.Mobile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.blockStore: DataStore<Preferences> by preferencesDataStore("blocklist")

/**
 * Blocked domains, addresses and ranges. Kept by the app; the engine holds a copy,
 * pushed on every change and before every start, so a VPN started at boot by
 * always-on blocks the same things.
 */
class BlockStore(context: Context) {
    private val store = context.applicationContext.blockStore

    val rules: Flow<List<String>> = store.data.map { p ->
        p[KEY]?.let { runCatching { BlockRules.decode(it) }.getOrNull() } ?: emptyList()
    }

    /** Adds [raw] if it is a valid rule; returns the normal form, or null. */
    suspend fun add(raw: String): String? {
        val rule = BlockRules.normalize(raw) ?: return null
        edit { if (rule in it) it else it + rule }
        return rule
    }

    suspend fun remove(rule: String) = edit { it - rule }

    /** Hands the current rules to the engine. */
    suspend fun push() = Mobile.setBlocklist(BlockRules.encode(rules.first()))

    private suspend fun edit(change: (List<String>) -> List<String>) {
        store.edit { p ->
            val now = p[KEY]?.let { runCatching { BlockRules.decode(it) }.getOrNull() } ?: emptyList()
            p[KEY] = BlockRules.encode(change(now))
        }
        push()
    }

    private companion object {
        val KEY = stringPreferencesKey("rules")
    }
}

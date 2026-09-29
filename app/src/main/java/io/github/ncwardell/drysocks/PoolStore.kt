package io.github.ncwardell.drysocks

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.ncwardell.drysocks.core.ProxyPool
import io.github.ncwardell.drysocks.core.migrateRotatingHops
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

private val Context.poolStore: DataStore<Preferences> by preferencesDataStore("pools")

/** Saved proxy pools. Rotating hops refer to them by id; see [PoolUpdater] for how they fill. */
class PoolStore(context: Context) {
    private val app = context.applicationContext
    private val store = app.poolStore

    val pools: Flow<List<ProxyPool>> = store.data.map { decode(it[KEY]) }

    suspend fun all(): List<ProxyPool> = decode(store.data.first()[KEY])

    suspend fun get(id: String): ProxyPool? = all().firstOrNull { it.id == id }

    suspend fun create(name: String): ProxyPool {
        val p = ProxyPool(id = "pool-" + UUID.randomUUID().toString().take(8), name = name)
        save(p)
        return p
    }

    suspend fun save(pool: ProxyPool) = modify { all ->
        val i = all.indexOfFirst { it.id == pool.id }
        if (i < 0) all + pool else all.toMutableList().also { it[i] = pool }
    }

    /** Changes one pool atomically against concurrent updates. */
    suspend fun update(id: String, change: (ProxyPool) -> ProxyPool) = modify { all ->
        all.map { if (it.id == id) change(it) else it }
    }

    suspend fun delete(id: String) = modify { all -> all.filter { it.id != id } }

    private suspend fun modify(change: (List<ProxyPool>) -> List<ProxyPool>) {
        store.edit { it[KEY] = ProxyPool.encodeList(change(decode(it[KEY]))) }
        BackgroundUpdates.schedule(app, all())
    }

    companion object {
        private val KEY = stringPreferencesKey("pools")
        private val migrating = Mutex()

        private fun decode(s: String?): List<ProxyPool> =
            s?.let { runCatching { ProxyPool.parseList(it) }.getOrNull() } ?: emptyList()

        /** Turns rotating hops that still carry their own list into pools. Safe to repeat. */
        suspend fun migrate(context: Context) = migrating.withLock {
            val profiles = ProfileStore(context)
            val pools = PoolStore(context)
            val all = profiles.all()
            if (all.none { p -> p.rotatingHops.any { it.poolId.isEmpty() } }) return@withLock
            val (newProfiles, newPools) = migrateRotatingHops(all, pools.all(), System.currentTimeMillis())
            pools.modify { newPools }
            profiles.replaceAll(newProfiles)
        }
    }
}

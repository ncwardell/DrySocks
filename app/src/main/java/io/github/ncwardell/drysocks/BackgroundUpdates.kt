package io.github.ncwardell.drysocks

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.ncwardell.drysocks.core.ProxyPool
import io.github.ncwardell.drysocks.core.UpdateMode
import java.util.concurrent.TimeUnit

/**
 * Updates pools that asked for it while the VPN is off. Android runs it about every
 * 15 minutes, when there is a network; each pool still keeps its own interval, via
 * [ProxyPool.due]. Scheduled only while some pool wants it.
 */
object BackgroundUpdates {
    private const val WORK = "pool-updates"

    fun schedule(context: Context, pools: List<ProxyPool>) {
        val wm = WorkManager.getInstance(context)
        if (pools.none { it.update.mode == UpdateMode.AUTO && it.update.background }) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<PoolWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class PoolWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Connected, the VPN service updates every automatic pool itself. Connecting or
        // failing closed, this app's own traffic is outside the VPN and would go straight
        // out, which is exactly what the user asked not to happen.
        val s = ChainVpnService.status.value
        if (s !is VpnStatus.Stopped && s !is VpnStatus.Failed) return Result.success()
        PoolStore.migrate(applicationContext)
        PoolUpdater.runDue(applicationContext, backgroundOnly = true)
        return Result.success()
    }
}

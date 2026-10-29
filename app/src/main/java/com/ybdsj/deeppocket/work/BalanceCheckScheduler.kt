package com.ybdsj.deeppocket.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 后台巡检的排程开关。
 *
 * Android 对周期性后台任务的硬性限制是「最小 15 分钟」，所以设置页里
 * 秒级的刷新间隔只对前台生效——这不是偷懒，是系统规则。
 */
object BalanceCheckScheduler {

    const val UNIQUE_WORK_NAME = "balance_check_periodic"

    /** 系统允许的最小周期，写成常量便于在 UI 上一并说明。 */
    const val INTERVAL_MINUTES = 15L

    fun sync(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<BalanceCheckWorker>(
            INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build(),
        ).build()

        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}

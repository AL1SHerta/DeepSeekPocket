package com.ybdsj.deeppocket.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ybdsj.deeppocket.DeepPocketApp
import com.ybdsj.deeppocket.core.error.ApiError
import com.ybdsj.deeppocket.core.result.ApiResult
import com.ybdsj.deeppocket.data.settings.AppSettings
import com.ybdsj.deeppocket.data.settings.SettingsRepository
import com.ybdsj.deeppocket.domain.billing.AlertEvaluator
import com.ybdsj.deeppocket.domain.model.Currency
import com.ybdsj.deeppocket.notification.BalanceAlertNotifier
import com.ybdsj.deeppocket.notification.StatusNoticePresenter
import com.ybdsj.deeppocket.widget.WidgetBridge
import java.math.BigDecimal
import kotlinx.coroutines.flow.first

/**
 * 后台余额巡检。
 *
 * 由 WorkManager 每 15 分钟（系统允许的最小周期）唤醒一次，做两件事：
 * 1. 记录一条余额采样，让「今日用量」在不用打开 App 的情况下也能连续；
 * 2. 检查是否跌破用户设置的预警阈值，跌破了就发本地通知。
 *
 * 注意：后台触发时间由系统调度，不保证精确到分钟；应用被用户强行停止后系统也不会再唤醒它。
 */
class BalanceCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? DeepPocketApp ?: return Result.success()
        val container = app.container

        // 用户关掉了后台巡检，或还没添加 Key，都直接结束，不让 Worker 反复重试
        val settings = runCatching { container.settingsRepository.settings.first() }.getOrNull()
            ?: return Result.success()
        if (!settings.backgroundCheckEnabled) return Result.success()

        val keyId = runCatching { container.apiKeyRepository.activeKey()?.id }.getOrNull()
            ?: return Result.success()
        val plainKey = runCatching { container.apiKeyRepository.resolvePlainKey(keyId) }.getOrNull()
            ?: return Result.success()

        return when (val result = container.balanceRepository.fetchBalance(plainKey)) {
            is ApiResult.Success -> {
                runCatching { container.balanceSampleRepository.record(result.data, keyId) }
                runCatching { container.apiKeyRepository.markUsed(keyId) }
                AlertThrottle.applyIfNeeded(
                    settingsRepository = container.settingsRepository,
                    settings = settings,
                    notifier = container.alertNotifier,
                    total = result.data.primary?.total,
                    symbol = result.data.primary?.currency?.symbol ?: "¥",
                )
                // 后台巡检也刷新常驻通知，否则关掉 App 后通知会一直停在旧数据上
                runCatching {
                    StatusNoticePresenter.sync(
                        settingsRepository = container.settingsRepository,
                        notifier = container.statusNotifier,
                        sampleRepository = container.balanceSampleRepository,
                        holidayCalendar = container.holidayCalendar,
                        balance = result.data,
                    )
                }
                // 桌面小组件同理：不打开 App 也要能看到最新余额
                runCatching {
                    val usage = container.balanceSampleRepository
                        .todayUsage(result.data.primary?.currency ?: Currency.CNY)
                    WidgetBridge.publish(applicationContext, result.data, usage)
                }
                Result.success()
            }

            is ApiResult.Failure -> {
                // 网络类失败重试；Key 无效这类错误重试也不会变好
                if (result.error.isTransient()) Result.retry() else Result.success()
            }
        }
    }
}

/** 只重试「下次可能成功」的错误。 */
internal fun ApiError.isTransient(): Boolean = when (this) {
    is ApiError.Network, is ApiError.Timeout, is ApiError.RateLimited, is ApiError.ServerError -> true
    else -> false
}

/**
 * 前台刷新与后台巡检共用的预警触发逻辑。
 *
 * 两边都要走这里，否则会出现「开着 App 不提醒、只有后台才提醒」的怪现象。
 */
internal object AlertThrottle {

    suspend fun applyIfNeeded(
        settingsRepository: SettingsRepository,
        settings: AppSettings,
        notifier: BalanceAlertNotifier,
        total: BigDecimal?,
        symbol: String,
    ) {
        val threshold = settings.alertThreshold
        val decision = AlertEvaluator.evaluate(
            total = total,
            threshold = threshold,
            alreadySuppressed = settings.alertSuppressed,
        )

        if (decision.shouldNotify && total != null && threshold != null) {
            notifier.notifyLowBalance(total, threshold, symbol)
        }
        if (decision.nextSuppressed != settings.alertSuppressed) {
            // 设置写入失败不应影响余额展示
            runCatching { settingsRepository.setAlertSuppressed(decision.nextSuppressed) }
        }
    }
}

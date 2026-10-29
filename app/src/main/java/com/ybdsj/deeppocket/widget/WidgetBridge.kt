package com.ybdsj.deeppocket.widget

import android.content.Context
import com.ybdsj.deeppocket.domain.model.Balance
import com.ybdsj.deeppocket.domain.model.DailyUsage
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 把最新的余额/用量写给桌面小组件。
 *
 * 小组件本身不联网也不查数据库（RemoteViews 里跑不了这些），所以刷新成功后必须由
 * 前台（[com.ybdsj.deeppocket.ui.home.HomeViewModel]）和后台巡检
 * （[com.ybdsj.deeppocket.work.BalanceCheckWorker]）共同把快照推过去，
 * 否则桌面会一直停在「打开 App 查询余额」。
 */
object WidgetBridge {

    fun publish(context: Context, balance: Balance?, todayUsage: DailyUsage?) {
        runCatching {
            val entry = balance?.primary
            val balanceText = entry?.let { it.currency.symbol + formatAmount(it.total) }
            // 采样不足两次时用量没有意义，宁可留空让小组件显示占位符
            val usageText = todayUsage
                ?.takeIf { it.hasEnoughSamples }
                ?.let { it.currency.symbol + formatAmount(it.estimatedSpend) }

            WidgetStateStore(context).save(
                WidgetSnapshot(
                    balanceText = balanceText,
                    usageText = usageText,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            WidgetUpdater.refreshAll(context)
        }
    }

    private fun formatAmount(value: BigDecimal): String =
        value.setScale(2, RoundingMode.HALF_UP).toPlainString()
}

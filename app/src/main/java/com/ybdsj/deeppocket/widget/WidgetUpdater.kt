package com.ybdsj.deeppocket.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.ybdsj.deeppocket.MainActivity
import com.ybdsj.deeppocket.R
import com.ybdsj.deeppocket.domain.billing.HolidayCalendar
import com.ybdsj.deeppocket.domain.billing.HolidayCalendarLoader
import com.ybdsj.deeppocket.domain.billing.PeakBadge
import com.ybdsj.deeppocket.domain.billing.PeakHours
import java.time.Instant

/**
 * 小组件渲染与刷新（只有一种尺寸：4×1 宽条）。
 *
 * 每次刷新做三件事：读快照 → 现算当前计费时段 → 推一份 RemoteViews。
 * 之所以每次都重算时段而不是信任存起来的 peakLabel：时段会到点自动翻转，
 * 而 updatePeriodMillis 只保证 30 分钟一次，中间用户手动加桌面/点开 App 都可能需要最新值。
 */
object WidgetUpdater {

    /** 没有数据时金额位上显示的占位符。 */
    private const val PLACEHOLDER_VALUE = "—"

    private const val HOLIDAY_ASSET = "cn_holidays.json"

    /** 打开 App 的 PendingIntent 请求码，固定值保证多次刷新复用同一个 PendingIntent。 */
    private const val REQUEST_CODE_LAUNCH = 100

    /** 节假日表解析结果缓存：JSON 不大，但每 30 分钟重解析一次纯属浪费。 */
    @Volatile
    private var holidayCalendarCache: HolidayCalendar? = null

    /** 刷新所有实例的小组件。由 AppWidgetProvider 与 App 内刷新流程共同调用。 */
    fun refreshAll(context: Context) {
        val appContext = context.applicationContext
        val manager: AppWidgetManager = AppWidgetManager.getInstance(appContext)
        val store = WidgetStateStore(appContext)

        val isPeak = evaluateIsPeak(appContext)
        val badgeLabel = if (isPeak) PeakBadge.PEAK else PeakBadge.OFF_PEAK

        val stored = store.read()
        val snapshot = stored.copy(peakLabel = badgeLabel, peakIsPeak = isPeak)
        // 时段变了也要落盘：其它入口（例如 App 打开时读快照）才能看到一致的值。
        if (snapshot != stored) {
            store.save(snapshot)
        }

        push(context = appContext, manager = manager, snapshot = snapshot)
    }

    /** 给小组件的所有实例推一份新布局。 */
    private fun push(
        context: Context,
        manager: AppWidgetManager,
        snapshot: WidgetSnapshot,
    ) {
        val ids: IntArray = manager.getAppWidgetIds(
            ComponentName(context, BalanceWidgetWideProvider::class.java),
        )
        if (ids.isEmpty()) return

        val views = render(context, snapshot)
        for (widgetId in ids) {
            manager.updateAppWidget(widgetId, views)
        }
    }

    /** 渲染 RemoteViews：上排应用名 + 时段徽标，下排「总余额 ¥xx │ 今日用量 ¥xx」。 */
    internal fun render(context: Context, snapshot: WidgetSnapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_wide)

        val balance = snapshot.balanceText?.takeIf { it.isNotBlank() } ?: PLACEHOLDER_VALUE
        val usage = snapshot.usageText?.takeIf { it.isNotBlank() } ?: PLACEHOLDER_VALUE

        views.setTextViewText(R.id.widget_value_balance, balance)
        views.setTextViewText(R.id.widget_value_usage, usage)
        views.setTextViewText(R.id.widget_badge_peak, snapshot.peakLabel)

        // 徽标底色与文字颜色都跟着时段走：高峰偏红、错峰偏绿
        views.setInt(
            R.id.widget_badge_peak,
            "setBackgroundResource",
            if (snapshot.peakIsPeak) {
                R.drawable.widget_badge_peak
            } else {
                R.drawable.widget_badge_offpeak
            },
        )
        views.setTextColor(
            R.id.widget_badge_peak,
            context.getColor(
                if (snapshot.peakIsPeak) {
                    R.color.widget_badge_peak_text
                } else {
                    R.color.widget_badge_offpeak_text
                },
            ),
        )

        // 点哪都能打开 App。MIUI / HyperOS 这类桌面只把点击转发给带文本的子 View，
        // 所以根容器之外，几个文本节点也绑上同一个 PendingIntent。
        val launch = launchPendingIntent(context)
        views.setOnClickPendingIntent(R.id.widget_root, launch)
        views.setOnClickPendingIntent(R.id.widget_app_name, launch)
        views.setOnClickPendingIntent(R.id.widget_value_balance, launch)
        views.setOnClickPendingIntent(R.id.widget_value_usage, launch)
        views.setOnClickPendingIntent(R.id.widget_badge_peak, launch)
        return views
    }

    private fun launchPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_LAUNCH,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 当前是否高峰。判定失败时退化为 false（按错峰展示），绝不因为时段判断挂掉整个小组件。 */
    private fun evaluateIsPeak(context: Context): Boolean = try {
        PeakHours.evaluate(Instant.now(), loadHolidayCalendar(context)).isPeak
    } catch (e: Exception) {
        false
    }

    /** 读 assets/cn_holidays.json；读取或解析失败一律降级为空表（只看星期几），不抛给调用方。 */
    private fun loadHolidayCalendar(context: Context): HolidayCalendar {
        holidayCalendarCache?.let { return it }
        val calendar: HolidayCalendar = try {
            val raw = context.applicationContext.assets.open(HOLIDAY_ASSET).use { stream ->
                stream.bufferedReader().use { reader -> reader.readText() }
            }
            HolidayCalendarLoader.parse(raw)
        } catch (e: Exception) {
            HolidayCalendar.EMPTY
        }
        holidayCalendarCache = calendar
        return calendar
    }
}

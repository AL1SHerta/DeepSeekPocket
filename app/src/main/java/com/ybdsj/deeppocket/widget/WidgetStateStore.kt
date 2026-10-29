package com.ybdsj.deeppocket.widget

import android.content.Context
import android.content.SharedPreferences

/**
 * 桌面小组件要展示的一份数据快照。
 *
 * [android.appwidget.AppWidgetProvider] 的回调是广播，跑在主线程上，既不能等网络也不能
 * 跑协程查数据库。所以由 App 在余额刷新成功后把「要显示的文本」写进这里，
 * 小组件只负责读出来排版。
 */
data class WidgetSnapshot(
    /** 总余额文本，由 App 传入且已带货币符号（例如 "¥ 12.34"）；null 表示还没有数据。 */
    val balanceText: String?,
    /** 今日用量文本，由 App 传入且已带货币符号；null 表示还没有数据。 */
    val usageText: String?,
    /** 当前计费时段文案：「高峰」或「错峰」。时段随时间变化，刷新时会重算。 */
    val peakLabel: String = "—",
    /** 当前是否处于高峰时段，用于选择徽标配色。 */
    val peakIsPeak: Boolean = false,
    /** 快照写入时间（毫秒时间戳），0L 表示从未写入。 */
    val updatedAt: Long = 0L,
)

/**
 * 小组件状态存储。
 *
 * 用 SharedPreferences 而不是数据库：AppWidgetProvider.onUpdate 在主线程被调用，
 * 必须是同步可读的小数据；SharedPreferences 首次读会整文件载入内存，之后全走内存，代价可忽略。
 * 写入用 apply()：内存中的值同步更新，同一进程里立刻读到新值，磁盘落盘异步。
 */
class WidgetStateStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 覆盖写入完整快照。 */
    fun save(snapshot: WidgetSnapshot) {
        prefs.edit()
            .putString(KEY_BALANCE, snapshot.balanceText)
            .putString(KEY_USAGE, snapshot.usageText)
            .putString(KEY_PEAK_LABEL, snapshot.peakLabel)
            .putBoolean(KEY_PEAK_IS_PEAK, snapshot.peakIsPeak)
            .putLong(KEY_UPDATED_AT, snapshot.updatedAt)
            .apply()
    }

    /** 同步读取快照；没有写过时返回默认值。 */
    fun read(): WidgetSnapshot = WidgetSnapshot(
        balanceText = prefs.getString(KEY_BALANCE, null),
        usageText = prefs.getString(KEY_USAGE, null),
        peakLabel = prefs.getString(KEY_PEAK_LABEL, null) ?: DEFAULT_PEAK_LABEL,
        peakIsPeak = prefs.getBoolean(KEY_PEAK_IS_PEAK, false),
        updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L),
    )

    companion object {
        /** 与 App 内其它偏好分开存，避免互相覆盖，也避免被清理逻辑误删。 */
        const val PREFS_NAME = "deep_pocket_widget_state"

        /** 没有数据时的时段占位文案，与布局里的默认值保持一致。 */
        const val DEFAULT_PEAK_LABEL = "—"

        private const val KEY_BALANCE = "balance_text"
        private const val KEY_USAGE = "usage_text"
        private const val KEY_PEAK_LABEL = "peak_label"
        private const val KEY_PEAK_IS_PEAK = "peak_is_peak"
        private const val KEY_UPDATED_AT = "updated_at"
    }
}

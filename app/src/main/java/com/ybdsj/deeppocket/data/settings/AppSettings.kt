package com.ybdsj.deeppocket.data.settings

import java.math.BigDecimal

/**
 * 自动刷新间隔。
 *
 * 只在应用处于前台可见状态时生效——后台无法按秒级定时轮询（Android 会直接限制），
 * 后台的定期检查由 WorkManager 负责，最小周期 15 分钟，见 [AppSettings.backgroundCheckEnabled]。
 */
enum class RefreshInterval(val seconds: Long, val label: String) {
    OFF(0, "关闭"),

    SEC_15(15, "15 秒"),
    SEC_30(30, "30 秒"),
    MIN_1(60, "1 分钟"),
    MIN_5(300, "5 分钟"),
    MIN_15(900, "15 分钟"),
    ;

    val isEnabled: Boolean get() = seconds > 0

    companion object {
        val DEFAULT: RefreshInterval = OFF

        fun fromSeconds(seconds: Long): RefreshInterval =
            entries.firstOrNull { it.seconds == seconds } ?: DEFAULT

        /** 官方按账号限流，间隔过短容易撞 429，用于在设置页给出提醒。 */
        val aggressive: List<RefreshInterval> = listOf(SEC_15, SEC_30)
    }
}

/**
 * 用户在设置页保存的偏好。
 *
 * - [alertThreshold] 为 null 或非正数时视为"未启用余额预警"；
 * - [alertSuppressed] 用于去重：同一段"低于阈值"的余额只提醒一次，
 *   等余额回到阈值以上后自动复位，避免每次刷新都弹通知；
 * - [liquidGlassEnabled] 控制卡片与底部导航的液态玻璃效果，关掉后一律用不透明配色；
 * - [disclaimerAccepted] 记录用户是否已过首次启动的免责声明，只有点了「同意并继续」才会变 true；
 * - [statusNotificationEnabled] 常驻通知开关：只在下拉通知栏时可见的余额/用量/时段摘要；
 * - [ignoredVersionCode] 用户点过「忽略此版本」的 versionCode，只有更高的版本才会再次提醒；
 * - [apiKeyRestartHintEnabled] 添加 API Key 后是否还要弹「重启应用生效」提示。
 */
data class AppSettings(
    val refreshInterval: RefreshInterval = RefreshInterval.DEFAULT,
    val backgroundCheckEnabled: Boolean = false,
    val alertThreshold: BigDecimal? = null,
    val alertSuppressed: Boolean = false,
    val liquidGlassEnabled: Boolean = true,
    val disclaimerAccepted: Boolean = false,
    val statusNotificationEnabled: Boolean = false,
    val ignoredVersionCode: Int = 0,
    val apiKeyRestartHintEnabled: Boolean = true,
) {
    val alertEnabled: Boolean
        get() = alertThreshold != null && alertThreshold > BigDecimal.ZERO
}

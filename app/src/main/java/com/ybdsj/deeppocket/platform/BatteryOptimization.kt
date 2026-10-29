package com.ybdsj.deeppocket.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 电池优化白名单的读取与跳转。
 *
 * 为什么不直接用 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 弹系统确认框：
 * 那个 action 要求 App 声明 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限，而 Google Play
 * 只允许闹钟、VoIP 等少数类别的应用使用它，普通应用声明后上架会被拒。所以这里只做两件事：
 * 读取当前白名单状态（无需权限），以及在未加入时打开系统的电池优化列表让用户自己加。
 *
 * 结论：整个过程不需要任何运行时权限，也没有上架政策风险。
 */
object BatteryOptimization {

    /**
     * 当前应用是否已豁免电池优化（即在系统白名单里）。
     *
     * 拿不到 PowerManager 时保守地返回 true，避免在异常设备上一直显示"未加入白名单"的提示。
     */
    fun isIgnoring(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 候选跳转目标，按优先级排列：系统「电池优化」列表 → 系统「应用详情」页。
     * 两者都是纯展示型 Intent，不声明任何权限。
     */
    fun settingsIntents(context: Context): List<Intent> {
        val packageUri = Uri.parse("package:${context.packageName}")
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                add(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            add(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** 依次尝试打开设置页，返回是否成功启动了任意一个。 */
    fun openSettings(context: Context): Boolean {
        for (intent in settingsIntents(context)) {
            if (intent.resolveActivity(context.packageManager) == null) continue
            val started = runCatching { context.startActivity(intent) }.isSuccess
            if (started) return true
        }
        return false
    }
}

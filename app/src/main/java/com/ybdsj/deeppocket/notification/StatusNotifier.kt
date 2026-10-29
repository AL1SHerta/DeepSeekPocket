package com.ybdsj.deeppocket.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ybdsj.deeppocket.MainActivity
import com.ybdsj.deeppocket.R

/**
 * 常驻通知：把「余额 / 今日用量 / 峰谷时段」三项摘要挂在通知栏里。
 *
 * 设计约束（都是用户明确要求的）：
 * - **不打扰**：渠道重要性用 [NotificationManager.IMPORTANCE_LOW]，无声音、无震动、
 *   不弹横幅、不出现在锁屏上；只有主动下拉通知栏才看得到正文。
 * - **常驻**：[androidx.core.app.NotificationCompat.Builder.setOngoing] 置位，
 *   不能左右滑动清除，只能回到设置里关开关。
 * - 只在用户开启开关时存在；关掉时立刻 [cancel]，不在系统里留空壳。
 *
 * 与 [BalanceAlertNotifier] 是两个独立渠道：预警是"跌破阈值"的一次性提醒（默认重要性），
 * 常驻摘要是"随时可看"的低优先级状态条，用户可以只开其中一个。
 */
class StatusNotifier(private val context: Context) {

    fun canPostNotifications(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /**
     * 渠道是否被用户在系统设置里关掉了。
     *
     * 用户可以在系统里把本渠道降级为「静默」甚至「关闭」，此时 notify() 不会报错，
     * 但通知根本不会出现——设置页需要如实反映这一点，所以单独查一次。
     */
    fun isChannelBlocked(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return false
        return channel.importance == NotificationManager.IMPORTANCE_NONE
    }

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.status_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.status_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 发布/更新常驻通知。同一 id 覆盖更新，不会在通知栏堆叠。
     *
     * @return true 表示通知真的发出去了；权限缺失或渠道被关闭时返回 false。
     */
    fun update(balanceText: String, usageText: String, peakText: String): Boolean {
        if (!canPostNotifications()) return false
        ensureChannel()

        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val summary = context.getString(R.string.status_text, usageText, peakText)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_balance)
            .setContentTitle(context.getString(R.string.status_title, balanceText))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // 常驻：不可左右滑除，只能由用户关掉开关
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(openApp)
            .build()

        return runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }

    fun cancel() {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    companion object {
        const val CHANNEL_ID = "balance_status"
        const val NOTIFICATION_ID = 1002
        private const val REQUEST_CODE = 10
    }
}

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
import java.math.BigDecimal

/**
 * 余额预警的本地通知。
 *
 * 全部在本机由 NotificationManager 发出，不经过任何服务器，也不含密钥信息——
 * 通知正文只有金额与阈值，点开回到首页。
 */
class BalanceAlertNotifier(private val context: Context) {

    fun canPostNotifications(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.alert_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.alert_channel_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    /** @return true 表示通知真的发出去了（权限缺失或被系统关闭时返回 false）。 */
    fun notifyLowBalance(
        total: BigDecimal,
        threshold: BigDecimal,
        currencySymbol: String,
    ): Boolean {
        if (!canPostNotifications()) return false
        ensureChannel()

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_balance)
            .setContentTitle(context.getString(R.string.alert_title))
            .setContentText(
                context.getString(
                    R.string.alert_text,
                    currencySymbol + total.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                    currencySymbol + threshold.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                ),
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(
                        R.string.alert_text_long,
                        currencySymbol + total.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                        currencySymbol + threshold.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                    ),
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()

        return runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }

    companion object {
        const val CHANNEL_ID = "balance_alert"
        const val NOTIFICATION_ID = 1001
    }
}

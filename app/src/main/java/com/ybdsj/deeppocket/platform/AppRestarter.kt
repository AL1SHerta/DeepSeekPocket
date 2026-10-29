package com.ybdsj.deeppocket.platform

import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ybdsj.deeppocket.MainActivity

/**
 * 一键重启应用。
 *
 * 为什么不用「startActivity + exitProcess」：那条路径会先把旧 Activity 拉起来、
 * 再杀进程，新实例往往还是复用旧进程里已经初始化过的单例，达不到「重启生效」的目的。
 *
 * 这里改成：把这个 PendingIntent 交给系统，用 [AlarmManager.RTC] 闹钟在 ~400ms 后
 * 拉起 MainActivity，再立刻 exit(0) 把本进程整个杀掉。等闹钟到点时进程已经彻底死了，
 * 新 Activity 一定跑在全新进程里——数据库、DataStore、Keystore 全部重新初始化。
 *
 * 坑点：Android 10 起有「后台启动 Activity」限制，Android 14 又收紧成必须由创建方显式授权。
 * 我们的进程此刻已经 NONEXISTENT，系统会直接判 BAL_BLOCK（实测 MIUI 上 app 直接回桌面）——
 * 所以要给 PendingIntent 带上 [ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED]，
 * 否则重启按钮只会「关掉应用」而不会自己回来。
 */
object AppRestarter {

    fun restart(context: Context) {
        val app = context.applicationContext
        val launch = Intent(app, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val options = buildBackgroundStartOptions()
        val pending = if (options != null) {
            PendingIntent.getActivity(
                app,
                REQUEST_CODE,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                options.toBundle(),
            )
        } else {
            PendingIntent.getActivity(
                app,
                REQUEST_CODE,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val scheduled = runCatching {
            val alarm = app.getSystemService(AlarmManager::class.java)
            alarm?.set(AlarmManager.RTC, System.currentTimeMillis() + DELAY_MS, pending)
        }.isSuccess

        // 闹钟被系统拒绝时，退化成「先拉起新实例再退出」，至少保证能回到应用
        if (!scheduled) {
            runCatching { app.startActivity(launch) }
        }

        Runtime.getRuntime().exit(0)
    }

    /** API 34 起才能声明「由本 PendingIntent 发起的后台 Activity 启动是允许的」。 */
    private fun buildBackgroundStartOptions(): ActivityOptions? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return runCatching {
            ActivityOptions.makeBasic().apply {
                setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                )
            }
        }.getOrNull()
    }

    private const val REQUEST_CODE = 20
    private const val DELAY_MS = 400L
}

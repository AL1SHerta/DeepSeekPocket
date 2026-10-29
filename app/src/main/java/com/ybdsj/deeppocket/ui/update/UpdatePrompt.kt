package com.ybdsj.deeppocket.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.ybdsj.deeppocket.BuildConfig
import com.ybdsj.deeppocket.DeepPocketApp
import com.ybdsj.deeppocket.data.settings.AppSettings
import com.ybdsj.deeppocket.update.UpdateCheckResult
import com.ybdsj.deeppocket.update.UpdateChecker
import com.ybdsj.deeppocket.update.UpdateConfig
import com.ybdsj.deeppocket.update.UpdateManifest
import kotlinx.coroutines.flow.first

/**
 * 版本检查的统一入口：启动自动检查与设置页手动检查都走这里。
 *
 * 自动检查带 24 小时节流（[UpdateConfig.CHECK_INTERVAL_MS]），避免每次冷启动都去
 * GitHub 拉一次清单；手动检查永远跳过节流，用户点了就得真去查。
 */
object UpdatePrompt {

    /** 进程内复用一个实例，OkHttpClient 的连接池与线程池才有意义。 */
    private val checker: UpdateChecker by lazy { UpdateChecker() }

    /**
     * @return 检查结果；被节流跳过时返回 null（调用方什么都不用做）。
     */
    suspend fun check(app: DeepPocketApp, force: Boolean): UpdateCheckResult? {
        val repository = app.container.settingsRepository

        if (!force) {
            val lastCheckedAt = runCatching { repository.lastUpdateCheckAt.first() }.getOrDefault(0L)
            val elapsed = System.currentTimeMillis() - lastCheckedAt
            if (lastCheckedAt > 0L && elapsed in 0 until UpdateConfig.CHECK_INTERVAL_MS) {
                return null
            }
        }

        val settings = runCatching { repository.settings.first() }.getOrDefault(AppSettings())
        val result = checker.check(
            currentVersionCode = BuildConfig.VERSION_CODE,
            ignoredVersionCode = settings.ignoredVersionCode,
        )
        // 失败也记时间：否则断网时每次启动都会重试一次，纯属浪费流量
        runCatching { repository.setLastUpdateCheckAt(System.currentTimeMillis()) }
        return result
    }

    /** 用浏览器打开下载页（百度网盘），没有浏览器时给一句可复制的提示。 */
    fun openDownload(context: Context, manifest: UpdateManifest) {
        val url = UpdateConfig.effectiveDownloadUrl(manifest.downloadUrl)
        // 清单没填 downloadUrl 时会回落到兜底地址；兜底还是占位的话别跳 404
        if (url == UpdateConfig.DOWNLOAD_FALLBACK_URL && !UpdateConfig.isFallbackConfigured) {
            openDownloadFallback(context)
            return
        }
        openUrl(context, url)
    }

    /**
     * 不依赖远端清单，直接打开兜底下载页。
     *
     * 给「检查更新失败」这条路径用：远端仓库还没建/被墙时，用户仍然能自己去网盘拿新版。
     */
    fun openDownloadFallback(context: Context) {
        if (!UpdateConfig.isFallbackConfigured) {
            Toast.makeText(
                context,
                "下载链接还没配置，先从项目 release 目录或电脑上的 APK 安装吧",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        openUrl(context, UpdateConfig.effectiveDownloadUrl(null))
    }

    private fun openUrl(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!runCatching { context.startActivity(intent) }.isSuccess) {
            Toast.makeText(context, "没有可用的浏览器，请手动访问：$url", Toast.LENGTH_LONG).show()
        }
    }
}

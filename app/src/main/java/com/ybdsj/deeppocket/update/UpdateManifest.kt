package com.ybdsj.deeppocket.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 远端更新清单（仓库里的 `docs/update.json`）的数据结构。
 *
 * 设计成"除三个必备字段外全部可选、缺失即用默认值"：
 * 这份清单是手写在 GitHub 仓库里的，多写错写一个字段不该让所有老版本 App 直接判失败。
 *
 * @property versionCode 远端最新版本的 versionCode，与 `app/build.gradle.kts` 里的同一个数字。
 * @property versionName 远端最新版本的 versionName，仅用于 UI 展示。
 * @property downloadUrl 下载地址（当前是百度网盘分享链接）。
 * @property notes 更新说明，一条一行；缺失时为空列表而不是抛异常。
 * @property publishedAt 发布日期字符串（如 `"2026-10-28"`），不做日期解析，只展示。
 * @property forceBelowVersionCode 低于该 versionCode 的旧版必须更新（强制更新阈值），null 表示不强制。
 */
@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val notes: List<String> = emptyList(),
    val publishedAt: String? = null,
    val forceBelowVersionCode: Int? = null,
) {
    companion object {

        /**
         * 解析更新清单专用的 Json 配置，刻意不复用 [com.ybdsj.deeppocket.data.remote.NetworkFactory.json]：
         * 那个实例是给后端 API 用的严格模式（不接受非标准 JSON），而手写清单需要宽松一些。
         *
         * - `ignoreUnknownKeys = true`：以后给清单加字段，旧版本 App 照常能读；
         * - `isLenient = true`：容忍手写时常见的宽松写法（单引号、不带引号的字符串等）；
         * - `explicitNulls = false`：`"publishedAt": null` 与不写这个字段等价，不会因空值炸掉。
         */
        val json: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

        /**
         * 把远端返回的原始字符串解析成清单。
         *
         * 解析失败会抛 [kotlinx.serialization.SerializationException]（属于 IllegalArgumentException），
         * 由 [UpdateChecker] 转成 [UpdateCheckResult.Failed]，绝不冒到 UI 层。
         */
        fun parse(raw: String): UpdateManifest =
            json.decodeFromString(UpdateManifest.serializer(), raw)
    }
}

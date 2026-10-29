package com.ybdsj.deeppocket.update

/**
 * 版本检测的全部常量都收在这里，方便设置页直接引用与替换。
 *
 * 这里只放"静态配置"，不放业务逻辑；检查流程在 [UpdateChecker]。
 *
 * 仓库：https://github.com/AL1SHerta/DeepSeek-Pocket
 *
 * 发新版本时**只需要改仓库根目录的 `update.json`**，不用重新编译旧版本：
 * 老版本的 App 会自己读到新的 versionCode 并弹提示。
 */
object UpdateConfig {

    /**
     * 更新清单的主地址：仓库根目录的 `update.json`（`main` 分支，raw 直读）。
     *
     * 注意路径要对上——清单必须放在**仓库根目录**，而不是 `docs/`。
     * 仓库改名或换 owner 时只改这一行，别在别处硬编码 URL。
     */
    const val CHECK_URL =
        "https://raw.githubusercontent.com/AL1SHerta/DeepSeek-Pocket/main/update.json"

    /**
     * 备用更新源。
     *
     * `raw.githubusercontent.com` 在国内经常连不上或被 DNS 污染，jsDelivr 是
     * GitHub 仓库的免费 CDN 镜像（内容自动同步，不需要额外上传），
     * 所以主地址失败时按顺序再试一遍这里。
     *
     * 两条 jsDelivr 域名互为备份：`cdn.jsdelivr.net` 在部分运营商被墙时
     * `gcore.jsdelivr.net` 通常还能用。
     */
    val CHECK_MIRRORS: List<String> = listOf(
        "https://cdn.jsdelivr.net/gh/AL1SHerta/DeepSeek-Pocket@main/update.json",
        "https://gcore.jsdelivr.net/gh/AL1SHerta/DeepSeek-Pocket@main/update.json",
    )

    /** 实际尝试顺序：主地址优先，然后依次回落镜像。 */
    val CHECK_URLS: List<String> = listOf(CHECK_URL) + CHECK_MIRRORS

    /**
     * 下载兜底地址：项目用的百度网盘分享文件夹。
     *
     * 只在远端清单的 [UpdateManifest.downloadUrl] 为空或不是合法 http(s) 地址时使用，
     * 判定逻辑见 [effectiveDownloadUrl]。
     */
    const val DOWNLOAD_FALLBACK_URL = "https://pan.baidu.com/s/5f31vyRh-ITtHRVpEdOciqw"

    /**
     * 兜底下载地址是否已经换成真实的网盘分享链接。
     *
     * 还是占位时，界面不要假装能下载——直接告诉用户去哪儿取 APK，比跳到 404 页面强。
     */
    val isFallbackConfigured: Boolean
        get() = DOWNLOAD_FALLBACK_URL.startsWith("https://pan.baidu.com/s/") &&
            !DOWNLOAD_FALLBACK_URL.contains("xxxxxxxx")

    /** 自动检查更新的最小间隔：一天一次（设置页的"上次检查时间"节流也用它）。 */
    const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    /**
     * 一轮检查的总时间预算。
     *
     * 有三个更新源，若每个都按单次 20 秒的超时去等，断网时最坏要干等一分钟——
     * 用户早就退出设置页了。所以整体封顶 25 秒，超了就把已尝试源里最靠前的那条失败原因报上去。
     */
    const val TOTAL_CHECK_BUDGET_MS = 25_000L

    /**
     * 挑出真正该打开的下载地址。
     *
     * 远端清单是手写的，可能漏填 `downloadUrl`、留空串、或填了个不是 URL 的东西；
     * 这些情况一律回落到 [DOWNLOAD_FALLBACK_URL]，保证"点了下载一定有个地址可去"，
     * 而不是把用户带到一片空白。
     */
    fun effectiveDownloadUrl(candidate: String?): String {
        val trimmed = candidate?.trim().orEmpty()
        return if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
            trimmed
        } else {
            DOWNLOAD_FALLBACK_URL
        }
    }
}

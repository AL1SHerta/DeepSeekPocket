package com.ybdsj.deeppocket.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新配置的自检。
 *
 * 这些断言看着琐碎，但每一条都对应一次真实的沉默故障：
 * owner 写错 → 永远 404（第一次发布时就是这样哑掉的）；
 * 兜底链接还是占位 → 用户点"去网盘下载"跳到空白页。
 */
class UpdateConfigTest {

    @Test
    fun `primary url points at the real repository root manifest`() {
        assertEquals(
            "https://raw.githubusercontent.com/AL1SHerta/DeepSeek-Pocket/main/update.json",
            UpdateConfig.CHECK_URL,
        )
        // 清单必须放在仓库根目录，路径里不能再有 docs/
        assertFalse(UpdateConfig.CHECK_URL.contains("/docs/"))
    }

    @Test
    fun `all sources are https json manifests and primary comes first`() {
        val sources = UpdateConfig.CHECK_URLS
        assertEquals("主地址必须排在最前，镜像只在失败后才试", UpdateConfig.CHECK_URL, sources.first())
        assertTrue("至少要有一个镜像源", sources.size >= 2)
        assertEquals("更新源不能重复", sources.size, sources.toSet().size)
        sources.forEach { url ->
            assertTrue("更新源必须是 https：$url", url.startsWith("https://"))
            assertTrue("更新源必须指向 update.json：$url", url.endsWith("update.json"))
        }
    }

    @Test
    fun `mirrors are jsdelivr cdn views of the same repository`() {
        assertTrue(UpdateConfig.CHECK_MIRRORS.isNotEmpty())
        UpdateConfig.CHECK_MIRRORS.forEach { url ->
            assertTrue("镜像是 jsDelivr 的 GitHub 视图：$url", url.contains("jsdelivr.net/gh/AL1SHerta/DeepSeek-Pocket@main"))
        }
    }

    @Test
    fun `fallback download link is configured`() {
        assertTrue("兜底下载链接还是占位，用户点下载会跳到空白页", UpdateConfig.isFallbackConfigured)
        assertEquals("https://pan.baidu.com/s/5f31vyRh-ITtHRVpEdOciqw", UpdateConfig.DOWNLOAD_FALLBACK_URL)
    }

    @Test
    fun `check budget is short enough for a settings page tap`() {
        // 设置页手动检查是用户主动点的，不能让人盯着转圈一分钟
        assertTrue(UpdateConfig.TOTAL_CHECK_BUDGET_MS in 10_000L..30_000L)
    }
}

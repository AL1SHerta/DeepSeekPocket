package com.ybdsj.deeppocket.update

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只测两件真正容易错的事：JSON 解析的宽松程度，以及版本判定（含"忽略此版本"语义）。
 *
 * 不引入 MockWebServer：网络层只是把 OkHttp 回调包成挂起函数，写了也是测 OkHttp 自己。
 */
class UpdateManifestTest {

    /** 造一份"远端清单"，只关心 versionCode 的测试用它省字。 */
    private fun remote(versionCode: Int): UpdateManifest = UpdateManifest(
        versionCode = versionCode,
        versionName = "0.2.0",
        downloadUrl = "https://pan.baidu.com/s/1xxxxxxxxxx",
    )

    // region 解析

    @Test
    fun `parses full manifest`() {
        val raw = """
            {
              "versionCode": 3,
              "versionName": "0.2.0",
              "downloadUrl": "https://pan.baidu.com/s/1xxxxxxxxxx",
              "notes": ["首个更新说明", "第二条"],
              "publishedAt": "2026-10-28",
              "forceBelowVersionCode": 2
            }
        """.trimIndent()

        val parsed = UpdateManifest.parse(raw)

        assertEquals(3, parsed.versionCode)
        assertEquals("0.2.0", parsed.versionName)
        assertEquals("https://pan.baidu.com/s/1xxxxxxxxxx", parsed.downloadUrl)
        assertEquals(listOf("首个更新说明", "第二条"), parsed.notes)
        assertEquals("2026-10-28", parsed.publishedAt)
        assertEquals(2, parsed.forceBelowVersionCode ?: -1)
    }

    @Test
    fun `missing notes becomes empty list instead of throwing`() {
        val raw = """
            {
              "versionCode": 3,
              "versionName": "0.2.0",
              "downloadUrl": "https://pan.baidu.com/s/1xxxxxxxxxx"
            }
        """.trimIndent()

        val parsed = UpdateManifest.parse(raw)

        assertTrue(parsed.notes.isEmpty())
        assertNull(parsed.publishedAt)
        assertNull(parsed.forceBelowVersionCode)
    }

    @Test
    fun `unknown fields are ignored`() {
        val raw = """
            {
              "versionCode": 3,
              "versionName": "0.2.0",
              "downloadUrl": "https://pan.baidu.com/s/1xxxxxxxxxx",
              "futureField": {"nested": [1, 2, 3]},
              "minSdk": 26
            }
        """.trimIndent()

        val parsed = UpdateManifest.parse(raw)

        assertEquals(3, parsed.versionCode)
        assertTrue(parsed.notes.isEmpty())
    }

    @Test
    fun `blank notes array stays empty`() {
        val raw = """
            {
              "versionCode": 3,
              "versionName": "0.2.0",
              "downloadUrl": "https://pan.baidu.com/s/1xxxxxxxxxx",
              "notes": []
            }
        """.trimIndent()

        assertTrue(UpdateManifest.parse(raw).notes.isEmpty())
    }

    @Test
    fun `invalid json throws`() {
        assertThrows(SerializationException::class.java) {
            UpdateManifest.parse("{ this is not json ")
        }
    }

    @Test
    fun `missing required field throws`() {
        // 少 downloadUrl：清单确实写错了，必须显式失败，不能静默当成"下载地址为空"
        assertThrows(SerializationException::class.java) {
            UpdateManifest.parse("""{"versionCode": 3, "versionName": "0.2.0"}""")
        }
    }

    // endregion

    // region 版本判定

    @Test
    fun `newer remote version is available and not ignored`() {
        val result = UpdateChecker.evaluate(
            manifest = remote(versionCode = 3),
            currentVersionCode = 2,
            ignoredVersionCode = 0,
        ) as UpdateCheckResult.Available

        assertEquals(3, result.manifest.versionCode)
        assertFalse(result.isIgnored)
    }

    @Test
    fun `same version is up to date`() {
        val result = UpdateChecker.evaluate(
            manifest = remote(versionCode = 3),
            currentVersionCode = 3,
            ignoredVersionCode = 0,
        )

        assertEquals(UpdateCheckResult.UpToDate, result)
    }

    @Test
    fun `older remote version is up to date`() {
        val result = UpdateChecker.evaluate(
            manifest = remote(versionCode = 2),
            currentVersionCode = 3,
            ignoredVersionCode = 0,
        )

        assertEquals(UpdateCheckResult.UpToDate, result)
    }

    @Test
    fun `ignored when ignoredVersionCode equals remote version`() {
        val result = UpdateChecker.evaluate(
            manifest = remote(versionCode = 3),
            currentVersionCode = 2,
            ignoredVersionCode = 3,
        ) as UpdateCheckResult.Available

        assertTrue(result.isIgnored)
    }

    @Test
    fun `ignored when ignoredVersionCode is higher than remote version`() {
        // 用户忽略过 5，远端还是 3：仍然不打扰
        val result = UpdateChecker.evaluate(
            manifest = remote(versionCode = 3),
            currentVersionCode = 2,
            ignoredVersionCode = 5,
        ) as UpdateCheckResult.Available

        assertTrue(result.isIgnored)
    }

    @Test
    fun `not ignored once remote version exceeds the ignored one`() {
        // 用户忽略过 3，远端升到 4：重新提醒
        val result = UpdateChecker.evaluate(
            manifest = remote(versionCode = 4),
            currentVersionCode = 3,
            ignoredVersionCode = 3,
        ) as UpdateCheckResult.Available

        assertFalse(result.isIgnored)
    }

    @Test
    fun `up to date ignores the ignored flag entirely`() {
        // 本机已是最新时，无论忽略记录是什么都是 UpToDate
        val result = UpdateChecker.evaluate(
            manifest = remote(versionCode = 3),
            currentVersionCode = 3,
            ignoredVersionCode = 3,
        )

        assertEquals(UpdateCheckResult.UpToDate, result)
    }

    // endregion

    // region 下载地址兜底

    @Test
    fun `effective download url falls back when blank or illegal`() {
        assertEquals(UpdateConfig.DOWNLOAD_FALLBACK_URL, UpdateConfig.effectiveDownloadUrl(null))
        assertEquals(UpdateConfig.DOWNLOAD_FALLBACK_URL, UpdateConfig.effectiveDownloadUrl("   "))
        assertEquals(UpdateConfig.DOWNLOAD_FALLBACK_URL, UpdateConfig.effectiveDownloadUrl("not a url"))
        assertEquals(
            "https://pan.baidu.com/s/1AbCdEf",
            UpdateConfig.effectiveDownloadUrl("  https://pan.baidu.com/s/1AbCdEf  "),
        )
    }

    // endregion
}

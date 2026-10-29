package com.ybdsj.deeppocket.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ybdsj.deeppocket.ui.glass.GlassPanel

/**
 * 开发者信息。改主页只需要动这一个常量。
 *
 * 形如 `https://space.bilibili.com/12345678`，也可以在末尾带参数（如 `?spm_id_from=..`）。
 * 留空时卡片仍然显示，点击只提示未配置——不写死一个会 404 的地址。
 */
object DeveloperInfo {
    const val NAME = "云边的三色堇"

    /** 用 UID 拼的标准主页地址；比 b23.tv 短链更稳，也不会带上分享会话参数。 */
    const val BILIBILI_URL = "https://space.bilibili.com/600176547"
}

/** 设置页最底部的开发者卡片，整块可点，点了跳 B 站主页。 */
@Composable
fun DeveloperCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    GlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                val url = DeveloperInfo.BILIBILI_URL
                if (url.isBlank()) {
                    Toast.makeText(context, "B 站主页地址还没填上", Toast.LENGTH_SHORT).show()
                    return@clickable
                }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                val opened = runCatching { context.startActivity(intent) }.isSuccess
                if (!opened) {
                    Toast.makeText(context, "没有找到能打开这个链接的应用", Toast.LENGTH_SHORT).show()
                }
            },
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "开发者 @${DeveloperInfo.NAME}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "点这里去我的 B 站主页看看",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

package com.ybdsj.deeppocket.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowCircleUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ybdsj.deeppocket.update.UpdateManifest

/**
 * 「发现新版本」弹窗。
 *
 * 两个明确的出口（忽略 / 前往升级）+ 一个「以后再说」：
 * 忽略会把该 versionCode 记进设置，直到远端版本号更高才再次提醒；
 * 直接关掉弹窗只是本次不看，下次启动还会提醒。
 */
@Composable
fun UpdateAvailableDialog(
    manifest: UpdateManifest,
    forced: Boolean,
    onIgnore: () -> Unit,
    onUpgrade: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = if (forced) {
        "需要更新到 ${manifest.versionName}"
    } else {
        "发现新版本 ${manifest.versionName}"
    }

    AlertDialog(
        onDismissRequest = { if (!forced) onDismiss() },
        icon = { Icon(Icons.Rounded.ArrowCircleUp, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                if (manifest.publishedAt != null) {
                    Text(
                        text = "发布于 ${manifest.publishedAt}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (manifest.notes.isEmpty()) {
                    Text("本次更新以稳定性改进为主。", style = MaterialTheme.typography.bodyMedium)
                } else {
                    manifest.notes.forEach { note ->
                        Text(
                            text = "· $note",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "安装包从百度网盘下载，装的时候系统会提示覆盖安装，原来的 API Key 和设置都会保留。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onUpgrade) { Text("前往升级") }
        },
        dismissButton = {
            if (forced) {
                TextButton(onClick = onDismiss) { Text("稍后") }
            } else {
                TextButton(onClick = onIgnore) { Text("忽略此版本") }
            }
        },
    )
}

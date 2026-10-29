package com.ybdsj.deeppocket.ui.disclaimer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay

/** 首次启动时必须停留的秒数，期间两个按钮都锁着，点返回键也关不掉。 */
const val DISCLAIMER_COUNTDOWN_SECONDS = 5

/**
 * 首次启动的免责声明。
 *
 * 刻意做成不可跳过：`dismissOnBackPress` / `dismissOnClickOutside` 全部关掉，
 * 连"不同意"也要等倒计时走完——否则 5 秒限制形同虚设。
 */
@Composable
fun DisclaimerDialog(
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    var secondsLeft by rememberSaveable { mutableIntStateOf(DISCLAIMER_COUNTDOWN_SECONDS) }

    // 用 secondsLeft 自身做 key，每秒递减一次；rememberSaveable 保证旋屏重建后不会白送时间
    LaunchedEffect(secondsLeft) {
        if (secondsLeft > 0) {
            delay(1_000)
            secondsLeft -= 1
        }
    }

    Dialog(
        onDismissRequest = { /* 故意留空：不允许点外部关闭 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        // 默认遮罩太重，会把背后的氛围渐变压成一团灰；调淡一点让背景还看得见
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0.18f)
            }
        }
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 16.dp,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .padding(horizontal = 24.dp, vertical = 22.dp),
            ) {
                Text(text = "免责声明", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "这是一个第三方小工具，不是 DeepSeek 官方应用。开用它之前，请先看完下面五条。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                Column(
                    modifier = Modifier
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Clause(
                        "与官方无关",
                        "本应用由个人开发，与深度求索（DeepSeek）没有任何隶属或合作关系，" +
                            "官方不会为它提供支持，也不会为它出现的问题负责。",
                    )
                    Clause(
                        "密钥只留在本机",
                        "你填入的 API Key 经 Android Keystore 加密后只存在本机数据库中，" +
                            "不会上传到任何第三方服务器，不会写进日志，也不会随备份外传。",
                    )
                    Clause(
                        "用量是估算值",
                        "官方没有开放用量与账单接口，「今日用量」是把本机相邻两次采样的余额差额" +
                            "累加推算出来的估算值。它只能帮你感受花得快不快，不能当作计费依据。",
                    )
                    Clause(
                        "峰谷判断以官方为准",
                        "峰谷时段依据 DeepSeek 官方公布的规则与国务院公布的节假日安排判断。" +
                            "官方临时调整或公布新一年安排时，以官方说法为准。",
                    )
                    Clause(
                        "风险自负",
                        "因使用本应用产生的接口费用、数据偏差或其他后果，由你自己承担。",
                    )
                }

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = onAccept,
                    enabled = secondsLeft == 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (secondsLeft > 0) "同意并继续（$secondsLeft）" else "同意并继续",
                    )
                }
                TextButton(
                    onClick = onReject,
                    enabled = secondsLeft == 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (secondsLeft > 0) {
                            "不同意，退出应用（$secondsLeft）"
                        } else {
                            "不同意，退出应用"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Clause(title: String, body: String) {
    Column {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(2.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

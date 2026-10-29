package com.ybdsj.deeppocket.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ybdsj.deeppocket.core.error.ApiError
import com.ybdsj.deeppocket.domain.billing.HolidayCalendar
import com.ybdsj.deeppocket.domain.billing.PeakBadge
import com.ybdsj.deeppocket.domain.billing.PeakStatus
import com.ybdsj.deeppocket.domain.model.Balance
import com.ybdsj.deeppocket.domain.model.DailyUsage
import com.ybdsj.deeppocket.ui.glass.GlassPanel
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// 品牌余额卡片固定使用蓝色渐变（深浅色模式一致），保证白字对比度稳定。
private val BalanceGradientStart = Color(0xFF2F6FED)
private val BalanceGradientEnd = Color(0xFF1B4FBF)
private val AvailableDot = Color(0xFF7BE3A8)
private val UnavailableDot = Color(0xFFFFC46B)

/**
 * DeepSeek 开放平台充值页。
 *
 * 用浏览器落地页而不是内置 WebView：充值必然要登录账号、走支付，内置 WebView
 * 拿不到系统浏览器的密码/支付能力，反而更容易出错。
 */
private const val RECHARGE_URL = "https://platform.deepseek.com/top_up"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onManageKeys: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // 自动刷新只在前台进行：切到后台就通知 ViewModel 停止轮询
    LifecycleResumeEffect(Unit) {
        viewModel.onScreenResumed()
        onPauseOrDispose { viewModel.onScreenPaused() }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        modifier = modifier,
        // 透明容器：让全局氛围背景透上来，玻璃卡片才有内容可折射
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(8.dp))

                HeaderRow(keyName = state.activeKey?.name)

                when {
                    state.activeKey == null -> NoKeyPrompt(onManageKeys)

                    state.isLoading -> LoadingCard()

                    state.error != null -> ErrorCard(
                        error = state.error!!,
                        onRetry = viewModel::refresh,
                        onManageKeys = onManageKeys,
                    )

                    state.balance != null -> {
                        BalanceCard(
                            balance = state.balance!!,
                            onRecharge = { openInBrowser(context, RECHARGE_URL) },
                        )
                        UpdatedAtRow(
                            lastUpdatedAt = state.lastUpdatedAt,
                            onRefresh = viewModel::refresh,
                        )
                    }

                    else -> LoadingCard()
                }

                if (state.activeKey != null && state.balance != null) {
                    TodayUsageCard(
                        usage = state.todayUsage,
                        totalSampleCount = state.totalSampleCount,
                        sampleCoverageStartAt = state.sampleCoverageStartAt,
                    )
                }

                PeakWindowCard(status = state.peakStatus, calendar = state.holidayCalendar)

                // 给底部悬浮的玻璃三段式开关留出空间
                Spacer(Modifier.height(110.dp))
            }
        }
    }
}

@Composable
private fun HeaderRow(keyName: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "账户余额",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = keyName ?: "尚未选择 API Key",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BalanceCard(balance: Balance, onRecharge: () -> Unit) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        surfaceColor = Color.Transparent,
    ) {
        Box(
            modifier = Modifier.background(
                Brush.linearGradient(
                    listOf(
                        BalanceGradientStart.copy(alpha = 0.90f),
                        BalanceGradientEnd.copy(alpha = 0.94f),
                    ),
                ),
            ),
        ) {
            // 顶部一道白色高光，模拟玻璃边缘的镜面反射
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.White.copy(alpha = 0f),
                                Color.White.copy(alpha = 0.55f),
                                Color.White.copy(alpha = 0f),
                            ),
                        ),
                    ),
            )
            Column(modifier = Modifier.fillMaxWidth().padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "总余额",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                    Spacer(Modifier.weight(1f))
                    StatusChip(available = balance.isAvailable)
                }

                Spacer(Modifier.height(8.dp))

                val entry = balance.primary
                if (entry == null) {
                    Text(
                        text = "官方未返回余额信息",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = entry.currency.symbol.ifEmpty { entry.rawCurrencyCode },
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = formatAmount(entry.total),
                            style = MaterialTheme.typography.displaySmall,
                            color = Color.White,
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AmountColumn(
                            label = "赠金余额",
                            symbol = entry.currency.symbol,
                            amount = entry.granted,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(34.dp)
                                .background(Color.White.copy(alpha = 0.22f)),
                        )
                        Spacer(Modifier.width(18.dp))
                        AmountColumn(
                            label = "充值余额",
                            symbol = entry.currency.symbol,
                            amount = entry.toppedUp,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // 一键充值：直接落到开放平台充值页，省掉"自己找网址"的步骤
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "余额不足时可直接充值",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.weight(1f),
                        )
                        RechargePill(onClick = onRecharge)
                    }
                }
            }
        }
    }
}

/** 余额卡片里的白底半透明胶囊按钮，和蓝色卡片保持同一套玻璃语言。 */
@Composable
private fun RechargePill(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.20f))
            .border(1.dp, Color.White.copy(alpha = 0.38f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "去充值",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "↗",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}

/** 用系统浏览器打开链接；没有可用浏览器时给一句提示而不是静默失败。 */
private fun openInBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val launched = runCatching { context.startActivity(intent) }.isSuccess
    if (!launched) {
        Toast.makeText(context, "没有找到可用的浏览器，请手动访问 platform.deepseek.com", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun AmountColumn(
    label: String,
    symbol: String,
    amount: BigDecimal,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = "$symbol${formatAmount(amount)}",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
        )
    }
}

@Composable
private fun StatusChip(available: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.White.copy(alpha = 0.18f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(if (available) AvailableDot else UnavailableDot, CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (available) "可用" else "不可用",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun UpdatedAtRow(lastUpdatedAt: Long?, onRefresh: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (lastUpdatedAt != null) {
                "更新于 ${formatTime(lastUpdatedAt)}"
            } else {
                "尚未成功查询"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRefresh) { Text("刷新") }
    }
}

@Composable
private fun LoadingCard() {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun ErrorCard(
    error: HomeError,
    onRetry: () -> Unit,
    onManageKeys: () -> Unit,
) {
    val message = when (error) {
        is HomeError.Request -> error.error.userMessage
        HomeError.KeyUnavailable -> "本地保存的密钥已无法解密（可能系统重置过）。请重新添加这个 API Key。"
    }

    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        surfaceColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.82f),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRetry) { Text("重试") }
                if (error is HomeError.KeyUnavailable) {
                    TextButton(onClick = onManageKeys) { Text("管理 Key") }
                }
            }
        }
    }
}

@Composable
private fun NoKeyPrompt(onManageKeys: () -> Unit) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "先添加一个 API Key",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "余额查询需要 DeepSeek 的 API Key。密钥会经 Android Keystore 加密后保存在本机。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onManageKeys) { Text("添加 API Key") }
        }
    }
}

@Composable
private fun TodayUsageCard(
    usage: DailyUsage?,
    totalSampleCount: Int,
    sampleCoverageStartAt: Long?,
) {
    var showDetail by remember { mutableStateOf(false) }
    val hasValue = usage != null && usage.hasEnoughSamples

    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "今日用量",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "按本机余额采样推算",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (hasValue) {
                            "${usage!!.currency.symbol}${formatAmount(usage.estimatedSpend)}"
                        } else {
                            "—"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (hasValue) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    DetailEntryButton(onClick = { showDetail = true })
                }
            }

            // 只在「算不出来」时给一行提示，能算出数值时主界面不留解释文字。
            val hint = when {
                usage == null || usage.sampleCount == 0 ->
                    "今天还没有采样记录，刷新一次会记一条。"
                !usage.hasEnoughSamples ->
                    "今天只有 ${usage.sampleCount} 条采样，至少 2 条才能算出变化。"
                else -> null
            }
            if (hint != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 有充值会影响推算准确性，属于必须让用户看到的异常状态。
            if (usage != null && usage.hasRecharge) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "期间检测到充值，余额上升部分未计入用量。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showDetail) {
        DetailDialog(
            title = "今日用量说明",
            onDismiss = { showDetail = false },
        ) {
            DetailSection(
                heading = "这个数字怎么来的",
                body = "官方未开放账单 / 历史用量接口，此数值由本机采样推算：" +
                    "把当天相邻两次余额的下降额累加，采样间隔内的细分消耗无法拆分，" +
                    "也不是官方计费口径。",
            )
            DetailSection(
                heading = "今日采样情况",
                body = when {
                    usage == null || usage.sampleCount == 0 ->
                        "还没有余额采样记录。刷新一次会记一条，攒够两条才能算出变化。"
                    !usage.hasEnoughSamples ->
                        "已有 ${usage.sampleCount} 条采样，至少 2 条才能算出今日变化。"
                    else ->
                        "今日采样 ${usage.sampleCount} 次 · 覆盖 " +
                            "${formatClock(usage.coverageStartAt)}–${formatClock(usage.lastSampleAt)}"
                },
            )
            if (totalSampleCount > 0 && sampleCoverageStartAt != null) {
                DetailSection(
                    heading = "数据覆盖起点",
                    body = "本机累计 $totalSampleCount 条采样，" +
                        "最早一条在 ${formatDate(sampleCoverageStartAt)}。",
                )
            }
            if (usage != null && usage.hasRecharge) {
                DetailSection(
                    heading = "关于充值",
                    body = "这段时间里检测到余额上升，视为充值，上升部分不计入今日用量，" +
                        "也不会用来抵消此前的下降额。",
                )
            }
            DetailSection(
                heading = "余额数据来源",
                body = "余额来自 DeepSeek 官方接口 GET /user/balance。" +
                    "官方未开放历史用量与账单接口，页面中的「今日用量」是本应用按余额采样自己推算的，" +
                    "不是官方计费数据，也不会用余额差值冒充消费额。",
            )
        }
    }
}

@Composable
private fun PeakWindowCard(status: PeakStatus?, calendar: HolidayCalendar?) {
    var showDetail by remember { mutableStateOf(false) }
    val dark = isSystemInDarkTheme()
    // 高峰＝梁文峰（红），错峰＝梁文谷（绿）
    val valleyBg = if (dark) Color(0xFF0F3A2C) else Color(0xFFD9F7E6)
    val valleyFg = if (dark) Color(0xFF63E6A8) else Color(0xFF0B6B42)
    val peakBg = if (dark) Color(0xFF3A1220) else Color(0xFFFFE1E6)
    val peakFg = if (dark) Color(0xFFFF8A9B) else Color(0xFFB3122E)

    val isPeak = status?.isPeak == true
    val badgeBg = if (isPeak) peakBg else valleyBg
    val badgeFg = if (isPeak) peakFg else valleyFg

    // 今天为什么是高峰/错峰：把"日子本身的性质"说清楚，
    // 避免用户看到"错峰"却不知道是周末、节假日还是普通工作日的非高峰时段。
    val dayKind = status?.let {
        when {
            it.isHoliday -> "今天是中国法定节假日：全天按错峰计费"
            it.isMakeupWorkday -> "今天是调休上班日：按工作日计算，仍会进入高峰"
            it.isWeekend -> "今天是周末：全天按错峰计费"
            else -> "今天是工作日：高峰只在上面两个时段"
        }
    }

    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "计费时段",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "DeepSeek 峰谷计价",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Surface(shape = RoundedCornerShape(50), color = badgeBg) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(badgeFg, CircleShape),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isPeak) PeakBadge.PEAK else PeakBadge.OFF_PEAK,
                                style = MaterialTheme.typography.labelMedium,
                                color = badgeFg,
                            )
                        }
                    }
                    DetailEntryButton(onClick = { showDetail = true })
                }
            }

            if (status != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (isPeak) {
                        "当前按高峰价计费，错峰时段为高峰价的 5 折。"
                    } else {
                        "当前按错峰价计费，约为高峰价的 5 折。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(if (status.nextIsPeak) "距高峰开始 " else "距错峰开始 ")
                        append(formatCountdown(status.timeUntilChange))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showDetail) {
        DetailDialog(
            title = "计费时段说明",
            onDismiss = { showDetail = false },
        ) {
            if (dayKind != null) {
                DetailSection(heading = "今天为什么是这个时段", body = dayKind)
            }
            DetailSection(
                heading = "高峰时段（北京时间）",
                body = "周一至周五 09:00–12:00、14:00–18:00。" +
                    "其余时间、以及整个周末均为错峰，错峰价是高峰价的一半。",
            )
            val cal = calendar
            val covered = status?.calendarCoversToday == true
            DetailSection(
                heading = "官方规则与节假日判定",
                body = "官方规则按 UTC 01:00–04:00、06:00–10:00（周一至周五）计算，" +
                    "并排除中国法定节假日。" + when {
                        cal == null || cal.version == "none" ->
                            "内置节假日表读取失败，节假日当天会被误判为高峰。"
                        covered ->
                            "已内置官方节假日安排（${cal.version}，覆盖至 ${cal.validUntil}），" +
                                "含调休上班日，表内日期判定准确。"
                        else ->
                            "内置节假日表只覆盖到 ${cal.validUntil}，今天已超出范围，" +
                                "只能按星期几判断，节假日当天可能误判。"
                    },
            )
        }
    }
}

/** 卡片标题行右侧的「查看详情」入口，紧凑样式，右贴边。 */
@Composable
private fun DetailEntryButton(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(30.dp),
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
    ) {
        Text(
            text = "查看详情",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** 长说明统一收进这个弹窗，正文可滚动。 */
@Composable
private fun DetailDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        },
    )
}

@Composable
private fun DetailSection(heading: String, body: String) {
    Column {
        Text(
            text = heading,
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatAmount(value: BigDecimal): String =
    value.setScale(2, RoundingMode.HALF_UP).toPlainString()

private fun formatTime(epochMillis: Long): String {
    val dateTime = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    return dateTime.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
}

/** 只显示时:分，用于"覆盖 18:16–18:20"这类区间描述。 */
private fun formatClock(epochMillis: Long?): String {
    if (epochMillis == null) return "—"
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))
}

private fun formatDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))

/** 把"距下次切换"的时长转成中文可读文案。 */
private fun formatCountdown(duration: Duration): String {
    val totalMinutes = duration.toMinutes().coerceAtLeast(0)
    return when {
        totalMinutes < 1 -> "不到 1 分钟"
        totalMinutes < 60 -> "$totalMinutes 分钟"
        else -> {
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            if (minutes == 0L) "$hours 小时" else "$hours 小时 $minutes 分钟"
        }
    }
}

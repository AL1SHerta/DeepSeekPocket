package com.ybdsj.deeppocket.ui.settings

import android.Manifest
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ybdsj.deeppocket.BuildConfig
import com.ybdsj.deeppocket.data.settings.RefreshInterval
import com.ybdsj.deeppocket.platform.BatteryOptimization
import com.ybdsj.deeppocket.ui.glass.GlassPanel
import com.ybdsj.deeppocket.ui.update.UpdateAvailableDialog
import com.ybdsj.deeppocket.ui.update.UpdatePrompt
import com.ybdsj.deeppocket.widget.BalanceWidgetWideProvider
import com.ybdsj.deeppocket.work.BalanceCheckScheduler

/**
 * 设置页：自动刷新间隔、后台巡检开关、余额预警阈值。
 *
 * 三项都是「改完即保存」，不需要额外的保存按钮（阈值输入除外，因为它需要一个明确的提交点）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var thresholdText by rememberSaveable { mutableStateOf("") }
    var thresholdLoaded by rememberSaveable { mutableStateOf(false) }

    // 电池优化白名单状态：每次回到本页都重新读一次，用户去系统设置改完回来能立刻看到变化
    var batteryOptimizationIgnored by remember {
        mutableStateOf(BatteryOptimization.isIgnoring(context))
    }
    LifecycleResumeEffect(Unit) {
        batteryOptimizationIgnored = BatteryOptimization.isIgnoring(context)
        onPauseOrDispose { }
    }

    // 打开常驻通知时顺带申请通知权限，批完直接生效，不用再点一次开关
    var pendingStatusNotification by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.refreshNotificationPermission()
        if (granted && pendingStatusNotification) {
            viewModel.setStatusNotificationEnabled(true)
        }
        pendingStatusNotification = false
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    // 首次读到已保存的阈值后回填输入框，之后不再覆盖用户正在输入的内容
    LaunchedEffect(state.isLoading, state.settings.alertThreshold) {
        if (!state.isLoading && !thresholdLoaded) {
            thresholdText = state.settings.alertThreshold?.toPlainString().orEmpty()
            thresholdLoaded = true
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("设置", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            text = "刷新间隔 · 后台巡检 · 余额预警",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                expandedHeight = 92.dp,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // 0. 界面效果
            SettingsCard(
                title = "界面效果",
                subtitle = "液态玻璃让卡片和底部导航透出背景的模糊与折射。",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("液态玻璃效果", style = MaterialTheme.typography.bodyLarge)
                        Caption(
                            if (state.settings.liquidGlassEnabled) {
                                "当前已开启，界面有实时背景模糊与边缘折射。"
                            } else {
                                "当前已关闭，卡片与底部导航使用不透明配色。"
                            },
                        )
                    }
                    Switch(
                        checked = state.settings.liquidGlassEnabled,
                        onCheckedChange = viewModel::setLiquidGlassEnabled,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Caption(
                    "关闭后更省电、滚动更稳，适合低端机或电量紧张时使用；" +
                        "Android 12 以下设备不支持实时模糊，会自动使用关闭后的样式。",
                )
            }

            // 1. 自动刷新
            SettingsCard(title = "自动刷新", subtitle = "应用停留在当前界面时，每隔多久自动查询一次余额。") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RefreshInterval.entries.forEach { interval ->
                        FilterChip(
                            selected = state.settings.refreshInterval == interval,
                            onClick = { viewModel.setRefreshInterval(interval) },
                            label = { Text(interval.label) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (state.settings.refreshInterval in RefreshInterval.aggressive) {
                    Caption("间隔较短时更容易遇到接口限流（HTTP 429），建议 1 分钟以上。")
                }
                Caption("仅前台生效。应用切到后台后系统不允许按秒轮询，请用下面的「后台巡检」。")
            }

            // 2. 后台巡检
            SettingsCard(
                title = "后台巡检",
                subtitle = "应用不在前台时，也定期查一次余额并在跌破阈值时提醒。",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("开启后台巡检", style = MaterialTheme.typography.bodyLarge)
                        Caption("系统最短周期 ${BalanceCheckScheduler.INTERVAL_MINUTES} 分钟，实际触发时间由系统决定。")
                    }
                    Switch(
                        checked = state.settings.backgroundCheckEnabled,
                        onCheckedChange = viewModel::setBackgroundCheckEnabled,
                    )
                }
            }

            // 3. 电池优化
            SettingsCard(
                title = "电池优化",
                subtitle = "系统把本应用冻结后，后台巡检与预警都会被推迟，甚至静默丢弃。",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (batteryOptimizationIgnored) {
                                "已豁免电池优化"
                            } else {
                                "仍受电池优化限制"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Caption(
                            if (batteryOptimizationIgnored) {
                                "系统不会因为省电而冻结本应用，后台巡检可以按计划触发。"
                            } else {
                                "系统可能推迟或取消后台巡检，预警因此延迟。建议加入白名单。"
                            },
                        )
                    }
                    if (!batteryOptimizationIgnored) {
                        TextButton(onClick = { BatteryOptimization.openSettings(context) }) {
                            Text("去设置")
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Caption(
                    "本应用不申请「忽略电池优化」权限（该权限仅限闹钟、VoIP 等类别，普通应用声明后上架会被拒），" +
                        "只把你带到系统设置页，由你自己决定是否把本应用加入白名单。",
                )
            }

            // 4. 余额预警
            SettingsCard(
                title = "余额预警",
                subtitle = "余额低于设定值时，本机发一条通知提醒你。",
            ) {
                OutlinedTextField(
                    value = thresholdText,
                    onValueChange = { thresholdText = it },
                    label = { Text("预警阈值（元）") },
                    placeholder = { Text("例如 10") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { viewModel.saveAlertThreshold(thresholdText) }) {
                        Text("保存阈值")
                    }
                    if (state.settings.alertEnabled) {
                        TextButton(onClick = {
                            thresholdText = ""
                            viewModel.saveAlertThreshold("")
                        }) {
                            Text("关闭预警")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (state.settings.alertEnabled) {
                            "已启用 · 低于 ${state.settings.alertThreshold?.toPlainString()} 元提醒"
                        } else {
                            "未启用"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (!state.canPostNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Spacer(Modifier.height(4.dp))
                    Caption("通知权限未开启，预警消息无法送达。")
                    TextButton(onClick = {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }) {
                        Text("开启通知权限")
                    }
                }
                Caption("同一段低于阈值的余额只提醒一次；余额回到阈值以上后，下次跌破会再次提醒。")
            }

            // 5. 常驻通知
            SettingsCard(
                title = "常驻通知",
                subtitle = "在下拉通知栏里放一条余额速览，锁屏和解锁都不打扰。",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("显示余额状态通知", style = MaterialTheme.typography.bodyLarge)
                        Caption(
                            if (state.settings.statusNotificationEnabled) {
                                "已开启 · 内容：余额 / 今日用量 / 峰谷时段"
                            } else {
                                "关闭时不占用通知栏"
                            },
                        )
                    }
                    Switch(
                        checked = state.settings.statusNotificationEnabled,
                        onCheckedChange = { checked ->
                            if (checked && !state.canPostNotifications &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                            ) {
                                pendingStatusNotification = true
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.setStatusNotificationEnabled(checked)
                            }
                        },
                    )
                }
                Spacer(Modifier.height(4.dp))
                Caption(
                    "通知使用最低优先级、无声音无振动、不显示角标，也不会亮屏；" +
                        "只在通知栏里看到，锁屏上默认隐藏，适合随手一瞥。",
                )
            }

            // 6. 隐私说明
            SettingsCard(title = "隐私", subtitle = null) {
                Caption(
                    "刷新间隔、阈值等设置保存在本机 DataStore 中，不含任何密钥材料；" +
                        "预警通知由本机 NotificationManager 发出，不经过任何服务器；" +
                        "日志不打印 API Key，也不开启 HTTP 抓包日志。",
                )
            }

            // 6. 桌面小组件
            SettingsCard(
                title = "桌面小组件",
                subtitle = "把余额放到桌面，点一下直接回到应用。",
            ) {
                Caption("4×1：左边总余额、中间今日用量、右边计费时段，和应用里看到的是同一套。")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        requestPinWidget(context, BalanceWidgetWideProvider::class.java)
                    }) { Text("添加到桌面") }
                }
                val widgetCount = rememberWidgetCount(context)
                Caption("桌面已添加：$widgetCount 个")
                Spacer(Modifier.height(4.dp))
                Caption(
                    "小组件数据来自最近一次余额刷新（前台刷新或后台巡检），" +
                        "若桌面不支持一键添加，请长按桌面空白处 → 小组件 → DeepSeek Pocket 手动拖出。",
                )
            }

            // 7. 关于
            SettingsCard(title = "关于", subtitle = null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("DeepSeek Pocket", style = MaterialTheme.typography.bodyLarge)
                        Caption("版本 ${BuildConfig.VERSION_NAME}（Build ${BuildConfig.VERSION_CODE}）")
                    }
                    TextButton(
                        onClick = { viewModel.checkUpdate() },
                        enabled = !state.isCheckingUpdate,
                    ) {
                        Text(if (state.isCheckingUpdate) "检查中…" else "检查更新")
                    }
                }
                state.updateMessage?.let { message ->
                    Spacer(Modifier.height(4.dp))
                    Caption(message)
                }
                Spacer(Modifier.height(4.dp))
                Caption("更新包从百度网盘下载，安装时选覆盖安装即可，本地的 API Key 与设置都会保留。")
                // 检查更新依赖远端清单，仓库还没建好或网络不通时，这条路保证用户仍能拿到新版
                TextButton(onClick = { UpdatePrompt.openDownloadFallback(context) }) {
                    Text("去网盘下载最新版")
                }
            }

            // 8. 开发者
            DeveloperCard()

            Spacer(Modifier.height(110.dp))
        }
    }

    state.latestUpdate?.let { manifest ->
        val forced = manifest.forceBelowVersionCode?.let { BuildConfig.VERSION_CODE < it } ?: false
        UpdateAvailableDialog(
            manifest = manifest,
            forced = forced,
            onIgnore = { viewModel.ignoreLatestUpdate() },
            onUpgrade = { UpdatePrompt.openDownload(context, manifest) },
            onDismiss = { viewModel.dismissUpdateDialog() },
        )
    }
}

/**
 * 请求把指定尺寸的小组件钉到桌面。
 *
 * 系统会弹一个「是否添加」的确认框；但部分国产桌面（MIUI / HyperOS 就是）会把这个请求
 * 吞掉：API 返回 true，却连确认框都不显示。所以无论成败都给一句可执行的手动指引，
 * 而不是让按钮点了没反应。
 */
private fun requestPinWidget(context: Context, provider: Class<out AppWidgetProvider>) {
    val manager = AppWidgetManager.getInstance(context)
    val supported = manager.isRequestPinAppWidgetSupported
    val requested = supported && runCatching {
        manager.requestPinAppWidget(ComponentName(context, provider), null, null)
    }.getOrDefault(false)
    val message = if (requested) {
        "已请求添加 4×1 小组件；若桌面没有出现确认框，请长按桌面空白处 → 小组件 → DeepSeek Pocket 手动拖出"
    } else {
        "当前桌面不支持一键添加，请长按桌面空白处 → 小组件 → DeepSeek Pocket 手动添加"
    }
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

/** 读取小组件目前被添加到桌面的实例数，用来给用户一个「到底加成功没」的反馈。 */
@Composable
private fun rememberWidgetCount(context: Context): Int {
    val count = remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        count.intValue = readWidgetCount(context)
        onPauseOrDispose { }
    }
    return count.intValue
}

private fun readWidgetCount(context: Context): Int {
    val manager = AppWidgetManager.getInstance(context)
    return runCatching {
        manager.getAppWidgetIds(ComponentName(context, BalanceWidgetWideProvider::class.java)).size
    }.getOrDefault(0)
}

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String?,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Caption(subtitle)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

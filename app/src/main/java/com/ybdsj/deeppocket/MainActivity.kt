package com.ybdsj.deeppocket

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ybdsj.deeppocket.data.settings.AppSettings
import com.ybdsj.deeppocket.ui.disclaimer.DisclaimerDialog
import com.ybdsj.deeppocket.ui.glass.AmbientBackdropLayer
import com.ybdsj.deeppocket.ui.glass.GlassTab
import com.ybdsj.deeppocket.ui.glass.GlassTabBar
import com.ybdsj.deeppocket.ui.glass.LocalGlassBackdrop
import com.ybdsj.deeppocket.ui.glass.LocalLiquidGlassEnabled
import com.ybdsj.deeppocket.ui.glass.rememberAmbientBackdrop
import com.ybdsj.deeppocket.ui.home.HomeScreen
import com.ybdsj.deeppocket.ui.keys.KeyListScreen
import com.ybdsj.deeppocket.ui.settings.SettingsScreen
import com.ybdsj.deeppocket.ui.theme.DeepPocketTheme
import com.ybdsj.deeppocket.ui.update.UpdateAvailableDialog
import com.ybdsj.deeppocket.ui.update.UpdatePrompt
import com.ybdsj.deeppocket.update.UpdateCheckResult
import com.ybdsj.deeppocket.update.UpdateManifest
import kotlinx.coroutines.launch

private val Tabs = listOf(
    GlassTab(label = "首页", icon = Icons.Rounded.Home),
    GlassTab(label = "管理 Key", icon = Icons.Rounded.VpnKey),
    GlassTab(label = "设置", icon = Icons.Rounded.Settings),
)

private const val TAB_HOME = 0
private const val TAB_KEYS = 1
private const val TAB_SETTINGS = 2

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = LocalContext.current.applicationContext as DeepPocketApp
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            // initialValue 用 null 而不是默认 AppSettings()：DataStore 还没读出来时先不渲染，
            // 否则已同意过的用户会看到主页闪一下、或者反过来先亮主页再弹免责声明。
            val settings by app.container.settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = null)

            // 待展示的新版本（null = 没有要弹的）
            var pendingUpdate by remember { mutableStateOf<UpdateManifest?>(null) }

            DeepPocketTheme {
                val backdrop = rememberAmbientBackdrop()
                // 根容器不再是不透明 Surface，这里手动补回默认前景色，
                // 否则没有显式指定 color 的文本会退回 LocalContentColor 的默认黑色。
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                    LocalGlassBackdrop provides backdrop,
                    // 设置页的总开关：关掉后 GlassPanel / GlassTabBar 一起换成不透明配色
                    LocalLiquidGlassEnabled provides (settings?.liquidGlassEnabled ?: true),
                ) {
                    // 启动时检查一次新版本：节流 24 小时，用户忽略过的版本不再打扰。
                    // 放在免责声明之后，避免两层弹窗叠在一起。
                    LaunchedEffect(settings?.disclaimerAccepted) {
                        if (settings?.disclaimerAccepted == true && pendingUpdate == null) {
                            val result = UpdatePrompt.check(app, force = false)
                            if (result is UpdateCheckResult.Available && !result.isIgnored) {
                                pendingUpdate = result.manifest
                            }
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        // 氛围背景层：既是画面底色，也是玻璃卡片的实时采样源
                        AmbientBackdropLayer(backdrop)
                        when (settings?.disclaimerAccepted) {
                            null -> Unit
                            false -> DisclaimerDialog(
                                onAccept = {
                                    scope.launch { app.container.settingsRepository.setDisclaimerAccepted() }
                                },
                                onReject = { (context as? Activity)?.finishAffinity() },
                            )
                            true -> RootScreen()
                        }

                        pendingUpdate?.let { manifest ->
                            val forced = manifest.forceBelowVersionCode?.let {
                                BuildConfig.VERSION_CODE < it
                            } ?: false
                            UpdateAvailableDialog(
                                manifest = manifest,
                                forced = forced,
                                onIgnore = {
                                    scope.launch {
                                        app.container.settingsRepository
                                            .setIgnoredVersionCode(manifest.versionCode)
                                    }
                                    pendingUpdate = null
                                },
                                onUpgrade = { UpdatePrompt.openDownload(context, manifest) },
                                onDismiss = { pendingUpdate = null },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RootScreen() {
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_HOME) }
    val stateHolder = rememberSaveableStateHolder()

    // 三段平级切换，没有返回栈；在非首页按系统返回先回首页，再按一次才退出。
    BackHandler(enabled = selectedTab != TAB_HOME) {
        selectedTab = TAB_HOME
    }

    Box(Modifier.fillMaxSize()) {
        // 自适应：手机上是铺满全宽，平板/横屏/折叠屏展开态则把内容收在中间一列，
        // 避免一行文字横跨 1600px 读不动。底部三段式开关也按同一套思路限宽。
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 720.dp)
                .fillMaxSize(),
        ) {
            stateHolder.SaveableStateProvider(selectedTab) {
                when (selectedTab) {
                    TAB_KEYS -> KeyListScreen()
                    TAB_SETTINGS -> SettingsScreen()
                    else -> HomeScreen(
                        onManageKeys = { selectedTab = TAB_KEYS },
                        onOpenSettings = { selectedTab = TAB_SETTINGS },
                    )
                }
            }
        }

        GlassTabBar(
            tabs = Tabs,
            selectedIndex = selectedTab,
            onSelect = { selectedTab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 520.dp)
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        )
    }
}

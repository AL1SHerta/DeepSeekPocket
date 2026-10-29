package com.ybdsj.deeppocket.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ybdsj.deeppocket.DeepPocketApp
import com.ybdsj.deeppocket.core.result.ApiResult
import com.ybdsj.deeppocket.data.settings.AppSettings
import com.ybdsj.deeppocket.data.settings.RefreshInterval
import com.ybdsj.deeppocket.domain.model.Balance
import com.ybdsj.deeppocket.notification.StatusNoticePresenter
import com.ybdsj.deeppocket.ui.update.UpdatePrompt
import com.ybdsj.deeppocket.update.UpdateCheckResult
import com.ybdsj.deeppocket.update.UpdateManifest
import com.ybdsj.deeppocket.work.BalanceCheckScheduler
import java.math.BigDecimal
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val isLoading: Boolean = true,
    val canPostNotifications: Boolean = true,
    /** 正在手动检查更新（按钮转圈并禁用） */
    val isCheckingUpdate: Boolean = false,
    /** 查到的新版本，非 null 时设置页弹「发现新版本」 */
    val latestUpdate: UpdateManifest? = null,
    /** 一次性的检查结果提示（已是最新 / 已忽略 / 失败原因） */
    val updateMessage: String? = null,
)

/**
 * 设置页状态。
 *
 * 所有写操作都是「改完立刻生效并持久化」，没有"保存"总开关——
 * 用户的原话是"保存一次后，就不需要每次再设置"，所以每个控件自己承担持久化。
 */
class SettingsViewModel(
    private val app: DeepPocketApp,
) : ViewModel() {

    private val settingsRepository = app.container.settingsRepository
    private val alertNotifier = app.container.alertNotifier

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings, isLoading = false) }
            }
        }
        refreshNotificationPermission()
    }

    /** 从系统设置页返回后需要重新读一次通知权限状态。 */
    fun refreshNotificationPermission() {
        _uiState.update {
            it.copy(canPostNotifications = alertNotifier.canPostNotifications())
        }
    }

    fun setRefreshInterval(interval: RefreshInterval) {
        viewModelScope.launch {
            runCatching { settingsRepository.setRefreshInterval(interval) }
                .onFailure { _messages.send("刷新间隔保存失败，请重试") }
        }
    }

    fun setBackgroundCheckEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching {
                settingsRepository.setBackgroundCheckEnabled(enabled)
                BalanceCheckScheduler.sync(app, enabled)
            }.onSuccess {
                _messages.send(
                    if (enabled) "已开启后台巡检，系统最短 15 分钟检查一次" else "已关闭后台巡检",
                )
            }.onFailure {
                _messages.send("后台巡检设置失败，请重试")
            }
        }
    }

    /**
     * 切换液态玻璃效果。关掉后卡片与底部导航立刻改成不透明配色，
     * 这在大屏低端机上是肉眼可见的省电/省 GPU 选项。
     */
    fun setLiquidGlassEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { settingsRepository.setLiquidGlassEnabled(enabled) }
                .onFailure { _messages.send("界面效果保存失败，请重试") }
        }
    }

    /**
     * 常驻通知开关。
     *
     * 关掉时立刻撤销已排队的通知，打开时先补一次内容再挂出去——否则用户会看到
     * 「开关是开的但通知栏没东西」的空窗期。
     */
    /**
     * 常驻通知开关。
     *
     * 关掉时立刻撤销已排队的通知，打开时先补一次内容再挂出去——否则用户会看到
     * 「开关是开的但通知栏没东西」的空窗期。
     *
     * 打开时还要真去拉一次余额：只传 null 的话通知会先挂在「余额 —」上，
     * 一直等到用户回首页刷新或 15 分钟后台巡检才换成真数字。
     */
    fun setStatusNotificationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { settingsRepository.setStatusNotificationEnabled(enabled) }
                .onSuccess {
                    if (!enabled) {
                        app.container.statusNotifier.cancel()
                    } else {
                        val balance = fetchCurrentBalance()
                        StatusNoticePresenter.sync(
                            settingsRepository = settingsRepository,
                            notifier = app.container.statusNotifier,
                            sampleRepository = app.container.balanceSampleRepository,
                            holidayCalendar = app.container.holidayCalendar,
                            balance = balance,
                        )
                        if (balance == null) {
                            _messages.send("还没拿到余额，回到首页刷新一次就会显示在通知里")
                        }
                    }
                }
                .onFailure { _messages.send("常驻通知设置失败，请重试") }
        }
    }

    /** 取当前 Key 的真实余额；没 Key、解密失败或请求失败都返回 null（通知退化成占位符）。 */
    private suspend fun fetchCurrentBalance(): Balance? {
        val key = app.container.apiKeyRepository.activeKey() ?: return null
        val plainKey = app.container.apiKeyRepository.resolvePlainKey(key.id) ?: return null
        return when (val result = app.container.balanceRepository.fetchBalance(plainKey)) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> null
        }
    }

    /**
     * 保存预警阈值。[raw] 为空表示关闭预警。
     *
     * 金额用 BigDecimal 解析，拒绝负数与非法输入——这里不做四舍五入，
     * 用户输入什么就按什么比较，避免"设了 10 元实际按 9.5 判断"这种意外。
     */
    fun saveAlertThreshold(raw: String) {
        val trimmed = raw.trim()
        viewModelScope.launch {
            if (trimmed.isEmpty()) {
                settingsRepository.setAlertThreshold(null)
                _messages.send("已关闭余额预警")
                return@launch
            }
            val value = trimmed.toBigDecimalOrNull()
            if (value == null || value <= BigDecimal.ZERO) {
                _messages.send("请输入大于 0 的金额，例如 10")
                return@launch
            }
            settingsRepository.setAlertThreshold(value)
            _messages.send("已保存预警阈值：$value 元")
        }
    }

    /**
     * 手动检查更新（跳过节流）。
     *
     * 成功/失败都只给一句人话：`UpdateCheckResult.Failed.reason` 里的 HTTP 状态码、
     * 超时细节对用户没有意义，留给日志。
     */
    fun checkUpdate() {
        if (_uiState.value.isCheckingUpdate) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingUpdate = true, updateMessage = null) }
            val result = UpdatePrompt.check(app, force = true)
            _uiState.update { current ->
                when (result) {
                    is UpdateCheckResult.Available -> current.copy(
                        isCheckingUpdate = false,
                        latestUpdate = if (result.isIgnored) null else result.manifest,
                        updateMessage = if (result.isIgnored) {
                            "已忽略 ${result.manifest.versionName}，等下一版发布后会再提醒"
                        } else {
                            null
                        },
                    )
                    UpdateCheckResult.UpToDate -> current.copy(
                        isCheckingUpdate = false,
                        latestUpdate = null,
                        updateMessage = "已是最新版本",
                    )
                    is UpdateCheckResult.Failed -> current.copy(
                        isCheckingUpdate = false,
                        latestUpdate = null,
                        // 技术细节（HTTP 404 / 超时堆栈）进日志就够，界面上只给一句人话。
                        updateMessage = "暂时拿不到更新信息，请稍后重试，或直接去网盘下载最新版",
                    )
                    null -> current.copy(isCheckingUpdate = false)
                }
            }
        }
    }

    /** 忽略当前提示的版本：直到远端出现更高 versionCode 才再次提醒。 */
    fun ignoreLatestUpdate() {
        val manifest = _uiState.value.latestUpdate ?: return
        viewModelScope.launch {
            runCatching { settingsRepository.setIgnoredVersionCode(manifest.versionCode) }
                .onFailure { _messages.send("忽略失败，请重试") }
            _uiState.update {
                it.copy(latestUpdate = null, updateMessage = "已忽略 ${manifest.versionName}")
            }
        }
    }

    fun dismissUpdateDialog() {
        _uiState.update { it.copy(latestUpdate = null) }
    }

    fun clearUpdateMessage() {
        _uiState.update { it.copy(updateMessage = null) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DeepPocketApp
                SettingsViewModel(app)
            }
        }
    }
}

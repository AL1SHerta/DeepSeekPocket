package com.ybdsj.deeppocket.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ybdsj.deeppocket.DeepPocketApp
import com.ybdsj.deeppocket.core.error.ApiError
import com.ybdsj.deeppocket.core.result.ApiResult
import com.ybdsj.deeppocket.data.repository.ApiKeyRepository
import com.ybdsj.deeppocket.data.repository.BalanceRepository
import com.ybdsj.deeppocket.data.repository.BalanceSampleRepository
import com.ybdsj.deeppocket.data.settings.SettingsRepository
import com.ybdsj.deeppocket.domain.billing.HolidayCalendar
import com.ybdsj.deeppocket.domain.billing.PeakHours
import com.ybdsj.deeppocket.domain.billing.PeakStatus
import com.ybdsj.deeppocket.domain.model.Balance
import com.ybdsj.deeppocket.domain.model.Currency
import com.ybdsj.deeppocket.domain.model.DailyUsage
import com.ybdsj.deeppocket.domain.model.StoredApiKey
import com.ybdsj.deeppocket.notification.BalanceAlertNotifier
import com.ybdsj.deeppocket.notification.StatusNoticePresenter
import com.ybdsj.deeppocket.notification.StatusNotifier
import com.ybdsj.deeppocket.widget.WidgetBridge
import com.ybdsj.deeppocket.work.AlertThrottle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/** 首页可能出现的错误：网络层错误，或本地密钥已不可用。 */
sealed interface HomeError {
    data class Request(val error: ApiError) : HomeError
    data object KeyUnavailable : HomeError
}

data class HomeUiState(
    val activeKey: StoredApiKey? = null,
    val balance: Balance? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: HomeError? = null,
    val lastUpdatedAt: Long? = null,
    /** 今日用量估算（本机采样推算，非官方账单）。 */
    val todayUsage: DailyUsage? = null,
    /** 本地采样最早时刻，用于说明"数据从何时开始积累"。 */
    val sampleCoverageStartAt: Long? = null,
    /** 本地累计采样条数。 */
    val totalSampleCount: Int = 0,
    /** 当前计费时段（高峰 / 错峰）。 */
    val peakStatus: PeakStatus? = null,
    /** 内置节假日表元信息（版本、来源、覆盖范围），用于在界面上如实标注判定依据。 */
    val holidayCalendar: HolidayCalendar? = null,
)

/**
 * 首页状态：跟随当前选中的 Key 查询余额。
 *
 * 切换 Key 会自动重新查询；下拉刷新走同一条路径。
 * 明文 Key 只在 [refresh] 内部存活于局部变量，不进入任何 State。
 *
 * 除余额外还维护两件"由本应用自己算出来"的事实：
 * - [HomeUiState.todayUsage]：靠余额采样序列推算，绝不冒充官方计费数据；
 * - [HomeUiState.peakStatus]：按官方公布的 UTC 时段规则每 30 秒重算一次。
 */
class HomeViewModel(
    private val appContext: Context,
    private val keyRepository: ApiKeyRepository,
    private val balanceRepository: BalanceRepository,
    private val sampleRepository: BalanceSampleRepository,
    private val settingsRepository: SettingsRepository,
    private val alertNotifier: BalanceAlertNotifier,
    private val statusNotifier: StatusNotifier,
    private val holidayCalendar: HolidayCalendar = HolidayCalendar.EMPTY,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState(holidayCalendar = holidayCalendar))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** 界面是否处于前台。自动刷新只在 true 时进行，切到后台就停。 */
    private val isForeground = MutableStateFlow(false)

    init {
        startPeakTicker()
        startAutoRefresh()
        viewModelScope.launch { refreshUsageStats() }

        viewModelScope.launch {
            keyRepository.observeKeys().collect { keys ->
                val active = keys.firstOrNull { it.isActive }
                val previousId = _uiState.value.activeKey?.id
                val balanceBelongsToOtherKey = previousId != active?.id

                _uiState.update { state ->
                    state.copy(
                        activeKey = active,
                        // 换了 Key 就丢弃旧 Key 的余额，避免把 A 的余额标成 B 的
                        balance = if (balanceBelongsToOtherKey) null else state.balance,
                        error = if (balanceBelongsToOtherKey) null else state.error,
                        lastUpdatedAt = if (balanceBelongsToOtherKey) null else state.lastUpdatedAt,
                    )
                }

                when {
                    active == null -> {
                        // 没有可用 Key 时，常驻通知里的余额已经没有意义，直接撤掉
                        statusNotifier.cancel()
                        _uiState.update {
                            it.copy(balance = null, error = null, isLoading = false, isRefreshing = false)
                        }
                    }

                    balanceBelongsToOtherKey -> refresh(isUserInitiated = false)
                }
            }
        }
    }

    /** 用户主动刷新（下拉或点击重试）。 */
    fun refresh() = refresh(isUserInitiated = true)

    private fun refresh(isUserInitiated: Boolean) {
        val keyId = _uiState.value.activeKey?.id ?: return
        if (_uiState.value.isLoading || _uiState.value.isRefreshing) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = it.balance == null,
                    isRefreshing = isUserInitiated && it.balance != null,
                    error = null,
                )
            }

            val plainKey = keyRepository.resolvePlainKey(keyId)
            if (plainKey == null) {
                _uiState.update {
                    it.copy(isLoading = false, isRefreshing = false, error = HomeError.KeyUnavailable)
                }
                return@launch
            }

            when (val result = balanceRepository.fetchBalance(plainKey)) {
                is ApiResult.Success -> {
                    keyRepository.markUsed(keyId)
                    // 先落采样，再统计，保证这次刷新的结果计入今日用量
                    sampleRepository.record(result.data, keyId)
                    _uiState.update {
                        it.copy(
                            balance = result.data,
                            isLoading = false,
                            isRefreshing = false,
                            error = null,
                            lastUpdatedAt = now(),
                        )
                    }
                    refreshUsageStats()
                    evaluateLowBalanceAlert(result.data)
                    updateStatusNotice(result.data)
                    // 桌面小组件跟前台数据保持一致；写快照失败不影响主流程
                    runCatching {
                        WidgetBridge.publish(appContext, result.data, _uiState.value.todayUsage)
                    }
                }

                is ApiResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            error = HomeError.Request(result.error),
                        )
                    }
                }
            }
        }
    }

    /** 重新计算今日用量与采样覆盖范围。采样失败（如迁移未完成）不应影响余额展示。 */
    private suspend fun refreshUsageStats() {
        val currency = _uiState.value.balance?.primary?.currency ?: Currency.CNY
        val usage = runCatching { sampleRepository.todayUsage(currency) }.getOrNull()
        val coverage = runCatching { sampleRepository.coverageStartAt() }.getOrNull()
        val count = runCatching { sampleRepository.sampleCount() }.getOrDefault(0)
        _uiState.update {
            it.copy(
                todayUsage = usage,
                sampleCoverageStartAt = coverage,
                totalSampleCount = count,
            )
        }
    }

    /** 每 30 秒重算一次峰谷状态，保证跨过时段边界时 UI 能自己更新。 */
    private fun startPeakTicker() {
        viewModelScope.launch {
            while (true) {
                val status = PeakHours.evaluate(Instant.ofEpochMilli(now()), holidayCalendar)
                _uiState.update { it.copy(peakStatus = status) }
                delay(PEAK_TICK_INTERVAL_MS)
            }
        }
    }

    /**
     * 按用户设置的间隔自动刷新。
     *
     * 只在界面处于前台时轮询：Android 不允许应用在后台按秒级定时任务，
     * 后台的定期检查交给 WorkManager（见 [com.ybdsj.deeppocket.work.BalanceCheckWorker]）。
     */
    private fun startAutoRefresh() {
        viewModelScope.launch {
            settingsRepository.settings
                .map { it.refreshInterval }
                .distinctUntilChanged()
                .collectLatest { interval ->
                    if (!interval.isEnabled) return@collectLatest
                    while (true) {
                        // 后台时挂起等待，回到前台才开始计时
                        isForeground.first { it }
                        delay(interval.seconds * 1_000L)
                        if (isForeground.value) refresh(isUserInitiated = false)
                    }
                }
        }
    }

    fun onScreenResumed() {
        isForeground.value = true
    }

    fun onScreenPaused() {
        isForeground.value = false
    }

    /**
     * 余额跌破阈值时发本地通知。
     *
     * 前台刷新也要走这一步，否则会出现「开着 App 不提醒、只有后台才提醒」的怪现象。
     * 去重逻辑在 [AlertThrottle] 里，同一段低位余额只提醒一次。
     */
    private suspend fun evaluateLowBalanceAlert(balance: Balance) {
        val settings = runCatching { settingsRepository.settings.first() }.getOrNull() ?: return
        if (!settings.alertEnabled) return
        AlertThrottle.applyIfNeeded(
            settingsRepository = settingsRepository,
            settings = settings,
            notifier = alertNotifier,
            total = balance.primary?.total,
            symbol = balance.primary?.currency?.symbol ?: "¥",
        )
    }

    /**
     * 常驻通知跟着余额一起更新。
     *
     * 用户在设置里关掉开关时，[StatusNoticePresenter] 会顺手把已有通知撤掉，
     * 所以这里不需要再判断开关。
     */
    private suspend fun updateStatusNotice(balance: Balance) {
        runCatching {
            StatusNoticePresenter.sync(
                settingsRepository = settingsRepository,
                notifier = statusNotifier,
                sampleRepository = sampleRepository,
                holidayCalendar = holidayCalendar,
                balance = balance,
            )
        }
    }

    companion object {
        private const val PEAK_TICK_INTERVAL_MS = 30_000L
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DeepPocketApp
                HomeViewModel(
                    appContext = app.applicationContext,
                    keyRepository = app.container.apiKeyRepository,
                    balanceRepository = app.container.balanceRepository,
                    sampleRepository = app.container.balanceSampleRepository,
                    settingsRepository = app.container.settingsRepository,
                    alertNotifier = app.container.alertNotifier,
                    statusNotifier = app.container.statusNotifier,
                    holidayCalendar = app.container.holidayCalendar,
                )
            }
        }
    }
}

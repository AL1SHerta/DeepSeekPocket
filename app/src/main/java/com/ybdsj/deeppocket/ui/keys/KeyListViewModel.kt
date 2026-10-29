package com.ybdsj.deeppocket.ui.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ybdsj.deeppocket.DeepPocketApp
import com.ybdsj.deeppocket.data.repository.AddKeyResult
import com.ybdsj.deeppocket.data.repository.ApiKeyRepository
import com.ybdsj.deeppocket.data.settings.SettingsRepository
import com.ybdsj.deeppocket.domain.model.StoredApiKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class KeyListUiState(
    val keys: List<StoredApiKey> = emptyList(),
    val isLoading: Boolean = true,
) {
    val activeKey: StoredApiKey? get() = keys.firstOrNull { it.isActive }
}

/**
 * Key 管理页的状态持有者。
 *
 * 列表来自 Room 的 Flow，增删改后 UI 自动刷新，不需要手动 reload。
 * 一次性提示（保存成功/校验失败）走 Channel，避免旋转屏幕后重复弹 Snackbar。
 */
class KeyListViewModel(
    private val repository: ApiKeyRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    val uiState: StateFlow<KeyListUiState> = repository.observeKeys()
        .map { keys -> KeyListUiState(keys = keys, isLoading = false) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = KeyListUiState(),
        )

    /** @return true 表示已保存成功，调用方据此关闭对话框。 */
    suspend fun addKey(name: String, apiKey: String): Boolean =
        when (val result = repository.addKey(name, apiKey)) {
            AddKeyResult.Success -> {
                _messages.send("API Key 已加密保存")
                true
            }

            is AddKeyResult.InvalidName -> {
                _messages.send(result.message)
                false
            }

            is AddKeyResult.InvalidKey -> {
                _messages.send(result.message)
                false
            }

            is AddKeyResult.StorageFailure -> {
                _messages.send("保存失败：${result.cause.message ?: "未知错误"}")
                false
            }
        }

    suspend fun renameKey(id: Long, name: String): Boolean =
        when (val result = repository.rename(id, name)) {
            AddKeyResult.Success -> {
                _messages.send("名称已更新")
                true
            }

            is AddKeyResult.InvalidName -> {
                _messages.send(result.message)
                false
            }

            is AddKeyResult.InvalidKey -> {
                _messages.send(result.message)
                false
            }

            is AddKeyResult.StorageFailure -> {
                _messages.send("更新失败：${result.cause.message ?: "未知错误"}")
                false
            }
        }

    fun setActive(id: Long) {
        viewModelScope.launch { repository.setActive(id) }
    }

    /**
     * 刚保存完 API Key，是否还要弹「重启应用让它生效」。
     *
     * 用户可以在弹窗里勾「不再提示」，之后就只看一次 Snackbar。
     */
    suspend fun shouldPromptRestart(): Boolean =
        runCatching { settingsRepository.settings.first().apiKeyRestartHintEnabled }.getOrDefault(true)

    fun disableRestartHint() {
        viewModelScope.launch { runCatching { settingsRepository.setApiKeyRestartHintEnabled(false) } }
    }

    fun deleteKey(id: Long) {
        viewModelScope.launch {
            repository.delete(id)
            _messages.send("已删除")
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DeepPocketApp
                KeyListViewModel(
                    repository = app.container.apiKeyRepository,
                    settingsRepository = app.container.settingsRepository,
                )
            }
        }
    }
}

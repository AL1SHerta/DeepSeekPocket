package com.ybdsj.deeppocket.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "deep_pocket_settings",
)

private val KEY_REFRESH_SECONDS = longPreferencesKey("refresh_interval_seconds")
private val KEY_BACKGROUND_CHECK = booleanPreferencesKey("background_check_enabled")
private val KEY_ALERT_THRESHOLD = stringPreferencesKey("alert_threshold")
private val KEY_ALERT_SUPPRESSED = booleanPreferencesKey("alert_suppressed")
private val KEY_LIQUID_GLASS = booleanPreferencesKey("liquid_glass_enabled")
private val KEY_DISCLAIMER_ACCEPTED = booleanPreferencesKey("disclaimer_accepted")
private val KEY_STATUS_NOTIFICATION = booleanPreferencesKey("status_notification_enabled")
private val KEY_IGNORED_VERSION = intPreferencesKey("ignored_version_code")
private val KEY_API_KEY_RESTART_HINT = booleanPreferencesKey("api_key_restart_hint_enabled")
private val KEY_LAST_UPDATE_CHECK = longPreferencesKey("last_update_check_at")

/**
 * 设置持久化。
 *
 * 全部内容都是"用户偏好"，不含任何密钥材料，因此放在普通 DataStore 里即可。
 */
class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            refreshInterval = RefreshInterval.fromSeconds(prefs[KEY_REFRESH_SECONDS] ?: 0L),
            backgroundCheckEnabled = prefs[KEY_BACKGROUND_CHECK] ?: false,
            alertThreshold = prefs[KEY_ALERT_THRESHOLD]?.toBigDecimalOrNull(),
            alertSuppressed = prefs[KEY_ALERT_SUPPRESSED] ?: false,
            liquidGlassEnabled = prefs[KEY_LIQUID_GLASS] ?: true,
            disclaimerAccepted = prefs[KEY_DISCLAIMER_ACCEPTED] ?: false,
            statusNotificationEnabled = prefs[KEY_STATUS_NOTIFICATION] ?: false,
            ignoredVersionCode = prefs[KEY_IGNORED_VERSION] ?: 0,
            apiKeyRestartHintEnabled = prefs[KEY_API_KEY_RESTART_HINT] ?: true,
        )
    }

    /** 上次成功检查更新的时刻，用于节流（默认一天一次）。 */
    val lastUpdateCheckAt: Flow<Long> =
        context.settingsDataStore.data.map { it[KEY_LAST_UPDATE_CHECK] ?: 0L }

    suspend fun setRefreshInterval(interval: RefreshInterval) {
        context.settingsDataStore.edit { it[KEY_REFRESH_SECONDS] = interval.seconds }
    }

    suspend fun setBackgroundCheckEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_BACKGROUND_CHECK] = enabled }
    }

    /** 传 null 表示关闭余额预警；关闭时顺带清掉去重标记。 */
    suspend fun setAlertThreshold(threshold: BigDecimal?) {
        context.settingsDataStore.edit { prefs ->
            if (threshold == null || threshold <= BigDecimal.ZERO) {
                prefs.remove(KEY_ALERT_THRESHOLD)
                prefs[KEY_ALERT_SUPPRESSED] = false
            } else {
                prefs[KEY_ALERT_THRESHOLD] = threshold.toPlainString()
                prefs[KEY_ALERT_SUPPRESSED] = false
            }
        }
    }

    suspend fun setAlertSuppressed(suppressed: Boolean) {
        context.settingsDataStore.edit { it[KEY_ALERT_SUPPRESSED] = suppressed }
    }

    suspend fun setLiquidGlassEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_LIQUID_GLASS] = enabled }
    }

    /** 只在用户点过「同意并继续」时调用，之后不再弹免责声明。 */
    suspend fun setDisclaimerAccepted() {
        context.settingsDataStore.edit { it[KEY_DISCLAIMER_ACCEPTED] = true }
    }

    /** 常驻通知开关。关掉时由调用方负责撤掉已发布的通知。 */
    suspend fun setStatusNotificationEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_STATUS_NOTIFICATION] = enabled }
    }

    /** 记住用户「忽略此版本」的选择，直到出现更高的 versionCode。 */
    suspend fun setIgnoredVersionCode(versionCode: Int) {
        context.settingsDataStore.edit { it[KEY_IGNORED_VERSION] = versionCode }
    }

    /** 用户勾选「不再提示重启」后调用。 */
    suspend fun setApiKeyRestartHintEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_API_KEY_RESTART_HINT] = enabled }
    }

    suspend fun setLastUpdateCheckAt(epochMillis: Long) {
        context.settingsDataStore.edit { it[KEY_LAST_UPDATE_CHECK] = epochMillis }
    }
}

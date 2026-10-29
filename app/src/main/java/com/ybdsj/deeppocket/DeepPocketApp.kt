package com.ybdsj.deeppocket

import android.app.Application
import android.content.Context
import com.ybdsj.deeppocket.data.local.DeepPocketDatabase
import com.ybdsj.deeppocket.data.remote.DeepSeekApi
import com.ybdsj.deeppocket.data.remote.NetworkFactory
import com.ybdsj.deeppocket.data.repository.ApiKeyRepository
import com.ybdsj.deeppocket.data.repository.BalanceRepository
import com.ybdsj.deeppocket.data.repository.BalanceSampleRepository
import com.ybdsj.deeppocket.data.security.KeystoreSecretCipher
import com.ybdsj.deeppocket.data.security.SecretCipher
import com.ybdsj.deeppocket.data.settings.SettingsRepository
import com.ybdsj.deeppocket.domain.billing.HolidayCalendar
import com.ybdsj.deeppocket.domain.billing.HolidayCalendarLoader
import com.ybdsj.deeppocket.notification.BalanceAlertNotifier
import com.ybdsj.deeppocket.notification.StatusNotifier
import com.ybdsj.deeppocket.work.BalanceCheckScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 手写的极简依赖容器。
 *
 * 本项目依赖图很小（数据库 → 两个仓库 → 若干 ViewModel），
 * 引入 Hilt/Koin 只会带来注解处理器与编译期耦合，因此在 Application 里手工装配。
 * 好处是：构造关系一眼可见、测试里可以直接 new 出对象、没有反射魔法。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: DeepPocketDatabase by lazy { DeepPocketDatabase.getInstance(appContext) }

    val secretCipher: SecretCipher by lazy { KeystoreSecretCipher() }

    val apiKeyRepository: ApiKeyRepository by lazy {
        ApiKeyRepository(dao = database.apiKeyDao(), cipher = secretCipher)
    }

    val api: DeepSeekApi by lazy { NetworkFactory.createApi() }

    val balanceRepository: BalanceRepository by lazy { BalanceRepository(api) }

    /** 余额采样：给「今日用量」提供唯一可信的数据来源（本机采样，非官方账单）。 */
    val balanceSampleRepository: BalanceSampleRepository by lazy {
        BalanceSampleRepository(dao = database.balanceSampleDao())
    }

    /** 用户偏好：刷新间隔、后台巡检开关、预警阈值。 */
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    /** 余额预警的本地通知发送器。 */
    val alertNotifier: BalanceAlertNotifier by lazy { BalanceAlertNotifier(appContext) }

    /** 常驻通知（余额 / 今日用量 / 峰谷时段摘要）。 */
    val statusNotifier: StatusNotifier by lazy { StatusNotifier(appContext) }

    /**
     * 内置的中国法定节假日表（assets/cn_holidays.json）。
     *
     * 用来把 DeepSeek 峰谷规则里的「排除中国法定节假日」算准，同时**不申请任何
     * 日历或网络权限**。读取失败时降级为空表，[com.ybdsj.deeppocket.domain.billing.PeakHours]
     * 会退回「只看星期几」的行为，并在 UI 上标注覆盖范围。
     */
    val holidayCalendar: HolidayCalendar by lazy {
        runCatching {
            appContext.assets.open(HOLIDAY_ASSET).bufferedReader().use {
                HolidayCalendarLoader.parse(it.readText())
            }
        }.getOrDefault(HolidayCalendar.EMPTY)
    }

    private companion object {
        const val HOLIDAY_ASSET = "cn_holidays.json"
    }
}

class DeepPocketApp : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // WorkManager 自己会持久化排程，这里只是兜底同步一次，
        // 保证「用户改过开关之后 App 被杀掉再重装回来」状态也一致。
        appScope.launch {
            runCatching {
                val settings = container.settingsRepository.settings.first()
                BalanceCheckScheduler.sync(this@DeepPocketApp, settings.backgroundCheckEnabled)
            }
        }
    }
}

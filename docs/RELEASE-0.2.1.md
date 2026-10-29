# DeepSeek Pocket 0.2.1 交付说明

- 版本：`versionCode 4` / `versionName 0.2.1`（2026-10-28）
- APK：`release/DeepSeekPocket-0.2.1-release.apk`（约 1.98 MB，正式签名）
- 构建：`gradle :app:testDebugUnitTest :app:assembleRelease`（R8 + 资源压缩 + lintVital 全过，单测 12 项通过）
- 实测设备：小米平板 `23046RP50C`（HyperOS，adb 序列号 `c9da6521`）

本版只改你反馈的两个问题，没有新增页面。

## 1. 常驻通知的余额显示成「—」

**原因**：打开常驻通知开关时，代码只发了一条空内容通知（`balance = null`），之后要等用户回首页刷新或 15 分钟后台巡检才会换成真数字。所以第一眼看到的总是破折号。

**改法**（`ui/settings/SettingsViewModel.kt`）：`setStatusNotificationEnabled(true)` 分支先 `fetchCurrentBalance()`（读当前 Key → 解密 → 请求余额接口），拿到真实余额后再交给 `StatusNoticePresenter.sync()`；拉取失败才退回占位符，并提示「还没拿到余额，回到首页刷新一次就会显示在通知里」。

**实测**：关掉开关再打开，下拉通知栏立刻显示 `余额 ¥45.06` / `今日用量 ¥0.56 · 高峰 · 距错峰 3 小时 11 分钟`，不再是「—」。

## 2. 桌面小组件只保留 4×1，并重排内容

**删除**：小号 2×1、大号 3×2 的布局、provider、widget info 与玻璃底图，共 8 个文件；Manifest 里的三个 receiver 合并成一个 `BalanceWidgetWideProvider`。

**新 4×1 排版**（`res/layout/widget_wide.xml`）：

```
DeepSeek Pocket                    梁文峰 · 高峰
总余额  ¥45.34          │        今日用量  ¥0.56
```

- 上排：左边应用名，右边时段徽标；徽标文案与 App 内首页同一套（高峰＝梁文峰，错峰＝梁文谷），抽到 `domain/billing/PeakHours.kt` 的 `PeakBadge` 里共用，避免两处各写一份后漂移。
- 下排：`总余额 │ 今日用量`，中间用 1dp 竖线分隔（竖线用 TextView 而不是 View —— RemoteViews 白名单里没有裸 View）。
- 背景仍是原来的磨砂玻璃 drawable（半透明渐变 + 高光描边 + 描边色），没有用液态玻璃。
- 点击：根容器、应用名、余额、用量、徽标五个节点都绑了同一个 PendingIntent，MIUI 只转发带文本子 View 的点击也能进 App。

**尺寸**：`res/xml/widget_info.xml` 里 `minWidth` 从 360dp 提到 **720dp**（该平板 5 列网格、单列约 180dp，360dp 只换来 2 列），`minHeight` 保持 40dp（实测 1 行）。

> ⚠️ **升级后要删掉旧的桌面组件再加一次**：provider 类名变了，桌面上原来那个（小/中/大都算）会变成空白占位，删掉它再按「长按桌面空白处 → 小部件 → DeepSeek Pocket」重加即可。设置页的「桌面已添加：N 个」也会跟着归零。

## 升级方式

`adb install -r` 或直接点 APK（同签名）。**不要卸载**，否则本机保存的 API Key 与余额采样记录一起丢。

## 0.2.0 的五项功能与已知限制

见 `docs/RELEASE-0.2.0.md`，那四条限制（MIUI 不弹一键添加确认框、MIUI 拦自动拉起、update.json 未上线、网盘链接占位）本版未变。

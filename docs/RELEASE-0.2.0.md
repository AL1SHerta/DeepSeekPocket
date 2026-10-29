# DeepSeek Pocket 0.2.0 交付说明

- 版本：`versionCode 3` / `versionName 0.2.0`（2026-10-28）
- APK：`release/DeepSeekPocket-0.2.0-release.apk`（约 2.03 MB，正式签名：`keystore/deeppocket-release.jks`）
- 构建：`gradle :app:assembleRelease`（R8 混淆 + 资源压缩 + lintVital 全部通过），`testDebugUnitTest` 通过
- 实测设备：小米平板 `23046RP50C`（HyperOS，Android 16 / SDK 37，adb 序列号 `c9da6521`）

## 本次新增的五项功能

| # | 功能 | 入口 | 实测结论 |
|---|------|------|----------|
| 1 | 首次录入 API Key 后弹「建议重启一次应用」+ 一键重启 | 管理 Key → 添加 Key（保存成功后） | 弹窗、勾选「以后不再提示」、按钮均正常；见 `m20-restart-hint.png` |
| 2 | 版本检测：列出更新内容，可「忽略此版本」或「前往升级」 | 设置 → 关于 → 检查更新（启动时也会自动检查，24h 节流） | 链路与失败兜底正常；见下方「已知限制 3」 |
| 3 | 余额卡片「去充值」直达开放平台充值页 | 首页余额卡片底部 | 打开 `https://platform.deepseek.com/top_up`，需真实 Key 才会渲染该卡片 |
| 4 | 桌面小组件（小 2×1 / 中 4×1 / 大 3×2，磨砂玻璃） | 设置 → 桌面小组件 → 三种尺寸按钮 | 三尺寸均可添加并渲染；点击一键添加在 MIUI 上被系统吞掉，已有 Toast 引导手动添加 |
| 5 | 常驻通知开关（仅下拉通知栏可见） | 设置 → 常驻通知 | 一键开关 + 权限引导正常；通知为 IMPORTANCE_LOW、无声音、锁屏隐藏，见 `m15-notification.png` |

截图目录：`docs/screenshots/`

- `m9-home-recharge.png` 首页（无 Key）
- `m10-settings-top.png` / `m11-settings-mid.png` / `m12-status-card.png` 设置页各段
- `m13-perm-request.png` 通知权限引导
- `m15-notification.png` 通知栏中的余额状态通知
- `m17-widget-card.png` 小组件卡片 + 已添加计数
- `m18-pin-toast.png` 一键添加的 Toast 引导
- `m19-widget-large.png` 大号小组件实际渲染（磨砂玻璃）
- `m20-restart-hint.png` 重启提示弹窗
- `m22-home-error.png` 无效 Key 时的错误态
- `m23-settings-release.png` / `m24-update-friendly.png` / `m25-download-toast.png` release 包下的关于卡片

## 已知限制（都是系统或占位导致，不是崩溃）

1. **一键添加小组件在 MIUI / HyperOS 上无效**：`requestPinAppWidget` 返回 true，系统也启动了 `com.miui.home/.launcher.AddItemActivity`，但界面立刻被销毁，用户看不到确认框。已改为无论成败都给 Toast 引导（长按桌面空白处 → 小组件 → DeepSeek Pocket）。
2. **「立即重启」在 MIUI 上只会关掉应用**：系统日志 `Background activity launch blocked!`（`MODE_BACKGROUND_ACTIVITY_START_DENIED`）。已按 Android 14+ 规范给 PendingIntent 加上 `MODE_BACKGROUND_ACTIVITY_START_ALLOWED`，MIUI 仍拦截。弹窗里已写明「少数手机（如小米）会拦下自动拉起，那时点一下桌面图标重新打开即可」。
3. **版本检测的数据源还没真正上线**：`UpdateConfig.CHECK_URL` 指向 `https://raw.githubusercontent.com/ybdsj/DeepSeekPocket/main/update.json`，仓库尚未创建，因此现在检查会拿到 404。界面已改成一句人话「暂时拿不到更新信息，请稍后重试，或直接去网盘下载最新版」，技术细节不展示给用户。
4. **百度网盘下载链接仍是占位**：`UpdateConfig.DOWNLOAD_FALLBACK_URL` 里的分享码是 `1xxxxxxxxxx`。为避免跳到 404，未配置时按钮只弹 Toast 提示（见 `m25-download-toast.png`）。

## 要让版本检测真正可用（二选一）

**做法 A（推荐，App 代码不用改）**

1. 建仓库 `ybdsj/DeepSeekPocket`（公开）；
2. 把 `docs/update.json` 上传到**仓库根目录**，文件名保持 `update.json`；
3. 把里面 `downloadUrl` 换成真实的百度网盘分享链接。

**做法 B**

仍把清单放在 `docs/` 下，只改 `UpdateConfig.CHECK_URL` 为：

```
https://raw.githubusercontent.com/ybdsj/DeepSeekPocket/main/docs/update.json
```

顺带把 `UpdateConfig.DOWNLOAD_FALLBACK_URL` 换成同一个网盘链接，`isFallbackConfigured` 会自动判定为已配置，「去网盘下载最新版」按钮就会真的跳转。

## 升级方式

`adb install -r` 覆盖安装，或直接点 APK 安装（同签名）。**不要卸载**，否则本机保存的 API Key 与采样记录会一起丢失。

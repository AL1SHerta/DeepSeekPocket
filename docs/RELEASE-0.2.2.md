# DeepSeek Pocket 0.2.2

发布日：2026-10-29 ｜ versionCode 5 ｜ applicationId `com.ybdsj.deeppocket`

本次只动"版本检测"这一条链路，余额查询、用量推算、峰谷判定、小组件、常驻通知的行为都没有变化。

## 一、这次修了什么

### 1. 版本检测从来没有成功过（真 bug）

`UpdateConfig.CHECK_URL` 指向的是 `ybdsj/DeepSeekPocket`——一个按包名猜出来的、并不存在的仓库，
所以每次检查都是 404，被折叠成"暂时拿不到更新信息"。真实仓库是 `AL1SHerta/DeepSeek-Pocket`。

现在指向：

```
https://raw.githubusercontent.com/AL1SHerta/DeepSeek-Pocket/main/update.json
```

### 2. 加了镜像源，国内网络也能查

GitHub raw 在国内经常连不上。`UpdateConfig.CHECK_URLS` 现在是三个源按序尝试：
GitHub raw → `cdn.jsdelivr.net` → `gcore.jsdelivr.net`。
jsDelivr 直接镜像 GitHub 仓库内容，**不需要额外上传**，主地址一更新它自己会跟。

一轮检查总预算 25 秒（`TOTAL_CHECK_BUDGET_MS`），失败原因只报第一个源（最有排查价值的那条）。

### 3. 兜底下载链接接上真实网盘

`DOWNLOAD_FALLBACK_URL` 从占位 `1xxxxxxxxxx` 换成真实分享：
<https://pan.baidu.com/s/5f31vyRh-ITtHRVpEdOciqw>
`isFallbackConfigured` 现在为 true，设置页的「去网盘下载最新版」和失败提示都不再是空跳转。

### 4. 仓库根目录补上 update.json

清单原来只躺在 `docs/` 下，而 App 读的是仓库根目录，于是"文件写了但读不到"。
根目录 `update.json` 就是**唯一**的线上清单，`docs/update.json` 作为模板备份保留。

## 二、测试

- `UpdateCheckerTest`（新增，5 例）：用 JDK 自带 HttpServer 起本地服务，真实走一遍
  主源 404 → 回落镜像成功、主源返回 HTML → 回落镜像成功、首个源直接 UpToDate、
  忽略版本标记、全失败时报第一个源的原因（含 404）。
- `UpdateConfigTest`（新增，5 例）：主地址与仓库一致性、源列表顺序与去重、镜像确为 jsDelivr、
  兜底链接非占位、总预算落在 10–30 秒。
- `UpdateManifestTest`（既有 12 例）：解析宽松度与版本判定语义不变。
- 真机：装 0.2.2 后设置页「关于」显示 `0.2.2（Build 5）`；用低 versionCode 构建指向本地清单校验了
  「发现新版本 → 忽略此版本」整条交互（见 `docs/screenshots/`）。

## 三、升级注意

- 从 0.2.1 升级请用覆盖安装（`adb install -r` 或点 APK 覆盖）：卸载会丢掉 API Key 与余额采样历史。
- **0.2.1 及更早版本无法自动收到这次更新**——它们里面的清单地址是错的。这次得手动装一次，
  从 0.2.2 起自动检查才真的生效。
- 首次安装后第一次自动检查最多等 24 小时节流；想立刻看效果去设置页点「检查更新」。

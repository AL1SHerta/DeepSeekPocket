# 版本检测与发版手册

## 一、它怎么工作

App 里没有任何"服务端"，版本信息全靠仓库根目录的一个静态文件：

```
https://raw.githubusercontent.com/AL1SHerta/DeepSeek-Pocket/main/update.json
```

检查顺序（见 `UpdateConfig.CHECK_URLS`）：

1. 上面的 GitHub raw 主地址；
2. `https://cdn.jsdelivr.net/gh/AL1SHerta/DeepSeek-Pocket@main/update.json`（jsDelivr 镜像）；
3. `https://gcore.jsdelivr.net/gh/AL1SHerta/DeepSeek-Pocket@main/update.json`（同样的镜像，换域名）。

任意一个源拿到清单就立刻判定，三个都失败才显示"暂时拿不到更新信息"。
一轮检查总时长封顶 25 秒。清单内容在 `UpdateManifest.update.json` 里解析，多写字段不会让老版本崩。

触发时机：

| 时机 | 行为 |
| --- | --- |
| 冷启动后（免责声明已同意） | 检查一次，24 小时内不重复检查（`CHECK_INTERVAL_MS`），失败也记时间避免断网反复请求 |
| 设置页「检查更新」按钮 | 跳过节流，立刻检查，结果只用一句人话提示 |
| 查到更高 versionCode | 弹「发现新版本」：更新说明 + 忽略此版本 + 前往升级 |
| 用户点「忽略此版本」 | 记下该 versionCode（`AppSettings.ignoredVersionCode`），直到远端更高才再次提醒 |

强制更新：清单里 `forceBelowVersionCode` 填一个数字 N，则 `versionCode < N` 的用户弹窗不可取消（只剩「稍后」和「前往升级」）。
一般不用，除非旧版本有数据损坏或密钥泄漏类的问题。

## 二、发新版本的四步

前提：`app/build.gradle.kts` 里的 `versionCode` 必须**单调递增**，`versionName` 随便好看。版本判定只看 versionCode。

1. **改版本号并打包**

   ```powershell
   # 先设 JAVA_HOME=D:\JDK17、GRADLE_USER_HOME=D:\GradleHome
   D:\Gradle-9.8.2\bin\gradle.bat :app:testDebugUnitTest :app:assembleRelease
   ```

   产物：`app/build/outputs/apk/release/app-release.apk`，复制成 `release/DeepSeekPocket-<ver>-release.apk`。

2. **把 APK 传到百度网盘**

   上传到分享文件夹（就是 `UpdateConfig.DOWNLOAD_FALLBACK_URL` 指向的那个分享）：
   <https://pan.baidu.com/s/5f31vyRh-ITtHRVpEdOciqw>

   先传 APK，再改第 3 步的清单——否则老版本用户点「前往升级」会下到上一版。

3. **改仓库根目录的 `update.json`**

   只改这几个字段，改完直接提交到 `main`：

   ```json
   {
     "versionCode": 6,
     "versionName": "0.2.3",
     "downloadUrl": "https://pan.baidu.com/s/5f31vyRh-ITtHRVpEdOciqw",
     "notes": ["这次改了什么，一条一行"],
     "publishedAt": "2026-11-01",
     "forceBelowVersionCode": null
   }
   ```

   - `notes` 会按行展示在弹窗里；
   - `downloadUrl` 留空或填错也能用：App 会回落到 `UpdateConfig.DOWNLOAD_FALLBACK_URL`；
   - 文件必须叫 `update.json`、必须在**仓库根目录**（不是 `docs/`）。

4. **（可选）让 jsDelivr 立刻刷新**

   jsDelivr 对 `@main` 的缓存有几分钟到十几分钟的延迟。介意的话手动刷一下：

   ```
   https://purge.jsdelivr.net/gh/AL1SHerta/DeepSeek-Pocket@main/update.json
   ```

   不刷也没关系：App 先访问 GitHub raw 主地址，镜像只是兜底。

## 三、排查

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 设置页一直"暂时拿不到更新信息" | 根目录没有 `update.json`，或仓库名/分支不是 `main` | 打开主地址确认能直接在浏览器里看到 JSON |
| 浏览器能看到 JSON，App 里还是失败 | 手机网络到 GitHub 不通，镜像也没通 | 那三个地址在手机浏览器里逐个试 |
| 提示了但下载页是旧的 | 网盘还没传新 APK，或清单先于 APK 更新 | 先传 APK 再改清单 |
| 改了清单 App 没反应 | 24 小时节流，或用户点过「忽略此版本」 | 设置页手动点「检查更新」；忽略过的需要远端 versionCode 更高才会再提醒 |
| 提示"清单格式不正确" | JSON 语法错误（多逗号、注释、中文引号） | 用 JSON 校验器过一遍 |

## 四、安全边界

- 更新清单是公开文件，**不要**往里写 API Key、内测口令等任何秘密；
- App 端不校验 APK 签名，所以网盘分享链接等同于发布权限——别把链接给不可信的人改；
- 检查更新不带任何鉴权头，也不读取本地 API Key（见 `UpdateChecker.defaultHttpClient`）。

# 逐个探测版本检测的更新源，确认线上清单真的能被 App 读到
#
# 用法：pwsh -NoProfile -File tools/check-update-url.ps1
#
# 发版后跑一遍，能提前发现「清单没上传 / JSON 语法错 / 镜像还没同步」这类
# 只在手机上才暴露的问题。

$ErrorActionPreference = 'Stop'

$Sources = @(
    'https://raw.githubusercontent.com/AL1SHerta/DeepSeek-Pocket/main/update.json',
    'https://cdn.jsdelivr.net/gh/AL1SHerta/DeepSeek-Pocket@main/update.json',
    'https://gcore.jsdelivr.net/gh/AL1SHerta/DeepSeek-Pocket@main/update.json'
)

$ok = 0
foreach ($url in $Sources) {
    try {
        $resp = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 20 -Headers @{ 'Cache-Control' = 'no-cache' }
        $manifest = $resp.Content | ConvertFrom-Json
        $ok++
        Write-Host "[OK]   $url"
        Write-Host "        versionCode=$($manifest.versionCode) versionName=$($manifest.versionName) publishedAt=$($manifest.publishedAt)"
        Write-Host "        downloadUrl=$($manifest.downloadUrl) notes=$(@($manifest.notes).Count) 条"
        if (-not $manifest.downloadUrl) {
            Write-Host "        [注意] downloadUrl 为空，App 会回落到内置网盘链接"
        }
    }
    catch {
        Write-Host "[FAIL] $url"
        Write-Host "        $($_.Exception.Message)"
    }
}

Write-Host ''
if ($ok -eq 0) {
    Write-Host '三个源都拿不到清单：确认仓库根目录有 update.json、默认分支是 main。' -ForegroundColor Red
    exit 1
}

Write-Host "$ok/$($Sources.Count) 个源可用。" -ForegroundColor Green

<#
.SYNOPSIS
    用 GitHub CLI 发布 ACS 新版本：打 tag、建 Release、上传按 ABI 分包的 APK、同步 updater.json。

.DESCRIPTION
    替代原先「本地 assembleRelease → 打开网页手工建 Release → 手工传两个 APK →
    手工改 updater.json → 手工 push」的手工流程。版本号、versionCode、tag 名、
    资产名、清单字段之间存在多处必须一致的约束，手工做任何一步漏掉都会造成
    线上更新失效（见下方「为什么这些约束必须机器保证」）。

    为什么走 GitHub Release 而不是别的：应用内更新（TomIDEUpdater）直接读
    `updater.json` 里的 `baseUrl` 拼出 Release 下载地址，所以 Release 是更新的
    唯一分发点，不能换。

    为什么这些约束必须机器保证：
      1. tag 必须是 `v<versionName>`（如 `v1.0.0+gh.r07`）。updater.json 的
         `baseUrl` 是 `.../releases/download/v{versionName}`，tag 名对不上就 404。
      2. 资产名必须是 `android-code-studio-<abi>-<versionName>.apk`。updater.json
         的 `apkUrl` 是 `{baseUrl}/android-code-studio-{abi}-{versionName}.apk`，
         文件名对不上就下载失败——而且失败发生在用户点「下载」之后，最晚才发现。
      3. `updater.json` 的 baseVersionCode 必须等于 APK 里的 versionCode，且
         versionCode = VersionCodeBase + revision（r06 → 1027）。不一致会让应用
         每次启动都误报「有更新」（ProjectConfig.kt:56 有专门注释警告过）。
      4. APK 签名证书必须是已发布版本用的那一把。用错证书，新包**无法覆盖安装**，
         用户只能卸载重装并丢掉 3.7G 数据。这条最贵，所以脚本把它做成硬校验。

.PARAMETER Revision
    修订号，整数。例如 7 会生成版本 `1.0.0+gh.r07`、versionCode 1028。

.PARAMETER MainVersion
    主版本号，默认 1.0.0。

.PARAMETER VersionCodeBase
    versionCode 基数，默认 1021。versionCode = 基数 + Revision（r06=1027 即 1021+6）。

.PARAMETER ExpectedCertSha1
    期望的 APK 签名证书 SHA-1。默认是已发布 r4/r5/r6 用的那把（本机
    acs-debug-signing.jks）。不匹配会中止发布——这是有意为之，见上第 4 条。

.PARAMETER ChangelogPath
    changelog 源文件，会被复制到 `whatsnew/v<versionName>.md`（updater.json 的
    changelog 字段指向该路径）。省略则不创建。

.EXAMPLE
    # 先看会做什么，不实际执行
    .\tools\publish-release.ps1 -Revision 7 -DryRun

    # 用已有产物发布（不重新构建）
    .\tools\publish-release.ps1 -Revision 7 -SkipBuild

    # 全流程：构建 → 打 tag → 发 Release → 同步 updater.json → push
    .\tools\publish-release.ps1 -Revision 7 -ChangelogPath whatsnew\v1.0.0+gh.r07.md

.NOTES
    前置：`gh auth status` 已登录且 token 有 repo 权限。
    构建需要环境变量 JAVA_HOME（见 CLAUDE.md）；签名走仓库 signing/signing-key.jks
    或 SIGNING_STORE_* 环境变量。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [int]$Revision,

    [string]$MainVersion = '1.0.0',
    [string]$Repo = '706412584/android-code-studio',
    [string]$Branch = 'dev',
    [int]$VersionCodeBase = 1021,

    # 已发布 r4/r5/r6 的证书指纹（本机 acs-debug-signing.jks）。
    # 换签名密钥时必须同步改这里，否则脚本会拦住你——这正是它的作用。
    [string]$ExpectedCertSha1 = '83ABB7381685A06DBDAE877DED4A207F4919C1DA',

    [string]$ChangelogPath,

    [switch]$SkipBuild,
    [switch]$SkipUpdaterJson,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# ---------- 推导版本标识 ----------
$revStr = '{0:D2}' -f $Revision
$versionName = "$MainVersion+gh.r$revStr"
$tag = "v$versionName"
$versionCode = $VersionCodeBase + $Revision

$repoRoot = Split-Path -Parent $PSScriptRoot
$apkDir = Join-Path $repoRoot 'core/app/build/outputs/apk/release'
$apkArm64 = Join-Path $apkDir "android-code-studio-arm64-v8a-$versionName.apk"
$apkArmv7 = Join-Path $apkDir "android-code-studio-armeabi-v7a-$versionName.apk"
$updaterJson = Join-Path $repoRoot 'updater.json'

function Info($m) { Write-Host "  $m" -ForegroundColor Cyan }
function Ok($m) { Write-Host "  OK  $m" -ForegroundColor Green }
function Warn($m) { Write-Host "  !!  $m" -ForegroundColor Yellow }
function Die($m) { Write-Host "  XX  $m" -ForegroundColor Red; exit 1 }

# PS 5.1 在 $ErrorActionPreference='Stop' 下，会把原生命令写到 stderr 的普通输出
# （例如 `gh release view` 的 "release not found"）升级成终止错误，导致脚本在
# 「预期内的失败」上直接崩掉。这里临时降级 EAP，只取退出码与合并输出。
function Invoke-Native([scriptblock]$cmd) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $cmd 2>&1 | Out-String
        return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $out }
    } finally { $ErrorActionPreference = $prev }
}

# 只读校验：DryRun 下也执行，否则 dry run 验证不了 tag 冲突/证书是否正确。
function Invoke-Check([string]$desc, [scriptblock]$body) {
    Write-Host "`n== $desc ==" -ForegroundColor White
    & $body
}

# 有副作用的步骤：DryRun 下跳过。
function Invoke-Step([string]$desc, [scriptblock]$body) {
    Write-Host "`n== $desc ==" -ForegroundColor White
    if ($DryRun) { Warn "DryRun: 跳过执行"; return }
    & $body
}

Write-Host "`nACS 发布: $versionName  (tag=$tag, versionCode=$versionCode, repo=$Repo)" -ForegroundColor Magenta

# ---------- 1. 前置检查 ----------
Invoke-Check '检查 gh 登录' {
    $r = Invoke-Native { gh auth status }
    if ($r.ExitCode -ne 0) { Die "gh 未登录。先运行 gh auth login。`n$($r.Output)" }
    if ($r.Output -notmatch [regex]::Escape($Repo.Split('/')[0])) { Warn "gh 当前账号可能与 $Repo 不一致，请自行确认" }
    Ok 'gh 已登录'
}

Invoke-Check '检查 tag 是否已存在' {
    $r = Invoke-Native { gh release view $tag -R $Repo }
    if ($r.ExitCode -eq 0) { Die "Release $tag 已存在。换个 -Revision，或先删除旧 Release。" }
    Ok "tag $tag 可用"
}

Invoke-Check '检查工作区是否干净' {
    $r = Invoke-Native { git -C $repoRoot status --porcelain }
    if ($r.Output.Trim()) { Warn "工作区有未提交改动，发布将基于当前 HEAD；建议先提交：`n$($r.Output)" }
    else { Ok '工作区干净' }
}

# ---------- 2. 构建 ----------
if (-not $SkipBuild) {
    Invoke-Step "构建 release APK (MAIN_VERSION=$MainVersion REVISION_NUM=$revStr versionCode=$versionCode)" {
        $env:MAIN_VERSION = $MainVersion
        $env:REVISION_NUM = $revStr
        $env:PROJECT_CONFIG_KT_BASE_VERSION_CODE = "$versionCode"
        if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:/Users/70641/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2' }

        # 仓库里缺这个目录，CI 与本地都补（见 CLAUDE.md）。
        $l8 = Join-Path $repoRoot 'core/app/build/intermediates/l8_art_profile/release/l8DexDesugarLibRelease'
        New-Item -ItemType Directory -Force -Path $l8 | Out-Null
        New-Item -ItemType File -Force -Path (Join-Path $l8 'baseline-prof.txt') | Out-Null

        Push-Location $repoRoot
        try {
            & ./gradlew :core:app:assembleRelease --offline -x lintVitalRelease
            if ($LASTEXITCODE -ne 0) { Die 'Gradle 构建失败' }
        } finally { Pop-Location }
        Ok '构建完成'
    }
}

# ---------- 3. 校验产物 ----------
Invoke-Check '校验 APK 存在且文件名含版本号' {
    if ($SkipBuild -and $DryRun) {
        Warn "DryRun+SkipBuild: 产物可能还是旧版本号，仅检查命名规则"
    }
    foreach ($p in @($apkArm64, $apkArmv7)) {
        if (-not (Test-Path $p)) {
            if ($DryRun) { Warn "（DryRun）产物尚不存在: $(Split-Path -Leaf $p) —— 正式发布会先构建" ; continue }
            Die "缺少产物: $p`n（用 -SkipBuild 时请确认已按当前版本号构建）"
        }
        $mb = [math]::Round((Get-Item $p).Length / 1MB, 1)
        Ok "$(Split-Path -Leaf $p)  ($mb MB)"
    }
}

Invoke-Check '校验签名证书（防止用户无法覆盖安装）' {
    $apksigner = Get-ChildItem -Path @("$env:ANDROID_HOME\build-tools", 'D:\android\build-tools') -Filter 'apksigner.bat' -Recurse -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $apksigner) { Warn '找不到 apksigner，跳过证书校验（不推荐）'; return }

    foreach ($p in @($apkArm64, $apkArmv7)) {
        if (-not (Test-Path $p)) { Warn "（跳过）产物不存在: $(Split-Path -Leaf $p)"; continue }
        $out = & $apksigner.FullName verify --print-certs $p 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0) { Die "apksigner 校验失败: $p`n$out" }
        $m = [regex]::Match($out, 'SHA-1 digest:\s*([0-9a-fA-F]{40})')
        if (-not $m.Success) { Die "无法从 apksigner 输出解析证书指纹: $p" }
        $sha1 = $m.Groups[1].Value.ToUpperInvariant()
        if ($sha1 -ne $ExpectedCertSha1.ToUpperInvariant()) {
            Die @"
签名证书不匹配！
  期望: $ExpectedCertSha1
  实际: $sha1
  文件: $(Split-Path -Leaf $p)

用错证书发布的 APK 无法覆盖安装到现有用户，用户只能卸载重装并丢掉 3.7G 数据。
确认这是有意更换签名密钥后，再用 -ExpectedCertSha1 显式传入新指纹。
"@
        }
        Ok "$(Split-Path -Leaf $p)  证书 $sha1"
    }
}

# ---------- 4. 创建 Release 并上传 ----------
Invoke-Step "创建 Release $tag 并上传 APK" {
    $notesArgs = @()
    if ($ChangelogPath) {
        if (-not (Test-Path $ChangelogPath)) { Die "changelog 不存在: $ChangelogPath" }
        $notesArgs = @('--notes-file', $ChangelogPath)
    } else {
        $notesArgs = @('--notes', "Release $versionName")
    }
    gh release create $tag -R $Repo --target $Branch --title $tag @notesArgs $apkArm64 $apkArmv7
    if ($LASTEXITCODE -ne 0) { Die 'gh release create 失败' }
    Ok "Release 已创建: https://github.com/$Repo/releases/tag/$tag"
}

# ---------- 5. 同步 updater.json ----------
if (-not $SkipUpdaterJson) {
    Invoke-Step '同步 updater.json 并 push' {
        $json = Get-Content $updaterJson -Raw -Encoding UTF8 | ConvertFrom-Json
        $json.baseVersionCode = $versionCode
        $json.baseVersionName = $versionName
        foreach ($abi in @('armeabi-v7a', 'arm64-v8a')) {
            $json.variants.$abi.versionCode = $versionCode
            $json.variants.$abi.versionName = $versionName
        }
        $json.changelog = "https://raw.githubusercontent.com/$Repo/refs/heads/$Branch/whatsnew/$versionName.md"
        # -Depth 保证 variants 不被截断；缩进与仓库现有格式一致（2 空格）。
        $json | ConvertTo-Json -Depth 10 | Set-Content $updaterJson -Encoding UTF8

        if ($ChangelogPath) {
            $dest = Join-Path $repoRoot "whatsnew/$versionName.md"
            Copy-Item $ChangelogPath $dest -Force
            Ok "changelog -> whatsnew/$versionName.md"
        }

        git -C $repoRoot add updater.json
        if ($ChangelogPath) { git -C $repoRoot add "whatsnew/$versionName.md" }
        git -C $repoRoot commit -m "chore(release): $versionName"
        git -C $repoRoot push origin $Branch
        if ($LASTEXITCODE -ne 0) { Die 'push 失败' }
        Ok "updater.json 已同步并 push 到 $Branch"
    }
} else {
    Warn '已跳过 updater.json 同步 —— 应用不会提示本次更新，记得手动补'
}

Write-Host "`n完成: $versionName`n" -ForegroundColor Green

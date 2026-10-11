<#
.SYNOPSIS
    把 Dart SDK（Android/arm64）压缩包上传到 Release，供 ACS 应用内下载。

.DESCRIPTION
    为什么是 67MB：完整 Dart SDK 解压后约 169MB，压缩后 67MB。ACS 的 Dart LSP
    只需要 bin/dart + bin/snapshots/analysis_server.dart.snapshot + lib/，
    这些已包含在官方 dart-sdk 包内，直接分发原始包即可。

    包来源：mumumusuc/termux-flutter release 3.29.2 抽取的 bionic ELF 构建
    （NDK r27c），`dart --version` 实测输出 `Dart SDK version: 3.7.2 (stable)
    on "linux_arm64"`，可直接在 Android 上运行。

    **通道选择（2026-10-12 实测）**：默认 github。
    - Gitee 仓库附件配额 1GB 已被 apt-mirror 备份分片占满，上传返回
      「文件大小已超出仓库附件配额」——通道暂不可用。
    - GitHub 侧设备实测：主站 github.com 超时，但 Release 附件下载 302 到
      objects.githubusercontent.com（可达），设备上 3.7MB/s / 18 秒下完
      67MB，sha256 一致。用 `-Channel github`。

.PARAMETER File
    要上传的包路径。默认取 D:\android\acs-mirror-backup\dart-sdk\ 下的包。

.PARAMETER Channel
    github（默认）或 gitee。

.PARAMETER Repo
    gitee 通道的目标仓库，形如 owner/repo。

.PARAMETER Tag
    Release 的 tag 名。不存在会自动创建。

.PARAMETER DryRun
    只打印将要做什么，不实际上传。

.EXAMPLE
    .\tools\publish-dart-sdk-package.ps1 -DryRun
    .\tools\publish-dart-sdk-package.ps1 -Channel github -Tag dart-sdk-v3.7.2
    .\tools\publish-dart-sdk-package.ps1 -Channel gitee -Repo wu-yongchengsvip_admin/android-code-studio

.NOTES
    github 通道用 gh CLI（需已 `gh auth login`）。
    gitee 通道令牌：优先环境变量 GITEE_KEY，否则读 %USERPROFILE%\.gitee_token。
    令牌不会写入任何文件，也不会打印出来。
#>
[CmdletBinding()]
param(
    [string]$File = 'D:\android\acs-mirror-backup\dart-sdk\dart-sdk-3.7.2-aarch64.tar.gz',

    [ValidateSet('github', 'gitee')]
    [string]$Channel = 'github',

    [string]$Repo = 'wu-yongchengsvip_admin/android-code-studio',

    [string]$Tag = 'dart-sdk-v3.7.2',

    # gitee 建 Release 时必须指定 target_commitish。不传会报 "target_commitish is missing"。
    [string]$Branch = 'dev',

    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# ---------- 前置检查 ----------
if (-not (Test-Path $File)) {
    throw "找不到要上传的文件：$File"
}
$item = Get-Item $File
$sizeMb = [math]::Round($item.Length / 1MB, 2)
$sha256 = (Get-FileHash $File -Algorithm SHA256).Hash.ToLower()

Write-Host "文件      : $($item.Name)"
Write-Host "大小      : $sizeMb MB"
Write-Host "SHA-256   : $sha256"
Write-Host "通道      : $Channel"
Write-Host "Tag       : $Tag"
Write-Host ''

if ($Channel -eq 'github') {
    # GitHub Release 单文件上限 2GB，本包余量充足；仍做一次合理上限检查
    if ($item.Length -gt 2000MB) {
        throw "文件超过 GitHub Release 的 2GB 单文件上限（当前 $sizeMb MB）。"
    }

    if ($DryRun) {
        Write-Host '[DryRun] 不实际上传。'
        Write-Host ''
        Write-Host '上传后请把 DartSdkInstaller.PACKAGE_URL 设为：'
        Write-Host "  https://github.com/706412584/android-code-studio/releases/download/$Tag/$($item.Name)"
        return
    }

    # 复用已有 release；不存在则创建
    $existing = gh release view $Tag --repo 706412584/android-code-studio --json tagName 2>$null
    if (-not $existing) {
        Write-Host '创建 Release…'
        gh release create $Tag --repo 706412584/android-code-studio `
            --title "Dart SDK 3.7.2 (Android/arm64)" `
            --notes "Dart SDK 3.7.2（bionic ELF，NDK r27c 构建），供 AndroidCodeStudio 应用内下载，用于 Flutter 模板的 Dart 语言服务器。

来源：mumumusuc/termux-flutter release 3.29.2 抽取。
sha256: $sha256"
        if ($LASTEXITCODE -ne 0) { throw "gh release create 失败（退出码 $LASTEXITCODE）" }
    }

    Write-Host "上传中（$sizeMb MB）…"
    gh release upload $Tag $File --repo 706412584/android-code-studio --clobber
    if ($LASTEXITCODE -ne 0) { throw "gh release upload 失败（退出码 $LASTEXITCODE）" }

    Write-Host ''
    Write-Host '上传完成。请核对下载地址可用：'
    Write-Host "  https://github.com/706412584/android-code-studio/releases/download/$Tag/$($item.Name)"
    Write-Host "并确认 sha256 与上面一致：$sha256"
    return
}

# ---------- gitee 通道 ----------
# Gitee 的硬限制。超了要在上传前拦住——传一半才失败会留下一个坏附件。
if ($item.Length -gt 100MB) {
    throw "文件超过 Gitee 的 100MB 单文件上限（当前 $sizeMb MB）。"
}

if ($DryRun) {
    Write-Host '[DryRun] 不实际上传。'
    Write-Host ''
    Write-Host '上传后请把 DartSdkInstaller.PACKAGE_URL 设为：'
    Write-Host "  https://gitee.com/$Repo/releases/download/$Tag/$($item.Name)"
    return
}

# ---------- 令牌 ----------
function Get-GiteeToken {
    if ($env:GITEE_KEY) { return $env:GITEE_KEY.Trim() }
    $tokenPath = Join-Path $env:USERPROFILE '.gitee_token'
    if (Test-Path $tokenPath) {
        return (Get-Content $tokenPath -Raw).Trim()
    }
    $secure = Read-Host -Prompt 'Gitee 访问令牌' -AsSecureString
    return [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)).Trim()
}

$token = Get-GiteeToken
$api = "https://gitee.com/api/v5/repos/$Repo"
$headers = @{ Authorization = "token $token" }

# 附件端点用的是 release id，不是 tag。实测用 tag 会 404。
Write-Host '检查 Release…'
$releaseId = $null
try {
    $existing = Invoke-RestMethod -Uri "$api/releases/tags/$Tag" -Headers $headers -Method Get
    $releaseId = $existing.id
    Write-Host "  已存在（id=$releaseId）"
}
catch {
    Write-Host '  不存在，创建…'
    $body = @{
        tag_name         = $Tag
        name             = $Tag
        body             = 'Dart SDK 3.7.2（Android/aarch64，bionic ELF）供 AndroidCodeStudio 应用内下载，用于 Flutter 模板的 Dart 语言服务器'
        target_commitish = $Branch
    } | ConvertTo-Json
    $created = Invoke-RestMethod -Uri "$api/releases" -Headers $headers -Method Post `
        -ContentType 'application/json' -Body $body
    $releaseId = $created.id
    Write-Host "  已创建（id=$releaseId）"
}

if (-not $releaseId) { throw '未能取得 release id。' }

# 同名附件先删：Gitee 不会覆盖，会变成两个同名附件，而下载按名字取，拿到哪个不确定。
Write-Host '检查同名附件…'
try {
    $assets = Invoke-RestMethod -Uri "$api/releases/$releaseId/attach_files" -Headers $headers -Method Get
    foreach ($asset in $assets) {
        if ($asset.name -eq $item.Name) {
            Write-Host "  删除旧的 $($asset.name)（id=$($asset.id)）"
            Invoke-RestMethod -Uri "$api/releases/$releaseId/attach_files/$($asset.id)" `
                -Headers $headers -Method Delete | Out-Null
        }
    }
}
catch {
    Write-Host "  跳过（$($_.Exception.Message)）"
}

Write-Host "上传中（$sizeMb MB）…"
# curl 而不是 Invoke-RestMethod：后者会把整个文件读进内存再编码 multipart，
# 大文件容易失败，且进度不可见。
$curl = Get-Command curl.exe -ErrorAction SilentlyContinue
if (-not $curl) { throw '需要 curl.exe（Windows 10+ 自带）。' }

# 令牌走表单字段而不是 Authorization 头：Gitee 的附件端点按表单里的
# access_token 鉴权，用头会 401/404。
& curl.exe -sS --http1.1 -X POST "$api/releases/$releaseId/attach_files" `
    -F "access_token=$token" `
    -F "file=@$File" `
    -o "$env:TEMP\gitee-dart-upload.json" -w "HTTP %{http_code}`n"

if ($LASTEXITCODE -ne 0) { throw "curl 退出码 $LASTEXITCODE" }

$result = Get-Content "$env:TEMP\gitee-dart-upload.json" -Raw
Write-Host ''
Write-Host '响应：'
Write-Host $result
Write-Host ''
Write-Host '上传完成。请核对下载地址可用：'
Write-Host "  https://gitee.com/$Repo/releases/download/$Tag/$($item.Name)"
Write-Host "并确认 sha256 与上面一致：$sha256"

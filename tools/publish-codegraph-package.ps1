<#
.SYNOPSIS
    把 CodeGraph 精简包上传到 Gitee Release，供 ACS 应用内下载。

.DESCRIPTION
    为什么走 Gitee 而不是 GitHub Releases：应用内下载发生在国内设备上，而设备常
    挂着代理（实测 Termux 直连会超时），GitHub 的跨境链路不可靠。

    **单文件上限 100MB**（Gitee 实测报错原文「文件大小已超过限制：100 MB」）。
    本包 12.8MB，远低于上限——这也正是当初做精简包的原因之一。

    与 orangeplayer 的 sync-to-gitee.ps1 同一套 API 与令牌，但用途不同：
    那边同步 APK，这边只传一个组件包。

.PARAMETER File
    要上传的包路径。默认取 D:\android\keys\codegraph-dist\ 下的精简包。

.PARAMETER Repo
    Gitee 仓库，形如 owner/repo。

.PARAMETER Tag
    Release 的 tag 名。不存在会自动创建。

.PARAMETER DryRun
    只打印将要做什么，不实际上传。

.EXAMPLE
    .\tools\publish-codegraph-package.ps1 -DryRun
    .\tools\publish-codegraph-package.ps1 -Repo wu-yongchengsvip/android-code-studio -Tag codegraph-v1.6.0

.NOTES
    令牌：优先环境变量 GITEE_KEY，否则读 %USERPROFILE%\.gitee_token。
    令牌不会写入任何文件，也不会打印出来。
#>
[CmdletBinding()]
param(
    [string]$File = 'D:\android\keys\codegraph-dist\codegraph-android-slim-1.6.0.tgz',

    [Parameter(Mandatory = $true)]
    [string]$Repo,

    [string]$Tag = 'codegraph-v1.6.0',

    # Gitee 建 Release 时必须指定 target_commitish（从哪个分支/commit 打 tag）。
    # 不传会报 "target_commitish is missing"。
    [string]$Branch = 'dev',

    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

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
Write-Host "目标仓库  : $Repo"
Write-Host "Tag       : $Tag"
Write-Host ''

# Gitee 的硬限制。超了要在上传前拦住——传一半才失败会留下一个坏附件。
if ($item.Length -gt 100MB) {
    throw "文件超过 Gitee 的 100MB 单文件上限（当前 $sizeMb MB）。"
}

if ($DryRun) {
    Write-Host '[DryRun] 不实际上传。'
    Write-Host ''
    Write-Host '上传后请把 app 里的 DEFAULT_PACKAGE_URL 设为：'
    Write-Host "  https://gitee.com/$Repo/releases/download/$Tag/$($item.Name)"
    return
}

$token = Get-GiteeToken
$api = "https://gitee.com/api/v5/repos/$Repo"
$headers = @{ Authorization = "token $token" }

# ---------- 确保 Release 存在，并拿到 release id ----------
# 附件端点用的是 release id，不是 tag。实测用 tag 会 404（返回一个 HTML 错误页）。
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
        body             = 'CodeGraph 精简包（Android/aarch64，供 AndroidCodeStudio 应用内下载）'
        target_commitish = $Branch
    } | ConvertTo-Json
    $created = Invoke-RestMethod -Uri "$api/releases" -Headers $headers -Method Post `
        -ContentType 'application/json' -Body $body
    $releaseId = $created.id
    Write-Host "  已创建（id=$releaseId）"
}

if (-not $releaseId) { throw '未能取得 release id。' }

# ---------- 上传附件 ----------
# 同名附件先删：Gitee 不会覆盖，会变成两个同名附件，而下载按名字取，
# 拿到哪个不确定。
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
    -o "$env:TEMP\gitee-upload.json" -w "HTTP %{http_code}`n"

if ($LASTEXITCODE -ne 0) { throw "curl 退出码 $LASTEXITCODE" }

$result = Get-Content "$env:TEMP\gitee-upload.json" -Raw
Write-Host ''
Write-Host '响应：'
Write-Host $result
Write-Host ''
Write-Host '上传完成。请核对下载地址可用：'
Write-Host "  https://gitee.com/$Repo/releases/download/$Tag/$($item.Name)"
Write-Host "并确认 sha256 与上面一致：$sha256"

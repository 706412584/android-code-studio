<#
.SYNOPSIS
    把语言服务器包上传到 Gitee Release，并发布国内镜像清单 servers-manifest.json。

.DESCRIPTION
    为什么走 Gitee：Kotlin 语言服务器的清单在 GitHub raw、包在 GitHub Releases，
    国内设备常直连超时，安装因此失败。App 端（core/common 的 LspServerSource）
    现在 **Gitee 优先、GitHub 兜底**，本脚本负责维护 Gitee 侧：

      servers-manifest.json（<Repo> 仓库根，raw 可匿名访问）
        └── Servers[].link    → 同仓库 Release 资产
        └── Servers[].sha256  → 本脚本计算（上游清单没有该字段）

    清单在 Gitee raw 上，**更新清单不必发版**；资产按版本放不同 Release。

    Gitee 硬限制：单文件 100MB（附件总配额 1GB）。KLS 包约 66MB，单文件可容纳。
    若某语言服务器超过 100MB，需先分卷（当前只有 Kotlin，暂不涉及）。

.PARAMETER PackagePath
    要上传的包路径（如 server.zip）。

.PARAMETER Version
    语言服务器版本（如 1.3.15）。写入清单与默认 tag。

.PARAMETER Repo
    Gitee 仓库，默认 wu-yongchengsvip_admin/acs-lsp-mirror。
    不存在会自动创建并强制公开（私有仓库的 Release 附件匿名访问会 403）。

.PARAMETER Tag
    Release tag，默认 lsp-kotlin-v<Version>。

.PARAMETER ServerId
    清单里的服务器 id，默认 Kotlin。

.PARAMETER UpstreamManifestUrl
    上游清单地址。脚本会拉取它并仅替换本服务器的条目，其余条目原样保留。

.PARAMETER DryRun
    只打印将要做什么，不上传、不推送。

.EXAMPLE
    .\tools\publish-lsp-mirror.ps1 -PackagePath D:\tmp\server.zip -Version 1.3.15 -DryRun
    .\tools\publish-lsp-mirror.ps1 -PackagePath D:\tmp\server.zip -Version 1.3.15

.NOTES
    令牌：优先环境变量 GITEE_KEY，否则读 %USERPROFILE%\.gitee_token。
    令牌不会写入任何文件，也不会打印出来。
#>
[CmdletBinding()]
param(
    [string]$PackagePath,

    [string]$Version,

    [string]$Repo = 'wu-yongchengsvip_admin/acs-lsp-mirror',

    [string]$Tag = '',

    [string]$ServerId = 'Kotlin',

    [string]$Branch = 'master',

    [string]$UpstreamManifestUrl = 'https://raw.githubusercontent.com/AndroidCSOfficial/acs-language-servers/refs/heads/main/servers-manifest.json',

    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($PackagePath)) { throw '缺少 -PackagePath（语言服务器 zip）。' }
if ([string]::IsNullOrWhiteSpace($Version)) { throw '缺少 -Version（如 1.3.15）。' }
if ([string]::IsNullOrWhiteSpace($Tag)) { $Tag = "lsp-$($ServerId.ToLower())-v$Version" }

if (-not (Test-Path $PackagePath)) { throw "找不到要上传的文件：$PackagePath" }
$item = Get-Item $PackagePath
$sizeMb = [math]::Round($item.Length / 1MB, 2)
$sha256 = (Get-FileHash $PackagePath -Algorithm SHA256).Hash.ToLower()
$assetName = $item.Name
$downloadUrl = "https://gitee.com/$Repo/releases/download/$Tag/$assetName"
$manifestUrl = "https://gitee.com/$Repo/raw/$Branch/servers-manifest.json"

Write-Host "文件      : $($item.Name)"
Write-Host "大小      : $sizeMb MB"
Write-Host "SHA-256   : $sha256"
Write-Host "版本      : $Version（$ServerId）"
Write-Host "目标仓库  : $Repo"
Write-Host "Tag       : $Tag"
Write-Host "下载地址  : $downloadUrl"
Write-Host "清单地址  : $manifestUrl"
Write-Host ''

if ($item.Length -gt 100MB) {
    throw "文件超过 Gitee 的 100MB 单文件上限（当前 $sizeMb MB）。需要先分卷。"
}

# ---------- 构建清单（先算出来，DryRun 也能打印） ----------
function Build-Manifest([string]$link, [string]$checksum) {
    $entry = [ordered]@{ id = $ServerId; version = $Version; link = $link; sha256 = $checksum }
    $servers = @()
    try {
        $upstream = Invoke-RestMethod -Uri $UpstreamManifestUrl -Method Get -TimeoutSec 20
        if ($upstream.PSObject.Properties.Name -contains 'Servers') {
            foreach ($s in $upstream.Servers) {
                if ([string]$s.id -eq $ServerId) { $servers += $entry }
                else {
                    $servers += [ordered]@{
                        id      = [string]$s.id
                        version = [string]$s.version
                        link    = [string]$s.link
                    }
                }
            }
        }
    }
    catch {
        Write-Warning "拉取上游清单失败（$($_.Exception.Message)），清单将只含 $ServerId。"
    }
    if (-not ($servers | Where-Object { $_.id -eq $ServerId })) { $servers += $entry }
    return [ordered]@{ Servers = [object[]]$servers } | ConvertTo-Json -Depth 6
}

$manifestJson = Build-Manifest $downloadUrl $sha256
Write-Host '清单预览：'
Write-Host $manifestJson
Write-Host ''

if ($DryRun) {
    Write-Host '[DryRun] 不实际上传、不推送清单。'
    return
}

# ---------- 令牌 ----------
function Get-GiteeToken {
    if ($env:GITEE_KEY) { return $env:GITEE_KEY.Trim() }
    $tokenPath = Join-Path $env:USERPROFILE '.gitee_token'
    if (Test-Path $tokenPath) { return (Get-Content $tokenPath -Raw).Trim() }
    $secure = Read-Host -Prompt 'Gitee 访问令牌' -AsSecureString
    return [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)).Trim()
}

$token = Get-GiteeToken
$headers = @{ Authorization = "token $token" }
$api = "https://gitee.com/api/v5/repos/$Repo"

# ---------- 确保仓库存在且公开 ----------
# Gitee 建仓时 private=false 会被忽略（建出来仍是私有），必须再 PATCH 一次；
# 而 PATCH 又要求带 name 字段。这组行为在 publish-cn-apk.ps1 里已踩过。
function Ensure-GiteeRepo {
    $repoName = $Repo.Split('/', 2)[1]
    $exists = $true
    try { Invoke-RestMethod -Uri $api -Headers $headers -Method Get | Out-Null }
    catch { $exists = $false }

    if (-not $exists) {
        Write-Host "创建仓库 $Repo（auto_init）…"
        $body = @{
            name        = $repoName
            description = 'Android Code Studio 语言服务器镜像（Gitee 源）'
            private     = $false
            auto_init   = $true
        } | ConvertTo-Json
        Invoke-RestMethod -Uri 'https://gitee.com/api/v5/user/repos' -Headers $headers -Method Post `
            -ContentType 'application/json' -Body $body | Out-Null
        Start-Sleep -Seconds 2
    }

    $patchBody = @{ name = $repoName; private = $false } | ConvertTo-Json
    $patched = Invoke-RestMethod -Uri $api -Headers $headers -Method Patch `
        -ContentType 'application/json' -Body $patchBody
    if ($patched.private) { throw "仓库 $Repo 仍为私有，匿名下载会 403。" }
    Write-Host "仓库 $Repo 已就绪（public）"
}

Write-Host '检查仓库…'
Ensure-GiteeRepo

# ---------- 确保 Release 存在 ----------
Write-Host "检查 Release $Tag…"
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
        body             = "语言服务器 $ServerId $Version（供 Android Code Studio 应用内下载）"
        target_commitish = $Branch
    } | ConvertTo-Json
    $created = Invoke-RestMethod -Uri "$api/releases" -Headers $headers -Method Post `
        -ContentType 'application/json' -Body $body
    $releaseId = $created.id
    Write-Host "  已创建（id=$releaseId）"
}
if (-not $releaseId) { throw '未能取得 release id。' }

# ---------- 上传附件（同名先删） ----------
try {
    $assets = Invoke-RestMethod -Uri "$api/releases/$releaseId/attach_files" -Headers $headers -Method Get
    foreach ($asset in $assets) {
        if ($asset.name -eq $assetName) {
            Write-Host "  删除旧附件 $assetName（id=$($asset.id)）"
            Invoke-RestMethod -Uri "$api/releases/$releaseId/attach_files/$($asset.id)" `
                -Headers $headers -Method Delete | Out-Null
        }
    }
}
catch { }

$curl = Get-Command curl.exe -ErrorAction SilentlyContinue
if (-not $curl) { throw '需要 curl.exe（Windows 10+ 自带）。' }

Write-Host "上传中（$sizeMb MB）…"
& curl.exe -sS --http1.1 -X POST "$api/releases/$releaseId/attach_files" `
    -F "access_token=$token" `
    -F "file=@$PackagePath" `
    -o "$env:TEMP\gitee-lsp-upload.json" -w "HTTP %{http_code}`n"

if ($LASTEXITCODE -ne 0) { throw "curl 退出码 $LASTEXITCODE" }
$result = Get-Content "$env:TEMP\gitee-lsp-upload.json" -Raw
if ($result -notmatch '"id"') { throw "上传响应异常：$result" }
Write-Host '上传完成。'

# ---------- 推送清单（contents API：新建 POST，更新 PUT+sha） ----------
function Push-GiteeFile([string]$path, [string]$content, [string]$message) {
    $fileApi = "$api/contents/$path"
    $existingSha = $null
    try {
        $meta = Invoke-RestMethod -Uri "$fileApi`?access_token=$token" -Method Get
        $existingSha = $meta.sha
    }
    catch { }

    $body = @{
        access_token = $token
        content      = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($content))
        message      = $message
        branch       = $Branch
    }
    if ($existingSha) {
        $body['sha'] = $existingSha
        Invoke-RestMethod -Uri $fileApi -Method Put -ContentType 'application/json; charset=utf-8' `
            -Body ($body | ConvertTo-Json) | Out-Null
        Write-Host "  更新 $path"
    }
    else {
        Invoke-RestMethod -Uri $fileApi -Method Post -ContentType 'application/json; charset=utf-8' `
            -Body ($body | ConvertTo-Json) | Out-Null
        Write-Host "  新建 $path"
    }
}

Write-Host '推送清单…'
Push-GiteeFile 'servers-manifest.json' $manifestJson "chore(lsp): $ServerId $Version"

Write-Host ''
Write-Host '==== 完成 ===='
Write-Host "下载地址: $downloadUrl"
Write-Host "清单地址: $manifestUrl"
Write-Host "SHA-256 : $sha256"
Write-Host '请用浏览器/curl 匿名访问清单确认可读。'
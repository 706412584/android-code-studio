<#
.SYNOPSIS
    发布 CN 版（Gitee 源）更新资产：APK 分卷 + 热更新补丁 + updater-cn.json 清单。

.DESCRIPTION
    CN 版的更新链路（TomIDEUpdater，-PcnMirror=true 构建）读 Gitee 的
    `updater-cn.json`，其字段指向本脚本上传的资产：

      updater-cn.json（acs-mirror 仓库根，raw 可匿名访问）
        ├── variants[*].parts → acs-cn-releases release 的 APK 分卷（每卷 95MB）
        └── patch.patchUrl    → 同 release 的 CN 热更新补丁（~74MB，单文件）

    为什么分卷：Gitee Release 附件单文件上限 100MB（实测报错原文
    「文件大小已超出限制：100 MB」），而 release 版 APK 约 141MB。

    为什么补丁单独生成：CN 包与常规包的 dex 不同（CN_MIRROR 常量内联差异），
    常规补丁打到 CN 包上会静默注入错误语义的 dex——两版补丁必须各自生成。
    CI（asm_build.yml 的 "Generate CN hot-update patch" 步骤）已产出
    `acs-hotupdate-cn-<ver>.zip` 并作为 GitHub Release 资产；本脚本默认从
    该 Release 拉取（也可 -PatchFile 指定本地文件）。

.PARAMETER ApkDir
    CN APK 所在目录。默认 apk-staging（CI staging 目录），不存在则退到
    core/app/build/outputs/apk/release。

.PARAMETER PatchFile
    CN 热更新补丁路径。默认从 GitHub Release 下载 acs-hotupdate-cn-<ver>.zip。

.PARAMETER ChangelogFile
    更新说明 markdown。默认 whatsnew/<version>.md；不存在则用占位文案。

.PARAMETER Repo
    Gitee 仓库（存 APK 分卷与补丁），默认 wu-yongchengsvip_admin/acs-cn-releases。
    不存在会自动创建（public）。

.PARAMETER ManifestRepo
    存放 updater-cn.json 与 changelog 的仓库，默认 wu-yongchengsvip_admin/acs-mirror
    （该仓库 raw 已用于镜像 manifest，国内可匿名访问）。

.PARAMETER DryRun
    只打印将要做什么，不实际上传/推送。

.EXAMPLE
    .\tools\publish-cn-apk.ps1 -DryRun
    .\tools\publish-cn-apk.ps1 -ApkDir apk-staging -PatchFile patch-work-cn/acs-hotupdate-cn-1.0.0+gh.r09.zip

.NOTES
    令牌：优先环境变量 GITEE_KEY，否则读 %USERPROFILE%\.gitee_token。
    令牌不会写入任何文件，也不会打印出来。
#>
[CmdletBinding()]
param(
    [string]$ApkDir = 'apk-staging',

    [string]$PatchFile = '',

    [string]$ChangelogFile = '',

    [string]$Repo = 'wu-yongchengsvip_admin/acs-cn-releases',

    [string]$ManifestRepo = 'wu-yongchengsvip_admin/acs-mirror',

    [string]$Branch = 'master',

    [int]$PartSizeMb = 95,

    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot

# ---------- 定位 CN APK ----------
if (-not (Test-Path $ApkDir)) {
    $ApkDir = Join-Path $repoRoot 'core/app/build/outputs/apk/release'
}
$cnApks = Get-ChildItem -Path $ApkDir -Filter '*-cn-*.apk' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'debug' } | Sort-Object Name
if (-not $cnApks) {
    throw "在 $ApkDir 找不到 CN release APK（*-cn-*.apk）。请先构建：./gradlew assembleRelease -PcnMirror=true"
}
$arm64Apk = $cnApks | Where-Object { $_.Name -match 'arm64-v8a' } | Select-Object -First 1
if (-not $arm64Apk) { throw "缺少 arm64-v8a CN APK（当前只有：$($cnApks.Name -join ', ')）" }
$armv7Apk = $cnApks | Where-Object { $_.Name -match 'armeabi-v7a' } | Select-Object -First 1

# 从文件名推导版本：…-cn-1.0.0+gh.r09.apk → 1.0.0+gh.r09
$m = [regex]::Match($arm64Apk.Name, '-cn-(?:debug-)?(.+)\.apk$')
if (-not $m.Success) { throw "无法从文件名推导版本：$($arm64Apk.Name)" }
$versionName = $m.Groups[1].Value
$tag = "cn-v$versionName"

Write-Host "版本      : $versionName"
Write-Host "Tag       : $tag"
Write-Host "arm64     : $($arm64Apk.Name)（$([math]::Round($arm64Apk.Length / 1MB, 1)) MB）"
if ($armv7Apk) { Write-Host "armeabi   : $($armv7Apk.Name)（$([math]::Round($armv7Apk.Length / 1MB, 1)) MB）" }
Write-Host ''

# ---------- 读取 APK versionCode（清单的 baseVersionCode 必须与 APK 一致） ----------
$aapt2 = Get-ChildItem -Path 'D:/android/build-tools' -Recurse -Filter 'aapt2.exe' -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | Select-Object -First 1
if (-not $aapt2) {
    $aapt2 = Get-ChildItem -Path "$env:LOCALAPPDATA/Android/Sdk/build-tools" -Recurse -Filter 'aapt2.exe' -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
}
if (-not $aapt2) { throw '找不到 aapt2.exe（需要读 APK versionCode）。' }
$badging = & $aapt2.FullName dump badging $arm64Apk.FullName 2>$null
$vcMatch = [regex]::Match(($badging -join "`n"), "versionCode='(\d+)'")
if (-not $vcMatch.Success) { throw '无法从 APK 读取 versionCode。' }
$versionCode = [int]$vcMatch.Groups[1].Value
Write-Host "versionCode: $versionCode"
Write-Host ''

# ---------- 补丁定位 ----------
# **只认与当前发布版本匹配的补丁**（acs-hotupdate-cn-<versionName>.zip）。
# 不能用「patch-work-cn 下最新的 zip」：该目录会留存历史版本的补丁，
# 发布 r09（首个 CN Release，无补丁）时会捡到 r07 的旧补丁传上去，
# 且清单会带上错误的 patch 块（实测踩到）。
if ([string]::IsNullOrWhiteSpace($PatchFile)) {
    $expectedName = "acs-hotupdate-cn-$versionName.zip"
    $candidate = Join-Path $repoRoot "patch-work-cn/$expectedName"
    if (Test-Path $candidate) {
        $PatchFile = $candidate
    }
    else {
        Write-Host "本地无 $expectedName，尝试从 GitHub Release v$versionName 下载…"
        $tmpDir = Join-Path $env:TEMP "cn-patch-dl-$versionName"
        $tmpPatch = Join-Path $tmpDir $expectedName
        # gh 在 dubious-ownership 仓库里会往 stderr 打警告，PS 5.1 下原生命令的
        # stderr 在 ErrorActionPreference=Stop 时会变成终止错误——临时放宽。
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & gh release download "v$versionName" --pattern $expectedName --dir $tmpDir --clobber 2>&1 | Out-Null
            if ($LASTEXITCODE -eq 0 -and (Test-Path $tmpPatch)) { $PatchFile = $tmpPatch }
            else { Write-Warning "GitHub Release 无 CN 补丁（首个 CN 版本属正常），跳过补丁上传。" }
        }
        finally { $ErrorActionPreference = $prevEap }
    }
}

$patchSha = $null
$patchBaseVc = $null
if (-not [string]::IsNullOrWhiteSpace($PatchFile) -and (Test-Path $PatchFile)) {
    $patchItem = Get-Item $PatchFile
    $patchSha = (Get-FileHash $PatchFile -Algorithm SHA256).Hash.ToLower()
    Write-Host "CN 补丁   : $($patchItem.Name)（$([math]::Round($patchItem.Length / 1MB, 1)) MB, sha256=$($patchSha.Substring(0,12))…）"
    # 读补丁内 patch.json 的 baseVersionCode（App 端据此判定补丁适用性）
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($PatchFile)
    try {
        $entry = $zip.GetEntry('patch.json')
        if ($entry) {
            $reader = New-Object System.IO.StreamReader($entry.Open())
            $patchMeta = $reader.ReadToEnd() | ConvertFrom-Json
            $patchBaseVc = [int]$patchMeta.baseVersionCode
            $reader.Dispose()
        }
    }
    finally { $zip.Dispose() }
    if ($null -eq $patchBaseVc) { Write-Warning '补丁内无 patch.json，清单将不带 patch 块。' }
}
Write-Host ''

# ---------- changelog ----------
if ([string]::IsNullOrWhiteSpace($ChangelogFile)) {
    $candidate = Join-Path $repoRoot "whatsnew/$versionName.md"
    if (Test-Path $candidate) { $ChangelogFile = $candidate }
}
$changelogContent = if ($ChangelogFile -and (Test-Path $ChangelogFile)) {
    # 必须显式指定 UTF-8：PS 5.1 的 Get-Content 默认按系统 ANSI（中文 Windows 是 GBK）
    # 解码，而 whatsnew/*.md 是 UTF-8——按 GBK 读会把每个中文字节错解后重新编码，
    # 上传到 Gitee 的 changelog 变成双重编码乱码（实测：弹窗里全是「鏈€鏂」）。
    [System.IO.File]::ReadAllText($ChangelogFile, [Text.Encoding]::UTF8)
}
else {
    Write-Host "无 changelog 文件，使用占位文案。"
    "CN 版 $versionName 发布。"
}
$changelogPath = "whatsnew-cn/$versionName.md"

# ---------- DryRun ----------
if ($DryRun) {
    Write-Host '[DryRun] 计划：'
    Write-Host "  1. 切卷上传 $($arm64Apk.Name) → $Repo release $tag"
    if ($armv7Apk) { Write-Host "  2. 切卷上传 $($armv7Apk.Name)" }
    if ($patchSha) { Write-Host "  3. 上传 CN 补丁 → $Repo release $tag" }
    Write-Host "  4. 推送 changelog → $ManifestRepo/$changelogPath"
    Write-Host "  5. 生成并推送 updater-cn.json → $ManifestRepo（versionCode=$versionCode, patch=$([bool]$patchSha)）"
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
$headers = @{ Authorization = "token $token" }

# ---------- 确保仓库存在 ----------
# Gitee 的建仓/改仓 API 有个坑：**建仓时 `private=false` 参数被忽略**（建出来仍是
# 私有，匿名下载 403），建完必须再 PATCH 一次 `private=false`（此时才生效）；
# 而 PATCH 又必须带 `name` 字段（缺了报 "name is missing"）。这组行为是实测结论
# （acs-cn-releases 首次发布时踩到：上传全部 201、匿名访问全 403）。
function Ensure-GiteeRepo([string]$repo) {
    $repoName = $repo.Split('/', 2)[1]
    $exists = $true
    try {
        Invoke-RestMethod -Uri "https://gitee.com/api/v5/repos/$repo" -Headers $headers -Method Get | Out-Null
    }
    catch { $exists = $false }

    if (-not $exists) {
        Write-Host "  创建仓库 $repo（auto_init）…"
        $body = @{
            name        = $repoName
            description = 'Android Code Studio CN 版发布资产（Gitee 源构建）'
            private     = $false
            auto_init   = $true
        } | ConvertTo-Json
        Invoke-RestMethod -Uri 'https://gitee.com/api/v5/user/repos' -Headers $headers -Method Post `
            -ContentType 'application/json' -Body $body | Out-Null
        Start-Sleep -Seconds 2
    }

    # 无论新建还是已存在，都强制公开一次——私有仓库的 Release 附件匿名访问会 403。
    $patchBody = @{ name = $repoName; private = $false } | ConvertTo-Json
    $patched = Invoke-RestMethod -Uri "https://gitee.com/api/v5/repos/$repo" -Headers $headers `
        -Method Patch -ContentType 'application/json' -Body $patchBody
    if ($patched.private) {
        throw "仓库 $repo 仍为私有（PATCH 未生效），匿名下载会 403。请到 Gitee 网页端改为公开后重跑。"
    }
    Write-Host "  仓库 $repo 已就绪（public）"
}

Write-Host '检查仓库…'
Ensure-GiteeRepo $Repo

# ---------- 确保 Release 存在 ----------
$api = "https://gitee.com/api/v5/repos/$Repo"
$releaseId = $null
Write-Host "检查 Release $tag…"
try {
    $existing = Invoke-RestMethod -Uri "$api/releases/tags/$tag" -Headers $headers -Method Get
    $releaseId = $existing.id
    Write-Host "  已存在（id=$releaseId）"
}
catch {
    Write-Host '  不存在，创建…'
    $body = @{
        tag_name         = $tag
        name             = $tag
        body             = "Android Code Studio CN 版 $versionName。APK 分卷下载后拼接：cat *.part.* > app.apk"
        target_commitish = $Branch
    } | ConvertTo-Json
    $created = Invoke-RestMethod -Uri "$api/releases" -Headers $headers -Method Post `
        -ContentType 'application/json' -Body $body
    $releaseId = $created.id
    Write-Host "  已创建（id=$releaseId）"
}
if (-not $releaseId) { throw '未能取得 release id。' }

# ---------- 上传附件（切卷 + 补丁） ----------
$curl = Get-Command curl.exe -ErrorAction SilentlyContinue
if (-not $curl) { throw '需要 curl.exe（Windows 10+ 自带）。' }

$existingNames = @{}
try {
    $assets = Invoke-RestMethod -Uri "$api/releases/$releaseId/attach_files" -Headers $headers -Method Get
    foreach ($asset in $assets) { $existingNames[$asset.name] = $asset.id }
}
catch { }

function Upload-GiteeAttachment([string]$path) {
    $item = Get-Item $path
    # Gitee 上传时对文件名做 URL 解码：`+` 会被存成空格，导致「按原名下载 404、
    # 按空格名（%20）才能下」。为避免这条歧义（且让 URL 稳定可预测），上传前把
    # 文件名里的 `+` 统一替换为 `-`（版本号 1.0.0+gh.r07 → 1.0.0-gh.r07）。
    # 下载 URL 里的名字与之一致，客户端无需做任何转义处理。
    $uploadName = $item.Name -replace '\+', '-'
    $uploadPath = Join-Path $item.DirectoryName $uploadName
    if ($uploadName -ne $item.Name) {
        Copy-Item $item.FullName $uploadPath -Force
    }
    if ($existingNames.ContainsKey($uploadName)) {
        Write-Host "  删除旧附件 $uploadName"
        Invoke-RestMethod -Uri "$api/releases/$releaseId/attach_files/$($existingNames[$uploadName])" `
            -Headers $headers -Method Delete | Out-Null
    }
    Write-Host "  上传 $uploadName（$([math]::Round($item.Length / 1MB, 1)) MB）…"
    & curl.exe -sS --http1.1 -X POST "$api/releases/$releaseId/attach_files" `
        -F "access_token=$token" `
        -F "file=@$uploadPath" `
        -o "$env:TEMP\gitee-cn-upload.json" -w "HTTP %{http_code}`n"
    if ($LASTEXITCODE -ne 0) { throw "curl 退出码 $LASTEXITCODE（$uploadName）" }
    $result = Get-Content "$env:TEMP\gitee-cn-upload.json" -Raw
    if ($result -match 'malicious') {
        throw "Gitee 误判 $uploadName 为恶意文件。绕过：本地重新压缩 APK 改变哈希后重跑（不要直接重传）。"
    }
    if ($result -notmatch '"id"') { throw "上传响应异常：$result" }
}

function Split-File([string]$path, [string]$destDir) {
    $item = Get-Item $path
    $partSize = $PartSizeMb * 1MB
    $count = [math]::Ceiling($item.Length / $partSize)
    New-Item -ItemType Directory -Path $destDir -Force | Out-Null
    $in = [System.IO.File]::OpenRead($path)
    $buffer = New-Object byte[] (4MB)
    try {
        for ($i = 0; $i -lt $count; $i++) {
            $partPath = Join-Path $destDir ("{0}.part.{1:d2}" -f $item.Name, $i)
            $out = [System.IO.File]::Create($partPath)
            try {
                $remaining = [math]::Min($partSize, $in.Length - $in.Position)
                while ($remaining -gt 0) {
                    $toRead = [int][math]::Min($buffer.Length, $remaining)
                    $read = $in.Read($buffer, 0, $toRead)
                    if ($read -le 0) { break }
                    $out.Write($buffer, 0, $read)
                    $remaining -= $read
                }
            }
            finally { $out.Dispose() }
        }
    }
    finally { $in.Dispose() }
    return Get-ChildItem -Path $destDir -Filter "$($item.Name).part.*" | Sort-Object Name
}

$baseUrl = "https://gitee.com/$Repo/releases/download/$tag"
$allPartNames = @{}  # 架构 → 分卷名列表（给 updater-cn.json；名字已把 + 换成 -）

# 上传名统一规则（与 Upload-GiteeAttachment 内的替换一致）：`+` → `-`。
function Get-UploadName([string]$name) { return $name -replace '\+', '-' }

Write-Host ''
Write-Host '上传 arm64 分卷…'
$arm64Parts = Split-File $arm64Apk.FullName (Join-Path $env:TEMP "cn-parts-$versionName-arm64")
foreach ($p in $arm64Parts) { Upload-GiteeAttachment $p.FullName }
$allPartNames['arm64-v8a'] = @($arm64Parts | ForEach-Object { Get-UploadName $_.Name })
$arm64Sha = (Get-FileHash $arm64Apk.FullName -Algorithm SHA256).Hash.ToLower()

if ($armv7Apk) {
    Write-Host ''
    Write-Host '上传 armeabi-v7a 分卷…'
    $armv7Parts = Split-File $armv7Apk.FullName (Join-Path $env:TEMP "cn-parts-$versionName-armv7")
    foreach ($p in $armv7Parts) { Upload-GiteeAttachment $p.FullName }
    $allPartNames['armeabi-v7a'] = @($armv7Parts | ForEach-Object { Get-UploadName $_.Name })
    $armv7Sha = (Get-FileHash $armv7Apk.FullName -Algorithm SHA256).Hash.ToLower()
}

$patchUploadName = $null
if ($patchSha) {
    Write-Host ''
    Write-Host '上传 CN 补丁…'
    $patchItem = Get-Item $PatchFile
    $patchUploadName = Get-UploadName $patchItem.Name
    Upload-GiteeAttachment $patchItem.FullName
}

# ---------- 推送文件到 acs-mirror（contents API） ----------
function Push-GiteeFile([string]$repo, [string]$path, [string]$content, [string]$message) {
    # Gitee contents API 与 GitHub 不同：**新建用 POST**，更新才用 PUT+sha。
    # （实测：对不存在文件直接 PUT 会报 "sha is missing"。）
    $apiBase = "https://gitee.com/api/v5/repos/$repo/contents/$path"
    $existingSha = $null
    try {
        $meta = Invoke-RestMethod -Uri "$apiBase`?access_token=$token" -Method Get
        $existingSha = $meta.sha
    }
    catch { }
    $body = @{
        access_token = $token
        content      = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($content))
        message      = $message
        branch       = $Branch
    }
    $json = $body | ConvertTo-Json
    if ($existingSha) {
        $body['sha'] = $existingSha
        $json = $body | ConvertTo-Json
        Invoke-RestMethod -Uri $apiBase -Method Put -ContentType 'application/json; charset=utf-8' -Body $json | Out-Null
        Write-Host "  更新 $repo/$path 完成"
    }
    else {
        Invoke-RestMethod -Uri $apiBase -Method Post -ContentType 'application/json; charset=utf-8' -Body $json | Out-Null
        Write-Host "  新建 $repo/$path 完成"
    }
}

Write-Host ''
Write-Host '推送 changelog 与 updater-cn.json…'
Push-GiteeFile $ManifestRepo $changelogPath $changelogContent "docs(cn): $versionName changelog"

# updater-cn.json 与 App 端 TomIDEUpdater.parseUpdateInfo 的字段一一对应：
#   baseUrl 含 {versionName} 占位符（App 端 expandUrl 展开）；
#   variants[*].parts 是分卷 URL 列表（展开后）；sha256 是拼接后整包哈希；
#   patch.patchUrl/sha256/baseVersionCode 供补丁分支使用。
$variants = [ordered]@{}
foreach ($abi in $allPartNames.Keys) {
    $partsExpanded = @($allPartNames[$abi] | ForEach-Object { "$baseUrl/$_" })
    $variants[$abi] = [ordered]@{
        versionCode = $versionCode
        versionName = $versionName
        apkUrl      = ''
        parts       = $partsExpanded
        sha256      = $(if ($abi -eq 'arm64-v8a') { $arm64Sha } else { $armv7Sha })
    }
}

$manifest = [ordered]@{
    baseVersionCode = $versionCode
    baseVersionName = $versionName
    baseUrl         = "https://gitee.com/$Repo/releases/download/cn-v{versionName}"
    variants        = $variants
    changelog       = "https://gitee.com/$ManifestRepo/raw/master/$changelogPath"
}
if ($patchSha -and $patchBaseVc) {
    $manifest['patch'] = [ordered]@{
        versionCode     = $versionCode
        versionName     = $versionName
        baseVersionCode = $patchBaseVc
        patchUrl        = "$baseUrl/$patchUploadName"
        sha256          = $patchSha
    }
}

$manifestJson = $manifest | ConvertTo-Json -Depth 6
Push-GiteeFile $ManifestRepo 'updater-cn.json' $manifestJson "chore(cn-release): $versionName"

Write-Host ''
Write-Host '==== 全部完成 ===='
Write-Host "updater-cn.json : https://gitee.com/$ManifestRepo/raw/master/updater-cn.json"
Write-Host "Release         : $baseUrl"
Write-Host ''
Write-Host '用户拼接方式（下载对应架构的 *.part.* 全部文件后）：'
Write-Host "  Linux/macOS : cat $($arm64Apk.Name).part.* > $($arm64Apk.Name)"
Write-Host "  Windows     : copy /b $($arm64Apk.Name).part.00+$($arm64Apk.Name).part.01 $($arm64Apk.Name)"
Write-Host ''
Write-Host "拼接后校验 sha256（arm64）：$arm64Sha"

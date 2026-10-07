<#
.SYNOPSIS
    把发布签名密钥写入 GitHub Secrets，供 CI 构建「可覆盖安装」的 APK。

.DESCRIPTION
    背景：已发布 r4/r5/r6 的 APK 都是用本机 acs-debug-signing.jks 签的
    （证书 SHA-1 83ABB7381685A06DBDAE877DED4A207F4919C1DA）。而 CI 现在签的是
    仓库里另一把 signing/signing-key.jks —— 证书不同，CI 产出的包**无法覆盖安装**，
    用户只能卸载重装并丢掉 3.7G 数据。本脚本把正确的那把密钥送进 Secrets。

    **密钥不经过命令行参数、不打印、不落盘为临时文件**：base64 编码在内存里完成，
    通过 stdin 直接管道给 `gh secret set`。这样密钥不会出现在 shell 历史、
    进程列表、脚本输出或任何日志中。

.PARAMETER Keystore
    keystore 路径。默认 D:\android\keys\acs-debug-signing.jks。

.PARAMETER Repo
    GitHub 仓库，默认 706412584/android-code-studio。

.PARAMETER ExpectedCertSha1
    期望的证书 SHA-1，用于确认拿对了密钥。默认是已发布版本用的那把。

.PARAMETER DryRun
    只做本地校验（文件存在、密码可打开、证书匹配、base64 往返一致），不写 Secrets。

.EXAMPLE
    # 先本地校验，确认密钥正确且能往返解码
    .\tools\setup-ci-signing.ps1 -DryRun

    # 写入 Secrets
    .\tools\setup-ci-signing.ps1

.NOTES
    写入后还需要改 .github/workflows/asm_build.yml：在构建前把
    ${{ secrets.SIGNING_KEYSTORE_BASE64 }} 解码写回 signing/signing-key.jks
    （覆盖仓库里那把），CI 才会用正确的证书签名。
#>
[CmdletBinding()]
param(
    [string]$Keystore = 'D:\android\keys\acs-debug-signing.jks',
    [string]$Repo = '706412584/android-code-studio',
    [string]$ExpectedCertSha1 = '83ABB7381685A06DBDAE877DED4A207F4919C1DA',
    [string]$StorePassword = 'android',
    [string]$KeyPassword = 'android',
    [string]$KeyAlias = 'androidcs',
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Ok($m) { Write-Host "  OK  $m" -ForegroundColor Green }
function Warn($m) { Write-Host "  !!  $m" -ForegroundColor Yellow }
function Die($m) { Write-Host "  XX  $m" -ForegroundColor Red; exit 1 }
function Info($m) { Write-Host "  $m" -ForegroundColor Cyan }

# 与 publish-release.ps1 同样的处理：PS 5.1 会把原生命令的 stderr 当终止错误。
function Invoke-Native([scriptblock]$cmd) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $cmd 2>&1 | Out-String
        return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $out }
    } finally { $ErrorActionPreference = $prev }
}

Write-Host "`n写入 CI 签名密钥 -> $Repo`n" -ForegroundColor Magenta

# ---------- 1. 文件存在 ----------
if (-not (Test-Path $Keystore)) { Die "找不到 keystore: $Keystore" }
$len = (Get-Item $Keystore).Length
Ok "keystore: $Keystore ($len 字节)"

# ---------- 2. 证书指纹匹配 ----------
$keytool = Get-ChildItem -Path @(
    "$env:JAVA_HOME\bin\keytool.exe",
    'C:\Users\70641\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2\bin\keytool.exe'
) -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $keytool) { Die '找不到 keytool，请设置 JAVA_HOME' }

$r = Invoke-Native { & $keytool.FullName -list -v -keystore $Keystore -storepass $StorePassword -alias $KeyAlias }
if ($r.ExitCode -ne 0) { Die "keytool 无法用给定密码打开 keystore（密码错了？）`n$($r.Output)" }
$m = [regex]::Match($r.Output, 'SHA1:\s*([0-9A-Fa-f:]{59})')
if (-not $m.Success) { Die '无法解析证书 SHA-1' }
$actual = $m.Groups[1].Value.Replace(':', '').ToUpperInvariant()
if ($actual -ne $ExpectedCertSha1.ToUpperInvariant()) {
    Die "证书不匹配！`n  期望 $ExpectedCertSha1`n  实际 $actual`n换错密钥会让 CI 产出无法覆盖安装的包。"
}
Ok "证书 SHA-1 $actual  ✓ 与已发布版本一致"

# ---------- 3. base64 往返校验 ----------
$bytes = [System.IO.File]::ReadAllBytes($Keystore)
$b64 = [Convert]::ToBase64String($bytes)
$roundTrip = [Convert]::FromBase64String($b64)
if ($roundTrip.Length -ne $bytes.Length) { Die 'base64 往返长度不一致' }
for ($i = 0; $i -lt $bytes.Length; $i++) {
    if ($roundTrip[$i] -ne $bytes[$i]) { Die 'base64 往返内容不一致' }
}
Ok "base64 往返一致（$($b64.Length) 字符，内容不打印）"

if ($DryRun) {
    Warn 'DryRun：未写入 Secrets。去掉 -DryRun 正式写入。'
    Write-Host "`n本地校验全部通过。`n" -ForegroundColor Green
    exit 0
}

# ---------- 4. 写入 Secrets ----------
# 通过 stdin 传值（`gh secret set NAME` 无 --body 时读 stdin），
# 避免出现在命令行参数/进程列表里。
function Set-SecretFromString([string]$name, [string]$value) {
    $r = Invoke-Native { $value | gh secret set $name -R $Repo }
    if ($r.ExitCode -ne 0) { Die "写入 $name 失败`n$($r.Output)" }
    Ok "已写入 $name"
}

Set-SecretFromString 'SIGNING_KEYSTORE_BASE64' $b64
Set-SecretFromString 'SIGNING_STORE_PASSWORD' $StorePassword
Set-SecretFromString 'SIGNING_KEY_PASSWORD' $KeyPassword

# 立即从内存中清掉明文副本（非加密内存，但减少驻留窗口）。
$b64 = $null; $bytes = $null; $roundTrip = $null
[System.GC]::Collect()

Write-Host "`n=== 当前 Secrets ===" -ForegroundColor White
$list = Invoke-Native { gh secret list -R $Repo }
Write-Host $list.Output

Write-Host @"

下一步（还需手工改一次 workflow）：
  在 .github/workflows/asm_build.yml 的 Assemble 步骤之前插入：

    - name: Restore signing keystore
      run: echo "${'$'}{{ secrets.SIGNING_KEYSTORE_BASE64 }}" | base64 -d > signing/signing-key.jks

  这样 CI 就会用与线上一致的那把密钥签名。改完建议用 publish-release.ps1
  的证书校验逻辑（或 apksigner verify --print-certs）核对 CI 产物的指纹。

"@ -ForegroundColor Cyan

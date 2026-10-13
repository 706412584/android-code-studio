# 构建 ACS APT 首装镜像（Gitee 分发）
#
# 用途：从本地镜像备份提取首装依赖闭包，打包为分卷上传 Gitee Release。
# 背景：Gitee 无法作标准 apt 源（raw 大文件 403 / 附件无目录结构），
#       故采用「分卷包 + 设备端解包为 file: 本地源」方案。
#
# 用法：
#   1. 更新 core-pkgs.txt（首装包清单，从设备 apt history 提取）
#   2. 运行本脚本生成 dists + pool + tar.gz 分卷
#   3. 上传到 Gitee：wu-yongchengsvip_admin/acs-apt-arm64 的 apt-core-v1 release
#
# 设备端：sh ~/acs-apt-mirror.sh（见同目录 acs-apt-mirror.sh）

param(
    [string]$SrcMirror = 'D:\android\acs-mirror-backup\arm64',
    [string]$OutDir = 'D:\data\cchh\tmp\apt-mirror-core',
    [string]$PkgList = "$PSScriptRoot\core-pkgs.txt"
)

$ErrorActionPreference = 'Stop'

Write-Host "=== 构建 ACS APT 首装镜像 ==="
Write-Host "源镜像: $SrcMirror"
Write-Host "输出:   $OutDir"

# 1. 解析 Packages 索引
$packagesGz = Join-Path $SrcMirror 'dists\stable\main\binary-aarch64\Packages.gz'
if (-not (Test-Path $packagesGz)) { throw "Packages.gz 不存在: $packagesGz" }

# 用 Python 处理（gzip 解析 + 文件复制）
$py = @"
import gzip, os, shutil, hashlib, re, sys

SRC = r'$SrcMirror'
OUT = r'$OutDir'
LIST = r'$PkgList'

data = gzip.open(os.path.join(SRC, 'dists/stable/main/binary-aarch64/Packages.gz'),'rt',encoding='utf-8',errors='replace').read()
blocks = {}
for b in data.split('\n\n'):
    name = None
    for line in b.split('\n'):
        if line.startswith('Package: '): name = line[9:].strip()
    if name: blocks[name] = b

pkgs = [l.strip() for l in open(LIST) if l.strip()]
os.makedirs(os.path.join(OUT, 'pool/main'), exist_ok=True)
os.makedirs(os.path.join(OUT, 'dists/stable/main/binary-aarch64'), exist_ok=True)

new_blocks = []
for p in pkgs:
    if p not in blocks:
        print(f'WARN: {p} 不在索引中，跳过'); continue
    blk = blocks[p]
    fn = None
    for line in blk.split('\n'):
        if line.startswith('Filename: '): fn = line[10:].strip()
    src = os.path.join(SRC, fn)
    if not os.path.exists(src):
        print(f'WARN: {src} 不存在，跳过'); continue
    base = os.path.basename(fn)
    dst = os.path.join(OUT, 'pool/main', base)
    with open(src,'rb') as fi, open(dst,'wb') as fo:
        shutil.copyfileobj(fi, fo, 1024*1024)
    new_blocks.append(blk.replace(f'Filename: {fn}', f'Filename: pool/main/{base}'))

pkgs_content = '\n\n'.join(new_blocks) + '\n'
open(os.path.join(OUT, 'dists/stable/main/binary-aarch64/Packages'),'w',encoding='utf-8').write(pkgs_content)
with gzip.open(os.path.join(OUT, 'dists/stable/main/binary-aarch64/Packages.gz'),'wt',encoding='utf-8') as f:
    f.write(pkgs_content)

def h(path):
    d = open(path,'rb').read()
    return hashlib.md5(d).hexdigest(), hashlib.sha1(d).hexdigest(), hashlib.sha256(d).hexdigest(), len(d)

m5,s1,s256,sz = h(os.path.join(OUT,'dists/stable/main/binary-aarch64/Packages'))
g5,gs1,gs256,gsz = h(os.path.join(OUT,'dists/stable/main/binary-aarch64/Packages.gz'))

import datetime
date = datetime.datetime.utcnow().strftime('%a, %d %b %Y %H:%M:%S UTC')
release = f'''Origin: ACS-Mirror
Label: ACS-Mirror
Suite: stable
Codename: stable
Date: {date}
Architectures: aarch64
Components: main
Description: ACS Termux APT mirror (Gitee)
MD5Sum:
 {m5} {sz} main/binary-aarch64/Packages
 {g5} {gsz} main/binary-aarch64/Packages.gz
SHA1:
 {s1} {sz} main/binary-aarch64/Packages
 {gs1} {gsz} main/binary-aarch64/Packages.gz
SHA256:
 {s256} {sz} main/binary-aarch64/Packages
 {gs256} {gsz} main/binary-aarch64/Packages.gz
'''
open(os.path.join(OUT,'dists/stable/Release'),'w',encoding='utf-8').write(release)
print(f'OK: {len(new_blocks)} 包, Packages {sz}B')
"@

$py | python -
Write-Host "dists 生成完成"

# 2. 打包
$tar = Join-Path (Split-Path $OutDir) 'apt-core.tar.gz'
if (Test-Path $tar) { Remove-Item $tar }
tar -czf $tar -C $OutDir .
$sha = (Get-FileHash $tar -Algorithm SHA256).Hash.ToLower()
Write-Host "tar.gz: $tar"
Write-Host "sha256: $sha"
Write-Host ""
Write-Host "下一步："
Write-Host "  1. 更新 tools/apt-mirror/acs-apt-mirror.sh 里的 EXPECTED_SHA256 = $sha"
Write-Host "  2. split -b 99614720 -d -a 2 apt-core.tar.gz apt-parts/apt-core-v1.part."
Write-Host "  3. 上传分卷到 Gitee release apt-core-v1"

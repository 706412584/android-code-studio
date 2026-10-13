#!/data/data/com.tom.rv2ide/files/usr/bin/sh
# ACS APT 镜像安装脚本（Gitee 源）
# 用法：在 ACS 内置终端运行  sh ~/acs-apt-mirror.sh
set -e

export PATH=/data/data/com.tom.rv2ide/files/usr/bin:$PATH
export HOME=/data/data/com.tom.rv2ide/files/home
export PREFIX=/data/data/com.tom.rv2ide/files/usr

BASE="https://gitee.com/wu-yongchengsvip_admin/acs-apt-arm64/releases/download/apt-core-v1"
MIRROR_DIR="$HOME/.acs-apt-mirror"
PARTS="apt-core-v1.part.00 apt-core-v1.part.01"
EXPECTED_SHA256="393d346398e262ab8c24ee36717a23c2fd0ab6fa69750eabf33b06a098bf64d1"

echo "=== ACS APT 镜像安装 ==="
echo "下载地址: $BASE"
echo "目标目录: $MIRROR_DIR"
echo

mkdir -p "$MIRROR_DIR/parts"
cd "$MIRROR_DIR/parts"
for p in $PARTS; do
  if [ -f "$p" ]; then
    echo "[跳过] $p 已存在"
  else
    echo "[下载] $p ..."
    curl -sSL --retry 3 -o "$p" "$BASE/$p"
    echo "  done: $(wc -c < "$p") bytes"
  fi
done

echo "[合并] 分卷..."
cat $PARTS > "$MIRROR_DIR/apt-core.tar.gz"
ACTUAL=$(sha256sum "$MIRROR_DIR/apt-core.tar.gz" | cut -d' ' -f1)
if [ "$ACTUAL" != "$EXPECTED_SHA256" ]; then
  echo "!! sha256 不匹配"
  echo "   期望: $EXPECTED_SHA256"
  echo "   实际: $ACTUAL"
  exit 1
fi
echo "[校验] sha256 OK"

echo "[解包] ..."
rm -rf "$MIRROR_DIR/repo"
mkdir -p "$MIRROR_DIR/repo"
tar -xzf "$MIRROR_DIR/apt-core.tar.gz" -C "$MIRROR_DIR/repo"
echo "[解包] done: $(find "$MIRROR_DIR/repo" -name "*.deb" | wc -l) debs"

echo "[配置] sources.list ..."
printf 'deb [trusted=yes] file:%s/repo stable main\n' "$MIRROR_DIR" > "$PREFIX/etc/apt/sources.list"
cat "$PREFIX/etc/apt/sources.list"

echo "[验证] apt update ..."
apt-get update 2>&1 | tail -3
echo
echo "=== 完成！现在可以 apt install 了 ==="
echo "示例: apt install jq wget git openssh"

#!/usr/bin/env bash
# 把 ticode 中文版源码同步进 QuickDevelop 模板 assets。
#
# 用法: tools/sync-ticode-assets.sh
#
# 为什么需要：模板生成工程时从 APK 的 assets 拷源码（见 QuickDevelop.copyTicodeSources）。
# 源码在仓库里因此有**两份**：core/ticode/src/zh/java（可编译/可测的源）与
# core/app/src/main/assets/QuickDevelop/ticode（模板打包用）。本脚本保证两者一致。
# 改动 core/ticode 源码后，务必跑一次本脚本，否则模板产出的是旧代码。
set -euo pipefail

ROOT="D:/android/projecet_iade/android-code-studio"
SRC="$ROOT/core/ticode/src/zh/java"
DST="$ROOT/core/app/src/main/assets/QuickDevelop/ticode"

if [ ! -d "$SRC/ticode/zh" ]; then
  echo "!! 源目录不存在: $SRC/ticode/zh"; exit 1
fi

rm -rf "$DST"
mkdir -p "$DST"
# 只拷 .java，保持 ticode/zh/** 目录结构（DST 即 java source root）
(cd "$SRC" && find ticode -name '*.java' -print0 | tar --null -cf - -T -) | (cd "$DST" && tar -xf -)

N=$(find "$DST" -name '*.java' | wc -l)
echo "同步完成: $N 个 .java -> $DST"
if [ "$N" -ne 333 ]; then
  echo "!! 预期 333 个文件，实际 $N —— 请确认是否有意增删"
fi
# 注：core/ticode/src/zh/java/简易无障碍.java 在**默认包**（源码里 `包名` 被注释掉），
# 且未被任何 ticode.zh.* 类引用。默认包类无法被命名包引用，作为库类不可用，
# 故不纳入模板 module（它还需要 manifest 的 accessibility service 声明）。

#!/bin/bash
# 从 LineCode Pro 搬运 Java 源文件到 AndroidCodeStudio（扁平化到单一包）
#
# 用法: port.sh <目标包目录> <源文件>...
#
# 策略：所有搬运的类扁平化进同一个 Java 包。因为同包内互相引用无需 import，
# 所以直接删除所有 `import cn.lineai.*;` 行即可，无需逐条重写映射。
# 指向未搬运类的 import 会在编译期暴露，届时人工处理。
#
# 同时：
#   - 重写 package 声明
#   - 保留源文件自带的 GPL 头；若无可用的版权头则插入来源声明

set -euo pipefail

LCP="/d/软件/LineCodePro"
DEST_DIR="$1"; shift
PORT_PKG="com.tom.rv2ide.ai.tool.api"

mkdir -p "$DEST_DIR"

HEADER='/*
 * This file is part of AndroidCodeStudio.
 *
 * Ported from LineCode Pro (https://github.com/LangLang03/LineCodePro),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */'

for src in "$@"; do
  if [ ! -f "$src" ]; then
    echo "MISSING: $src" >&2
    continue
  fi

  base=$(basename "$src")
  dest="$DEST_DIR/$base"

  # 若源文件已有 GPL 版权头（含 "GNU General Public"），保留原样；
  # 否则插入来源声明头。
  has_header=0
  if head -25 "$src" | grep -q "GNU General Public"; then
    has_header=1
  fi

  {
    if [ "$has_header" -eq 0 ]; then
      echo "$HEADER"
      echo
    fi
    # 重写 package，删除所有 cn.lineai 的 import（扁平化后同包无需 import）
    sed -e "s|^package cn\.lineai\..*;|package $PORT_PKG;|" \
        -e "/^import cn\.lineai\./d" \
        "$src"
  } > "$dest"

  echo "PORTED: $(echo "$src" | sed "s|$LCP/||") -> $base"
done

#!/bin/bash
# 从 LineCode Pro 搬运 Java 源文件到 AndroidCodeStudio（扁平化到固定包）
#
# 用法: port.sh <目标包目录> <源文件>...
#
# 策略：所有搬运的类扁平化进两个包，因此 LCP 的包前缀可以按固定映射重写。
# 这比「提取类名再判断」更简单也更可靠——无论引用出现在 import 行、内联全限定名，
# 还是 Javadoc 的 {@link} 里，一次性全部覆盖。
#
# 映射（顺序敏感：长前缀必须排在短前缀之前）：
#   cn.lineai.model.tool.*  -> com.tom.rv2ide.ai.tool.api.*
#   cn.lineai.tool.*        -> com.tom.rv2ide.ai.tool.api.*
#   cn.lineai.*             -> com.tom.rv2ide.ai.protocol.*
#
# 注：本模块只承载不依赖 Android 的协议层代码。若某个文件 import android.*，
# 说明它不属于这里，应排除（见 EXCLUDE 说明）。

set -euo pipefail

LCP="/d/软件/LineCodePro"
DEST_DIR="$1"; shift
PORT_PKG="com.tom.rv2ide.ai.protocol"
API_PKG="com.tom.rv2ide.ai.tool.api"

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

  # 守卫：协议层模块不能有 Android 依赖（否则应改用 android-library 或排除该文件）
  if grep -q "^import android\." "$src"; then
    echo "SKIP (依赖 Android，不属于纯协议层): $base" >&2
    continue
  fi

  dest="$DEST_DIR/$base"

  has_header=0
  if head -25 "$src" | grep -q "GNU General Public"; then
    has_header=1
  fi

  # 两段式重写。LCP 的引用形如 cn.lineai.<小写子包...>.<类名>；
  # 本模块把所有类扁平化，因此要把「cn.lineai + 全部子包」整体替换成目标包。
  #
  # 第一段：属于 core:ai-tool-api 的类，必须映射到 api 包（长名排在短名前，
  #         因为 sed 的 | 交替是首次匹配优先，否则 ToolCall 会先吃掉 ToolCallCardView）。
  # 第二段：其余类映射到本模块包。
  API_ALT='ToolCallCardView|ToolCategoryResolver|ToolDisplayCategory|PermissionResult|ToolReviewListener|ToolArgsCleaner|ToolCategory|ToolNames|ToolResult|ToolInfo|ToolCall|ToolArgs|Strings'

  {
    if [ "$has_header" -eq 0 ]; then echo "$HEADER"; echo; fi
    sed -E \
        -e "s#cn\.lineai\.[a-z0-9_.]*\.($API_ALT)\b#$API_PKG.\1#g" \
        -e "s#cn\.lineai\.[a-z0-9_.]*\.([A-Z][A-Za-z0-9_]*)#$PORT_PKG.\1#g" \
        -e "s#^package cn\.lineai\..*;#package $PORT_PKG;#" \
        "$src"
  } > "$dest"

  echo "PORTED: $(echo "$src" | sed "s|$LCP/||") -> $base"
done

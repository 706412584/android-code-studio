#!/bin/bash
# 从 LineCode Pro 搬运工具层 Java 源文件到 AndroidCodeStudio（扁平化到单一包）
set -euo pipefail
LCP="/d/软件/LineCodePro"
DEST_DIR="$1"; shift
PORT_PKG="com.tom.rv2ide.ai.tool"
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
API_ALT='ToolCallCardView|ToolCategoryResolver|ToolDisplayCategory|PermissionResult|ToolReviewListener|ToolArgsCleaner|ToolCategory|ToolNames|ToolResult|ToolInfo|ToolCall|ToolArgs|Strings'
for src in "$@"; do
  [ -f "$src" ] || { echo "MISSING: $src" >&2; continue; }
  base=$(basename "$src")
  has_header=0
  head -25 "$src" | grep -q "GNU General Public" && has_header=1
  {
    [ "$has_header" -eq 0 ] && { echo "$HEADER"; echo; }
    sed -E \
      -e "s#cn\.lineai\.[a-z0-9_.]*\.($API_ALT)\b#$API_PKG.\1#g" \
      -e "s#cn\.lineai\.[a-z0-9_.]*\.([A-Z][A-Za-z0-9_]*)#$PORT_PKG.\1#g" \
      -e "s#^package cn\.lineai\..*;#package $PORT_PKG;#" \
      "$src"
  } > "$DEST_DIR/$base"
  echo "PORTED: $base"
done

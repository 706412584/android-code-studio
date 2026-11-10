/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from Aharou (https://github.com/520huxiangli/Aharou),
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
 */

package com.tom.rv2ide.ssh

/**
 * POSIX sh 单引号转义：把 [value] 整体包成单引号字面量，内部的单引号用 `'"'"'` 转义，
 * 供容器/远程命令拼接时安全嵌入任意字符串（空格、引号等特殊字符不会破坏命令结构）。
 */
internal fun shellQuote(value: String): String {
  return "'" + value.replace("'", "'\"'\"'") + "'"
}
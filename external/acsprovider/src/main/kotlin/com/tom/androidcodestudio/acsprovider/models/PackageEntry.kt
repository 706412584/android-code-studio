/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.androidcodestudio.acsprovider.models

/*
 ** @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

data class PackageEntry(
    val id: String,
    val architecture: String,
    val version: String,
    val filename: String,
    val url: String,
    val sha256: String?,
    /**
     * 分卷下载地址（可选）。
     *
     * <p>镜像源（Gitee）单文件上限 100MB，超过的包（NDK ~180MB）被切成
     * 95MB 分卷上传。非空时按顺序下载各分卷、拼接为 [filename] 后做 sha256 校验；
     * 为空则走原有的单 URL 下载。两者最终产物完全一致（sha256 相同）。
     */
    val parts: List<String> = emptyList(),
)

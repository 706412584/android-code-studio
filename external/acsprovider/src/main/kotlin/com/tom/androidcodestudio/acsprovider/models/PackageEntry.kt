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
    /**
     * 该条目是否为**加密自解压 bundle**（可选，默认 false）。
     *
     * <p>manifest 里 `encrypted: true` 的条目指向的不是 tar.xz，而是一个
     * ARM ELF 自解压程序（约 10MB，内部嵌 xz）。它带启动密码校验，只能由
     * **官方 App 的 native 安装器**执行解包；本仓库没有任何对应的解包/解密
     * 实现（`INdk`/`ICMake` 的 `process*Install` 是无 native 实现的废弃桩），
     * 直接交给 tar 必然报 `This does not look like a tar archive`。
     *
     * <p>镜像 manifest 曾把同一 (包名, 架构, 版本) 同时列出 bundle 与 tar.xz
     * 两条，而 bundle 排在前面——[ACSProvider.findPackageEntry] 按顺序取
     * 第一条，于是稳定选中无法解包的 bundle（CN 版装 CMake 必失败）。
     * 选包逻辑据此字段跳过 bundle，见 `findPackageEntry`。
     */
    val encrypted: Boolean = false,
)

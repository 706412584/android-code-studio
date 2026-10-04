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
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.tooling.api

/**
 * AAPT2 相关的跨进程约定。
 *
 * app 进程与 tooling server 是**两个独立进程**，本类里的名字是它们之间的契约，
 * 因此定义在双方都依赖的 `tooling:api` 模块中。
 */
object Aapt2Config {

  /**
   * 传给 tooling server 进程的环境变量名，值为设备上 AAPT2 可执行文件的绝对路径。
   *
   * **为什么需要它**：AAPT2 路径常规上由 `getBuildArguments()` 的 RPC 结果传给构建，
   * 但那条链路一旦失败，`Main.configureLauncher` 只会记日志并丢弃**全部**构建参数。
   * 此时 AGP 会回退去 Maven 取 `aapt2:<version>:linux` 分类构件——那是 **x86-64**
   * 的二进制，在 arm64 设备上执行会报：
   *
   *     aapt2[...]: syntax error: unexpected '('
   *
   * 该环境变量提供一条独立于 RPC 的通道，使 aapt2 路径在 RPC 失效时仍然可用。
   */
  const val ENV_AAPT2_OVERRIDE = "ANDROIDIDE_AAPT2_PATH"

  /** AGP 用于指定 aapt2 可执行文件的 Gradle 属性名。 */
  const val PROPERTY_AAPT2_FROM_MAVEN_OVERRIDE = "android.aapt2FromMavenOverride"
}

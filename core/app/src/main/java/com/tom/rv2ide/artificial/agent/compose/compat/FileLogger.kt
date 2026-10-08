/*
 * This file is part of AndroidCodeStudio.
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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.compose.compat

import android.util.Log

/**
 * `com.aharou.core.util.FileLogger` 的最小等价物。
 *
 * <p>Aharou 的 FileLogger 会把日志落盘到应用私有目录（它需要日志文件导出给用户排查）。
 * ACS 全仓用 logcat + slf4j，没有「日志文件」这个概念，所以这里只做**同名转发**——
 * 移植过来的组件不必改调用点，日志也照样能在 logcat 里看到。
 *
 * <p>只实现被移植文件实际用到的 [w]。Aharou 那个类还有 v/d/i/e 与落盘、清理、按日期读取等，
 * 那些是 ACS 不需要的；真需要再加，不要凭它的目录名把整个类搬过来。
 *
 * <p>**这是 ACS 自己写的转发层**（非 Aharou 代码），所以不保留 Aharou 的许可证头。
 */
object FileLogger {

  /**
   * 等价于 Aharou 的 `FileLogger.w(tag, message, throwable)`。
   *
   * @param throwable 可为 null——Aharou 侧本来就是默认参数，调用点两种写法都有。
   */
  fun w(tag: String, message: String, throwable: Throwable? = null) {
    if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
  }
}

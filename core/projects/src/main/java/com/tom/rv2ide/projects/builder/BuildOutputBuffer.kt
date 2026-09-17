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

package com.tom.rv2ide.projects.builder

/**
 * 构建输出的有界环形缓冲。
 *
 * <p><b>为什么需要它</b>：`BuildService.EventListener` 是单槽位，由 IDE 的构建面板占用。
 * 但 AI agent 执行 `gradle_build` 时也需要知道<b>构建为什么失败</b>——只告诉它
 * `BUILD_FAILED` 这个枚举名，它只能靠反复试错去猜（实测烧掉 30 次工具调用）。
 *
 * <p>因此这里提供一个旁路缓冲：构建输出照常流向 UI，同时副本进这个环。
 * agent 在构建结束后读取尾部若干行，就能看到 `error: not a statement` 这类真实报错。
 *
 * <p><b>有界</b>：只保留最近 [capacity] 行。Android 构建输出可达数万行，
 * 无界保留既费内存，塞进模型上下文也会挤掉真正有用的信息——而错误总在尾部。
 *
 * <p>线程安全：构建输出来自 tooling 线程，读取来自 agent 线程。
 */
class BuildOutputBuffer(private val capacity: Int = DEFAULT_CAPACITY) {

  private val lines = ArrayDeque<String>(minOf(capacity, 1024))
  private val lock = Any()

  /** 追加一行。超过容量时丢弃最旧的。 */
  fun append(line: String?) {
    if (line == null) {
      return
    }
    synchronized(lock) {
      lines.addLast(line)
      while (lines.size > capacity) {
        lines.removeFirst()
      }
    }
  }

  /** 清空（新一轮构建开始时调用）。 */
  fun clear() {
    synchronized(lock) {
      lines.clear()
    }
  }

  /** 当前保留的行数。 */
  fun size(): Int = synchronized(lock) { lines.size }

  /** 全部保留的行，按时间正序。 */
  fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

  /**
   * 尾部若干行，按时间正序。
   *
   * <p>构建失败的根因通常在最后：Gradle 先打印任务进度，再打印 `FAILURE:` 段。
   *
   * @param maxLines 最多返回多少行；`<= 0` 表示不限制
   */
  fun tail(maxLines: Int): List<String> = synchronized(lock) {
    if (maxLines <= 0 || lines.size <= maxLines) {
      return lines.toList()
    }
    lines.toList().subList(lines.size - maxLines, lines.size)
  }

  companion object {
    /**
     * 默认保留行数。
     *
     * 2000 行足以覆盖 Gradle 失败时的完整 `FAILURE:` 段（通常几十到几百行），
     * 又不会在内存里堆积整个构建日志。
     */
    const val DEFAULT_CAPACITY = 2000
  }
}

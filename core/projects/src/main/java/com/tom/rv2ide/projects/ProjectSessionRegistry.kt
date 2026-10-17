/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.projects

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 打开中的项目会话表。
 *
 * <p><b>S1 阶段的行为</b>：只允许一个会话存在。`activate()` 会关闭先前的会话，
 * 与改动前「一次只能打开一个项目」完全一致。多会话能力在 S2/S3 逐步放开，
 * 届时只需去掉 [MAX_SESSIONS] 的限制，本类的其余逻辑不变。
 *
 * <p><b>键的归一化</b>：用 canonical path，避免 `/a/b` 与 `/a/b/` 被当成两个项目。
 * 归一化失败时退回绝对路径——宁可多一个会话，也不要让打开项目直接失败。
 */
class ProjectSessionRegistry(private val maxSessions: Int = 1) {

  /** 当前会话上限。S1 为 1；S2 起由调用方提高。 */
  private val limit: Int = maxSessions.coerceAtLeast(1)

  private val sessions = ConcurrentHashMap<String, ProjectSession>()

  @Volatile private var activeId: String? = null

  /** 路径归一化。 */
  fun keyOf(dir: File): String {
    val canonical =
        try {
          dir.canonicalFile
        } catch (e: Exception) {
          dir.absoluteFile
        }
    return canonical.absolutePath.trimEnd(File.separatorChar)
  }

  /** 当前活跃会话；没有时返回 null。 */
  fun active(): ProjectSession? = activeId?.let { sessions[it] }

  /** 全部会话（按插入顺序不保证）。 */
  fun all(): List<ProjectSession> = sessions.values.toList()

  fun size(): Int = sessions.size

  /**
   * 打开（或切到）指定项目。
   *
   * <p>已存在时直接激活并返回既有会话（不重建项目模型）；不存在时创建。
   * 超过上限时按「最久未使用」淘汰——S1 的上限是 1，因此等价于关闭前一个。
   */
  fun activate(dir: File): ProjectSession {
    val key = keyOf(dir)
    sessions[key]?.let {
      it.lastAccessAt = System.nanoTime()
      activeId = key
      return it
    }

    // 超限：淘汰非活跃的最久未使用会话（S1 下就是唯一那个）。
    while (sessions.size >= limit) {
      val victim =
          sessions.entries
              .filter { it.key != key && it.key != activeId }
              .minByOrNull { it.value.lastAccessAt }
              ?: sessions.entries.firstOrNull { it.key != key }
              ?: break
      sessions.remove(victim.key)?.clear()
    }

    // 用归一化后的路径构造会话：与改动前 `openProject` 存 canonicalFile 的语义一致。
    val session = ProjectSession(File(key))
    session.lastAccessAt = System.nanoTime()
    sessions[key] = session
    activeId = key
    return session
  }

  /** 关闭并移除指定会话；若是活跃会话，活跃指针清空。 */
  fun close(dir: File) {
    val key = keyOf(dir)
    sessions.remove(key)?.clear()
    if (activeId == key) {
      activeId = null
    }
  }

  /** 关闭全部会话（编辑器退出时）。 */
  fun closeAll() {
    sessions.values.forEach { it.clear() }
    sessions.clear()
    activeId = null
  }
}

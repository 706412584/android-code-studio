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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 打开中的项目会话表。
 *
 * <p><b>上限</b>：由调用方通过构造参数决定。S1 用 1（单项目），S2 起
 * [com.tom.rv2ide.projects.internal.ProjectManagerImpl] 用 3；`activate()` 在超限时
 * 按 LRU 淘汰**非活跃**会话。上限为 1 时等价于「打开新项目即关闭旧项目」。
 *
 * <p><b>键的归一化</b>：用 canonical path，避免 `/a/b` 与 `/a/b/` 被当成两个项目。
 * 归一化失败时退回绝对路径——宁可多一个会话，也不要让打开项目直接失败。
 *
 * <p><b>顺序</b>：会话按**首次打开顺序**保存（[all] 返回该顺序），供标签栏稳定渲染；
 * 切换活跃会话不改变顺序。LRU 只影响淘汰，不影响展示顺序。
 */
class ProjectSessionRegistry(private val maxSessions: Int = 1) {

  /** 当前会话上限。 */
  private val limit: Int = maxSessions.coerceAtLeast(1)

  private val sessions = ConcurrentHashMap<String, ProjectSession>()

  /** 首次打开顺序；与 [sessions] 同步维护，保证标签顺序稳定。 */
  private val order = CopyOnWriteArrayList<String>()

  @Volatile private var activeKey: String? = null

  /**
   * LRU 逻辑时钟。用递增计数而非 `System.nanoTime()`，保证同一毫秒内的连续激活
   * 也能分出先后（否则「关闭活跃标签后回到上一个」可能取错）。
   */
  private val accessClock = AtomicLong(0)

  /** 当前活跃会话的 id；没有时为 null。 */
  val activeId: String?
    get() = activeKey

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
  fun active(): ProjectSession? = activeKey?.let { sessions[it] }

  /** 全部会话，按**首次打开顺序**返回。 */
  fun all(): List<ProjectSession> =
      order.mapNotNull { sessions[it] }

  fun size(): Int = sessions.size

  /** 指定路径的会话；未打开时返回 null。 */
  fun find(dir: File): ProjectSession? = sessions[keyOf(dir)]

  fun isOpen(dir: File): Boolean = sessions.containsKey(keyOf(dir))

  /**
   * 打开（或切到）指定项目。
   *
   * <p>已存在时直接激活并返回既有会话（不重建项目模型）；不存在时创建。
   * 超过上限时按「最久未使用」淘汰非活跃会话——上限为 1 时等价于关闭前一个。
   *
   * <p>注意：本方法在超限时会**关闭**别的会话，因此「只是想切到已打开项目」的调用方
   * 应先用 [find]/[isOpen] 判断，或直接用 [switchTo]（不会新建会话）。
   */
  fun activate(dir: File): ProjectSession {
    val key = keyOf(dir)
    sessions[key]?.let {
      touch(it, key)
      return it
    }

    // 超限：淘汰非活跃的最久未使用会话（上限为 1 时就是唯一那个）。
    while (sessions.size >= limit) {
      val victim =
          sessions.entries
              .filter { it.key != key && it.key != activeKey }
              .minByOrNull { it.value.lastAccessAt }
              ?: sessions.entries.firstOrNull { it.key != key }
              ?: break
      evict(victim.key)
    }

    // 用归一化后的路径构造会话：与改动前 `openProject` 存 canonicalFile 的语义一致。
    val session = ProjectSession(File(key))
    session.lastAccessAt = accessClock.incrementAndGet()
    sessions[key] = session
    if (!order.contains(key)) {
      order.add(key)
    }
    activeKey = key
    return session
  }

  /**
   * 切到已打开的会话。
   *
   * <p>与 [activate] 的区别：项目未打开时返回 null，**不会**新建会话，因此永远不会
   * 触发 LRU 淘汰。切换会刷新该会话的 LRU 时间戳。
   */
  fun switchTo(dir: File): ProjectSession? {
    val key = keyOf(dir)
    val session = sessions[key] ?: return null
    touch(session, key)
    return session
  }

  /**
   * 关闭并移除指定会话。
   *
   * <p>若关闭的是活跃会话，活跃指针不会留空（只要还有其它会话）：选择**最近使用**的
   * 剩余会话，与「关闭当前标签后回到上一个用过的标签」一致。没有剩余会话时才置 null。
   *
   * @return 被关闭的会话；本来就不存在时返回 null。
   */
  fun close(dir: File): ProjectSession? {
    val key = keyOf(dir)
    val removed = sessions.remove(key) ?: return null
    order.remove(key)
    removed.clear()
    if (activeKey == key) {
      activeKey =
          sessions.entries.maxByOrNull { it.value.lastAccessAt }?.key
    }
    return removed
  }

  /** 关闭全部会话（编辑器退出时）。 */
  fun closeAll() {
    sessions.values.forEach { it.clear() }
    sessions.clear()
    order.clear()
    activeKey = null
  }

  private fun touch(session: ProjectSession, key: String) {
    session.lastAccessAt = accessClock.incrementAndGet()
    activeKey = key
  }

  private fun evict(key: String) {
    sessions.remove(key)?.clear()
    order.remove(key)
    if (activeKey == key) {
      activeKey = null
    }
  }
}

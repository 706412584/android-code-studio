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

package com.tom.rv2ide.services.builder

import com.tom.rv2ide.projects.builder.BuildService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * 按项目路径索引的构建连接表。
 *
 * <p><b>键的归一化</b>：同一项目可能以 `/a/b`、`/a/b/`、含 `..` 或符号链接的路径
 * 出现。不归一化会让「同一个项目」开出两个 tooling 进程，撞上 `.gradle` 文件锁。
 * 归一化失败（路径不存在）时退回原始绝对路径——宁可多开一个连接，也不要因为
 * 归一化异常让构建直接失败。
 *
 * <p><b>所有权</b>：编辑器连接由 [GradleBuildService] 显式持有，注册表不回收它；
 * 后台连接由注册表创建并按 TTL/LRU 回收（P3 启用）。P2 阶段注册表只做查找与登记，
 * 不创建连接——因此跨项目构建仍会被工具层明确拒绝，行为安全。
 */
internal class BuildConnectionRegistry(
    private val clock: () -> Long = System::currentTimeMillis,
) {

  companion object {
    private val log = LoggerFactory.getLogger(BuildConnectionRegistry::class.java)

    /** 后台连接空闲多久后被回收（P3 生效）。 */
    internal const val IDLE_TTL_MS = 5 * 60 * 1000L

    /** 后台连接数量上限（P3 生效）。 */
    internal const val MAX_BACKGROUND = 2
  }

  internal enum class Owner {
    /** 由编辑器 Service 持有；不参与 TTL 回收。 */
    EDITOR,
    /** 后台项目构建；空闲可回收。 */
    BACKGROUND,
  }

  internal class Entry(
      val connection: BuildService,
      @Volatile var owner: Owner,
      @Volatile var lastUsedAtMs: Long,
  )

  private val entries = ConcurrentHashMap<String, Entry>()

  /** 路径归一化：canonical 去尾分隔符。失败时退回绝对路径。 */
  fun keyOf(projectPath: String): String {
    val raw = projectPath.trim()
    if (raw.isEmpty()) {
      return ""
    }
    val file = File(raw)
    val canonical =
        try {
          file.canonicalFile.absolutePath
        } catch (e: Exception) {
          log.warn("Unable to canonicalize path {}, using absolute path", raw, e)
          file.absolutePath
        }
    return canonical.trimEnd(File.separatorChar)
  }

  /** 登记或替换一个连接。 */
  fun register(projectPath: String, connection: BuildService, owner: Owner) {
    val key = keyOf(projectPath)
    if (key.isEmpty()) {
      return
    }
    entries[key] = Entry(connection, owner, clock())
  }

  /** 查找连接；不存在返回 null。命中会刷新使用时间。 */
  fun find(projectPath: String): BuildService? {
    val key = keyOf(projectPath)
    if (key.isEmpty()) {
      return null
    }
    val entry = entries[key] ?: return null
    entry.lastUsedAtMs = clock()
    return entry.connection
  }

  /** 编辑器连接（唯一）。 */
  fun editor(): BuildService? =
      entries.values.firstOrNull { it.owner == Owner.EDITOR }?.connection

  /** 把连接的所有权转给编辑器（编辑器 adopt 后台连接时使用）。 */
  fun promoteToEditor(projectPath: String): BuildService? {
    val key = keyOf(projectPath)
    val entry = entries[key] ?: return null
    entry.owner = Owner.EDITOR
    entry.lastUsedAtMs = clock()
    return entry.connection
  }

  /** 编辑器关闭项目后，连接降级为后台（TTL 起算）。 */
  fun demoteToBackground(projectPath: String) {
    val entry = entries[keyOf(projectPath)] ?: return
    entry.owner = Owner.BACKGROUND
    entry.lastUsedAtMs = clock()
  }

  fun remove(projectPath: String) {
    entries.remove(keyOf(projectPath))
  }

  fun clear() {
    entries.clear()
  }

  internal fun size(): Int = entries.size

  /**
   * 回收可关闭的连接：空闲超过 TTL 的后台连接，或超过数量上限时最旧的。
   *
   * @param isBusy 正在构建的连接绝不回收——关掉会把正在跑的构建打断
   * @param close 关闭动作；由调用方决定（连接层是 `close()`）
   * @return 被回收的路径
   */
  internal fun evictIdle(
      isBusy: (BuildService) -> Boolean,
      close: (BuildService) -> Unit,
  ): List<String> {
    val now = clock()
    val evicted = mutableListOf<String>()

    // 1) TTL：只回收后台且空闲的连接。
    val idle =
        entries.entries
            .filter { (_, entry) ->
              entry.owner == Owner.BACKGROUND &&
                  now - entry.lastUsedAtMs >= IDLE_TTL_MS &&
                  !isBusy(entry.connection)
            }
            .map { it.key }
    for (key in idle) {
      entries.remove(key)?.let { close(it.connection) }
      evicted += key
    }

    // 2) 数量上限：仍超限时按 lastUsedAt 最旧的先回收。
    val background =
        entries.entries
            .filter { (_, entry) -> entry.owner == Owner.BACKGROUND && !isBusy(entry.connection) }
            .sortedBy { (_, entry) -> entry.lastUsedAtMs }
    var overflow = background.size - MAX_BACKGROUND
    for ((key, entry) in background) {
      if (overflow <= 0) {
        break
      }
      entries.remove(key)?.let { close(it.connection) }
      evicted += key
      overflow--
    }
    return evicted
  }
}

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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.a11y

import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 短生命周期节点注册表。
 *
 * AccessibilityNodeInfo 跨调用没有稳定标识：每次 dump 都会重新枚举出新对象，
 * 但 Agent 预期几秒后还能用 id 引用同一个节点（如「点 a3f2」）。这里用短 id 缓存
 * 节点引用，[TTL_MS] 过期或服务销毁时清空。
 *
 * <p><b>为什么 id 会过期</b>：节点引用持有的是窗口快照里的对象；界面一变
 * （Activity 切换、列表滚动），旧节点要么失效要么指向错误的位置。60 秒的窗口
 * 足够「dump → 看结果 → 点击」这个自然节奏，又不至于长期持有失效引用。
 *
 * <p>移植自 Aharou 的 `NodeRegistry`（原样保留逻辑，仅改包名）。
 */
class NodeRegistry {

  private data class Entry(val node: AccessibilityNodeInfo, val createdAt: Long)

  private val map = ConcurrentHashMap<String, Entry>()
  private val seq = AtomicLong(0)

  fun put(node: AccessibilityNodeInfo): String {
    evictExpired()
    val id = nextId(seq.incrementAndGet())
    map[id] = Entry(node, nowMs())
    return id
  }

  fun get(id: String): AccessibilityNodeInfo? {
    val entry = map[id] ?: return null
    if (isExpired(entry.createdAt, nowMs())) {
      map.remove(id)
      return null
    }
    return entry.node
  }

  fun clear() {
    map.clear()
  }

  private fun evictExpired() {
    val now = nowMs()
    val iterator = map.entries.iterator()
    while (iterator.hasNext()) {
      if (isExpired(iterator.next().value.createdAt, now)) iterator.remove()
    }
  }

  /**
   * 计时基准。
   *
   * <p>用 [android.os.SystemClock.elapsedRealtime]（开机以来毫秒，单调）而非
   * `currentTimeMillis`（墙钟）：设备 NTP 对时回拨会让 `now - createdAt` 变负、
   * 节点永不判定过期；向前跳则节点集体立即失效。仓库既有惯例同此
   * （`ChatRenderStore`：时长用 elapsedRealtime、墙钟用 currentTimeMillis）。
   */
  private fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()

  companion object {
    const val TTL_MS = 60_000L

    /**
     * 条目是否已过期。
     *
     * <p>抽成静态纯函数是为了**可 JVM 单测**：本类其余方法都要 [AccessibilityNodeInfo]，
     * 而仓库惯例是不 mock Android 类（见 `PhoneClearDataToolTest` 把校验逻辑抽成静态方法）。
     * 把判定独立出来，测试就能覆盖「TTL 边界」这个最容易写错的点。
     */
    @JvmStatic
    fun isExpired(createdAtMs: Long, nowMs: Long): Boolean = nowMs - createdAtMs > TTL_MS

    /**
     * 生成短 id（base36，4 位补零）。
     *
     * <p>同样抽成静态：id 的可读性值得钉住（Agent 要复述「点 a3f2」，不能是一长串数字）。
     * 掩码 0xFFFFF 把序号限制在 20 位，避免 id 无限增长。
     */
    @JvmStatic
    fun nextId(sequence: Long): String =
        java.lang.Long.toString(sequence and 0xFFFFFL, 36).padStart(4, '0')
  }
}

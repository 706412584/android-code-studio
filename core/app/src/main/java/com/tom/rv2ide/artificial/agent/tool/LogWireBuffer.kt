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

package com.tom.rv2ide.artificial.agent.tool

import io.github.mohammedbaqernull.logger.model.LogEntry

/**
 * LogWire 日志的按包名有界缓冲。
 *
 * <p><b>为什么必须有缓冲</b>：[io.github.mohammedbaqernull.logger.service.LogReceiverService]
 * 只做转发、不存历史（`sendLog` 里直接 `listeners.forEach`）。日志面板能显示是因为它
 * 自己订阅时就开始收；AI 工具是**事后**查询，不自己攒一份就什么都读不到——
 * 「面板里能看到、AI 却读不到」正是缺这一层的表现。
 *
 * <p><b>为什么按包名分组</b>：一台设备上可能同时有多个被调试应用在跑。不分组的单一
 * 环形缓冲会让活跃应用把安静应用的日志挤出去——查询时明明只关心 A，却因为 B 话多
 * 而看不到 A。
 *
 * <p><b>纯类、无 Android 依赖</b>：只持有 [LogEntry]（纯数据）与计数，
 * 因此可在 JVM 上直接单测，不需要 Robolectric。
 *
 * <p><b>线程安全</b>：写入来自 binder 线程（`sendLog` 回调），读取来自 agent 循环线程。
 * 用一把锁保护全部状态——缓冲操作都是 O(1) 或 O(查询条数)，锁竞争可忽略。
 */
class LogWireBuffer(
    /** 每个包名各自保留的最大条数。 */
    private val perPackageLimit: Int = DEFAULT_PER_PACKAGE_LIMIT,
) {

  private val lock = Any()

  /** 包名 → 该包的日志（时间序，末尾最新）。 */
  private val byPackage = LinkedHashMap<String, ArrayDeque<LogEntry>>()

  /** 写入一条。超出上限时丢弃最旧的一条。 */
  fun add(entry: LogEntry) {
    val pkg = entry.packageName ?: return
    synchronized(lock) {
      val queue = byPackage.getOrPut(pkg) { ArrayDeque() }
      queue.addLast(entry)
      while (queue.size > perPackageLimit) {
        queue.removeFirst()
      }
    }
  }

  /**
   * 查询快照。
   *
   * @param packageName 目标包名；null 或空表示全部包
   * @param limit 最多返回多少条（取**最新**的 limit 条）；<=0 表示不限
   * @return 按时间序排列的条目（末尾最新）
   */
  fun snapshot(packageName: String?, limit: Int): List<LogEntry> {
    synchronized(lock) {
      val out = ArrayList<LogEntry>()
      if (packageName.isNullOrEmpty()) {
        // 全部包：合并后按时间戳排序，保持「末尾最新」的语义。
        val all = ArrayList<LogEntry>()
        for (queue in byPackage.values) {
          all.addAll(queue)
        }
        all.sortBy { it.timestamp }
        appendTail(all, limit, out)
      } else {
        val queue = byPackage[packageName] ?: return emptyList()
        appendTail(queue, limit, out)
      }
      return out
    }
  }

  /** 已知包名（按首次出现顺序）。 */
  fun packages(): List<String> = synchronized(lock) { ArrayList(byPackage.keys) }

  /** 某包当前条数。 */
  fun count(packageName: String): Int =
      synchronized(lock) { byPackage[packageName]?.size ?: 0 }

  /** 清空（测试与诊断用）。 */
  fun clear() = synchronized(lock) { byPackage.clear() }

  /** 取 [source] 的末尾 [limit] 条（limit<=0 取全部）追加到 [out]。 */
  private fun appendTail(source: Collection<LogEntry>, limit: Int, out: MutableList<LogEntry>) {
    if (limit <= 0 || source.size <= limit) {
      out.addAll(source)
      return
    }
    val skip = source.size - limit
    var index = 0
    for (entry in source) {
      if (index++ >= skip) {
        out.add(entry)
      }
    }
  }

  companion object {
    /**
     * 每包默认保留条数。
     *
     * <p>2000 条足以覆盖一次调试会话（一个正常 app 的 brief 格式日志每条约 100~200 字节，
     * 2000 条约 200~400KB/包），同时把多应用场景的内存占用控制在可忽略的量级。
     */
    const val DEFAULT_PER_PACKAGE_LIMIT = 2000
  }
}

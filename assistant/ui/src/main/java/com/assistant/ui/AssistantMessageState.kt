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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.assistant.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.assistant.ui.model.AcsMessageMapper
import com.assistant.ui.model.AgentUIMessage
import com.assistant.ui.model.MessageTimeline

/**
 * 把 [MessageTimeline] 的「可变列表 + 变更通知」语义桥接成 Compose 需要的**不可变快照**。
 *
 * <p><b>为什么需要这一层</b>：时间线持有 `MutableList<Item>`，靠「数据集变了」通知驱动消费方；
 * Compose 没有「第 N 项变了」这种输入，它要的是一份不可变列表 + 稳定的 `key`。
 * 两者之间若直接用「通知到达就整表 `map` 一遍」，流式输出期间每个增量都会重建整张表——
 * 条目数越多越慢，且每条消息都变成新对象，`LazyColumn` 会把整屏全部重组，直接掉帧。
 *
 * <p><b>三条关键设计</b>：
 *
 * <p>1. **通知只当"脏"信号，不按位置增量更新**。时间线通知不带位置，且
 * [AcsMessageMapper] 会把 `ToolGroup` / `DiffGroup` 摊平，两者的下标**不对应**。
 * 观察者只负责触发一次合并后的重算。
 *
 * <p>2. **按条目记忆化，未变的条目复用同一个 [AgentUIMessage] 实例**。
 * [MessageTimeline.Item] 是不可变 data class，内容没变时时间线保留的是**同一个实例**，
 * `==` 走引用相等即返回。因此逐条判断"这一项变了吗"是 O(1)，只有真正变了的条目才重跑映射。
 *
 * <p>3. **同一帧内的多次通知合并成一次重算**。流式期间 `appendTo` / `update` 会连续发通知，
 * 逐个重算没有意义（中间态根本不会被渲染到）。用主线程 Handler 合并，令牌级增量最终
 * 每帧最多一次重算。
 *
 * <p><b>线程约定</b>：时间线的所有改动都发生在主线程，因此重算也在主线程执行。
 * 若在非主线程调用 [refreshNow]，会改为投递到主线程执行。
 */
internal class AssistantMessageState(private val timeline: MessageTimeline) {

  /**
   * 一个条目的记忆化结果。
   *
   * @param item 上次映射时的原始条目；与当前条目 `==` 时说明内容未变
   * @param messages 该条目映射出的消息。**是列表而非单条**——`ToolGroup` / `DiffGroup`
   *   会被摊平成多条，只存单条会丢掉组内除首项外的全部内容。
   */
  private class Entry(val item: MessageTimeline.Item, val messages: List<AgentUIMessage>)

  /** 按条目 id 记忆化。每次重算重建，顺带淘汰已删除条目的缓存。 */
  private var memo = HashMap<Long, Entry>()

  private val _messages = mutableStateOf<List<AgentUIMessage>>(emptyList())

  /** 供 Compose 读取的快照。赋值只在内容真的变化时发生，避免无谓重组。 */
  val messages: State<List<AgentUIMessage>>
    get() = _messages

  private val handler = Handler(Looper.getMainLooper())

  private val refreshTask = Runnable {
    scheduled = false
    refresh()
  }

  private var scheduled = false
  private var registered = true

  /** 时间线变更一律只置脏（见类注释第 1 条）。 */
  private val listener = MessageTimeline.ChangeListener { schedule() }

  init {
    timeline.addChangeListener(listener)
    // 面板可能在已有消息之后才接上，因此构造时先同步一次，避免第一帧是空列表。
    refresh()
  }

  private fun schedule() {
    if (scheduled) {
      return
    }
    scheduled = true
    // post 本身线程安全，非主线程调用也会被投递到主线程执行。
    handler.post(refreshTask)
  }

  /**
   * 立刻重算并发布快照，跳过帧合并。
   *
   * <p>用于「清空 / 切换会话」这类必须马上反映、且不在流式高频路径上的改动——
   * 合并会带来一帧的旧内容残留，切会话时那一帧会显示上一个会话的消息。
   */
  fun refreshNow() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      schedule()
      return
    }
    handler.removeCallbacks(refreshTask)
    scheduled = false
    refresh()
  }

  private fun refresh() {
    val items = timeline.snapshot()
    val next = HashMap<Long, Entry>(items.size * 2)
    val out = ArrayList<AgentUIMessage>(items.size)
    for (item in items) {
      val cached = memo[item.id]
      // 未变则复用同一 AgentUIMessage 实例：下游 LazyColumn 靠引用相等跳过重组。
      val entry =
          if (cached != null && cached.item == item) {
            cached
          } else {
            Entry(item, AcsMessageMapper.toUiMessages(listOf(item)))
          }
      next[item.id] = entry
      out.addAll(entry.messages)
    }
    memo = next
    // 逐元素比较：未变元素是同一实例，data class 的 equals 会先做引用判断。
    // 不做这一步则每次重算都会发出一个新 List 实例，触发一次无谓的全量重组。
    if (out != _messages.value) {
      _messages.value = out
    }
  }

  /**
   * 注销观察者。
   *
   * <p>由宿主在视图销毁时调用。不注销的话，时间线会一直持有本对象，而本对象持有
   * `mutableStateOf` 与消息列表——面板关掉后这些内存不会释放。重复调用是安全的。
   */
  fun dispose() {
    if (!registered) {
      return
    }
    registered = false
    handler.removeCallbacks(refreshTask)
    scheduled = false
    timeline.removeChangeListener(listener)
  }
}

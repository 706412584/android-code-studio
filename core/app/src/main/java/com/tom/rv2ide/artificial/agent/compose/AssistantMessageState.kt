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

package com.tom.rv2ide.artificial.agent.compose

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.recyclerview.widget.RecyclerView
import com.tom.rv2ide.adapters.AssistantMessageAdapter
import com.tom.rv2ide.artificial.agent.compose.model.AcsMessageMapper
import com.tom.rv2ide.artificial.agent.compose.model.AgentUIMessage

/**
 * 把 [AssistantMessageAdapter] 的「可变列表 + 增量通知」语义桥接成 Compose 需要的**不可变快照**。
 *
 * <p><b>为什么需要这一层</b>：适配器持有 `MutableList<Item>`，靠 `notifyItemChanged` 等增量通知驱动
 * RecyclerView；Compose 没有「第 N 项变了」这种输入，它要的是一份不可变列表 + 稳定的 `key`。
 * 两者之间若直接用「通知到达就整表 `map` 一遍」，流式输出期间每个增量都会重建整张表——
 * 条目数越多越慢，且每条消息都变成新对象，`LazyColumn` 会把整屏全部重组，直接掉帧。
 *
 * <p><b>三条关键设计</b>：
 *
 * <p>1. **通知只当"脏"信号，不按位置增量更新**。适配器的 `positionStart` 是**分组后**的下标，
 * 而 [AcsMessageMapper] 会把 `ToolGroup` / `DiffGroup` 摊平，两者下标**不对应**。照位置做
 * `insert/remove` 会插错位置、删错条目——这类错位不会报错，只会让对话串行。因此观察者只负责
 * 触发一次合并后的重算，位置信息一概不用。
 *
 * <p>2. **按条目记忆化，未变的条目复用同一个 [AgentUIMessage] 实例**。[AssistantMessageAdapter.Item]
 * 是不可变 data class，内容没变时适配器保留的是**同一个实例**，`==` 走引用相等即返回。
 * 因此逐条判断"这一项变了吗"是 O(1)，只有真正变了的条目才重跑映射（映射才是贵的：
 * 序列化 diff、拼 JSON、分配字符串）。未变条目复用同一实例 → `LazyColumn` 按 `key` + 引用相等
 * 直接跳过，不重组。
 *
 * <p>3. **同一帧内的多次通知合并成一次重算**。流式期间 `appendTo` / `update` 会连续发通知，
 * 逐个重算没有意义（中间态根本不会被渲染到）。用主线程 Handler 合并，令牌级增量最终
 * 每帧最多一次重算。
 *
 * <p><b>线程约定</b>：适配器的所有改动都发生在主线程（视图层事件与 `Dispatchers.Main` 回填），
 * 因此重算也在主线程执行。若在非主线程调用 [refreshNow]，会改为投递到主线程执行，
 * 而不是就地读取——`mutableStateOf` 在非主线程赋值需要额外快照处理，投递更简单且不会错。
 */
internal class AssistantMessageState(private val adapter: AssistantMessageAdapter) {

  /**
   * 一个条目的记忆化结果。
   *
   * @param item 上次映射时的原始条目；与当前条目 `==` 时说明内容未变
   * @param messages 该条目映射出的消息。**是列表而非单条**——`ToolGroup` / `DiffGroup`
   *   会被摊平成多条，只存单条会丢掉组内除首项外的全部内容。
   */
  private class Entry(val item: AssistantMessageAdapter.Item, val messages: List<AgentUIMessage>)

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

  /**
   * 观察适配器的**全部**通知类型，一律只置脏。
   *
   * <p>这里刻意不重写 `onItemRangeChanged(positionStart, itemCount)`：RecyclerView 新版把它标为
   * 过时，实际分发走带 payload 的重载。只重写非过时的重载即可覆盖全部通知，且不产生告警。
   */
  private val observer =
      object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = schedule()

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = schedule()

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = schedule()

        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = schedule()

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = schedule()
      }

  init {
    adapter.registerAdapterDataObserver(observer)
    // 面板可能在已有消息之后才接上（例如从中途切到 Compose 渲染路径），
    // 因此构造时先同步一次，避免第一帧是空列表。
    refresh()
  }

  private fun schedule() {
    if (scheduled) {
      return
    }
    scheduled = true
    // post 本身线程安全，非主线程调用也会被投递到主线程执行；因此这里不需要分支判断
    // 当前线程——分支只会重复同一个调用。
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
    val items = adapter.snapshot()
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
    // 逐元素比较：未变元素是同一实例，data class 的 equals 会先做引用判断，代价近似 O(条目数)
    // 的轻量扫描；不做这一步则每次重算都会发出一个新 List 实例，触发一次无谓的全量重组。
    if (out != _messages.value) {
      _messages.value = out
    }
  }

  /**
   * 注销观察者。
   *
   * <p>由宿主在视图销毁时调用。不注销的话，适配器会一直持有本对象，而本对象持有
   * `mutableStateOf` 与消息列表——面板关掉后这些内存不会释放。重复调用是安全的。
   */
  fun dispose() {
    if (!registered) {
      return
    }
    registered = false
    handler.removeCallbacks(refreshTask)
    scheduled = false
    adapter.unregisterAdapterDataObserver(observer)
  }
}

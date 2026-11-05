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

package com.tom.rv2ide.artificial.agent

import com.tom.rv2ide.adapters.AssistantMessageAdapter
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 渲染状态的接管与让出。
 *
 * <p><b>为什么值得测</b>：真机上侧栏仍存活时进全屏，两个视图曾共用同一组渲染指针、
 * 却各持一个适配器——指针里的 id 只对其中一个成立，推理流式时双方轮流覆盖
 * `lastThinkingId`，一段推理被拆成一词一块。这里钉死「同时存活 → 各自独立、
 * 先后重建 → 接管同一份」两条规则。
 */
class ChatRenderStoreTest {

  private val viewA = Any()
  private val viewB = Any()

  private fun acquire(slot: ChatRenderStore.RenderSlot, view: Any, active: String? = "c1") =
      ChatRenderStore.acquire(slot, view, active) { AssistantMessageAdapter() }

  @Test
  fun 同时存活的两个视图各持一份状态() {
    val slot = ChatRenderStore.RenderSlot()
    val a = acquire(slot, viewA)
    val b = acquire(slot, viewB)

    assertNotSame(a, b)
    assertNotSame(a.adapter, b.adapter)
    // 指针按状态隔离：A 写的推理块 id 不会被 B 读到。
    a.sessionUi.getOrPut("c1") { SessionUiState() }.lastThinkingId = 7L
    assertSame(null, b.sessionUi["c1"]?.lastThinkingId)
  }

  @Test
  fun 显示会话按视图隔离() {
    val slot = ChatRenderStore.RenderSlot()
    val a = acquire(slot, viewA)
    val b = acquire(slot, viewB)

    b.displayedConversationId = "c2"
    assertSame(null, a.displayedConversationId)
  }

  @Test
  fun 重建时接管上一个视图的状态() {
    val slot = ChatRenderStore.RenderSlot()
    val a = acquire(slot, viewA)
    a.conversationId = "c1"
    ChatRenderStore.release(slot, a, successor = null)

    val rebuilt = acquire(slot, viewB)
    assertSame(a, rebuilt)
  }

  @Test
  fun 适配器属于别的会话时不接管() {
    val slot = ChatRenderStore.RenderSlot()
    val a = acquire(slot, viewA)
    a.conversationId = "c1"
    ChatRenderStore.release(slot, a, successor = null)

    assertNotSame(a, acquire(slot, viewB, active = "c2"))
  }

  @Test
  fun 后到视图销毁不污染槽位() {
    // 侧栏 A 先在，全屏 B 后到并销毁：A 的状态仍是槽位里那份，A 重建时接管的是持续更新的列表。
    val slot = ChatRenderStore.RenderSlot()
    val a = acquire(slot, viewA)
    val b = acquire(slot, viewB)
    ChatRenderStore.release(slot, b, successor = a)

    assertSame(a, slot.state)
  }

  @Test
  fun 先到视图销毁时槽位交给仍存活的视图() {
    // 侧栏 A 先销毁、全屏 B 仍在：槽位改寄存 B 的状态，A 那份（已停止收事件）不再被接管。
    val slot = ChatRenderStore.RenderSlot()
    val a = acquire(slot, viewA)
    val b = acquire(slot, viewB)
    ChatRenderStore.release(slot, a, successor = b)

    assertSame(b, slot.state)
    // 再来一个视图：B 仍被占用，新视图新建而不是接管 A 的旧列表。
    val c = acquire(slot, Any())
    assertNotSame(a, c)
    assertNotSame(b, c)
  }
}

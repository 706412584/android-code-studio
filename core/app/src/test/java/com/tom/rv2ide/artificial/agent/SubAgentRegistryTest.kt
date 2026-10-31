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

package com.tom.rv2ide.artificial.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行中子代理注册表的查询与归属过滤。
 *
 * <p><b>为什么值得单独测</b>：注册表是 `send_message` 的 `agent:&lt;name&gt;` 唯一的寻址依据，
 * 而它的键（名字）是**会话作用域**的——每个父会话各自可能有一个叫 `reviewer` 的子代理。
 * 过滤写错不会报错，只会表现为「A 会话的消息发到了 B 会话的子代理上」，
 * 而那等于向另一个会话的上下文注入指令，是实打实的串扰。这里把它钉死。
 *
 * <p>用 null 会话构造「已停止」条目：{@code canInject()} 据此为 false，
 * 正好覆盖「注册表保留条目、但不再可注入」这个第 3 期依赖的关键状态。
 */
class SubAgentRegistryTest {

  private fun registryWith(vararg entries: Triple<String, String, String?>): SubAgentRegistry {
    val registry = SubAgentRegistry()
    entries.forEach { (name, parentId, conversationId) ->
      // session/cancellation 传 null：本测试只验证表本身的语义，不驱动真实会话。
      registry.register(name, conversationId ?: "", parentId, name, null, null)
    }
    return registry
  }

  @Test
  fun findsRegisteredAgent() {
    val registry = registryWith(Triple("reviewer", "conv-a", "sub-1"))
    val entry = registry.find("conv-a", "reviewer")
    assertNotNull(entry)
    assertEquals("sub-1", entry!!.getConversationId())
    assertEquals("conv-a", entry.getParentId())
  }

  @Test
  fun unknownNameReturnsNull() {
    assertNull(registryWith(Triple("reviewer", "conv-a", "sub-1")).find("conv-a", "nope"))
    assertNull(SubAgentRegistry().find("conv-a", null))
  }

  @Test
  fun registerWithoutNameIsIgnored() {
    val registry = SubAgentRegistry()
    registry.register("", "sub-1", "conv-a", "", null, null)
    assertTrue(registry.list(null).isEmpty())
  }

  /** 同名覆盖：父会话反复启用同一个角色名时，最新那次成为可寻址对象。 */
  @Test
  fun sameNameOverwrites() {
    val registry = SubAgentRegistry()
    registry.register("reviewer", "sub-1", "conv-a", "reviewer", null, null)
    registry.register("reviewer", "sub-2", "conv-a", "reviewer", null, null)

    assertEquals("同名只应有一个条目", 1, registry.list(null).size)
    assertEquals("sub-2", registry.find("conv-a", "reviewer")!!.getConversationId())
  }

  /**
   * 两个会话各自有同名子代理时**互不覆盖**。
   *
   * <p>这条是回归测试：注册表最初只用名字做键，于是 B 会话的 `reviewer` 会把 A 会话
   * 的顶掉——A 随后发 `agent:reviewer` 会命中 B 的那个（甚至把它从 B 的历史里唤醒）。
   * 键必须含父会话 id。
   */
  @Test
  fun sameNameInDifferentSessionsDoesNotCollide() {
    val registry = SubAgentRegistry()
    registry.register("reviewer", "sub-a", "conv-a", "reviewer", null, null)
    registry.register("reviewer", "sub-b", "conv-b", "reviewer", null, null)

    assertEquals("两个会话的同名子代理都应存在", 2, registry.list(null).size)
    assertEquals("sub-a", registry.find("conv-a", "reviewer")!!.getConversationId())
    assertEquals("sub-b", registry.find("conv-b", "reviewer")!!.getConversationId())
  }

  @Test
  fun markStoppedKeepsEntryForResume() {
    val registry = registryWith(Triple("reviewer", "conv-a", "sub-1"))
    registry.markStopped("conv-a", "reviewer")

    // 条目必须还在：第 3 期靠它从会话续跑。删掉的话唤醒就无从知道该读哪个会话。
    val entry = registry.find("conv-a", "reviewer")
    assertNotNull("停止后条目应保留（唤醒要用它的会话 id）", entry)
    assertFalse(entry!!.isRunning())
    assertFalse(entry.canInject())
    assertEquals("停止后仍要能拿到会话 id", "sub-1", entry.getConversationId())
  }

  @Test
  fun markStoppedOnUnknownNameIsNoOp() {
    registryWith(Triple("reviewer", "conv-a", "sub-1")).markStopped("conv-a", "nope")
  }

  @Test
  fun unregisterRemovesEntry() {
    val registry = registryWith(Triple("reviewer", "conv-a", "sub-1"))
    registry.unregister("conv-a", "reviewer")
    assertNull(registry.find("conv-a", "reviewer"))
  }

  // ── 归属过滤 ──────────────────────────────────────────────────────────

  @Test
  fun listFiltersByParent() {
    val registry =
        registryWith(
            Triple("reviewer", "conv-a", "sub-a1"),
            Triple("coder", "conv-a", "sub-a2"),
            Triple("reviewer", "conv-b", "sub-b1"))

    assertEquals("不带过滤应列出全部", 3, registry.list(null).size)
    assertEquals("空串等同于不过滤", 3, registry.list("").size)

    val forA = registry.list("conv-a")
    assertEquals(2, forA.size)
    assertTrue(forA.all { it.getParentId() == "conv-a" })

    val forB = registry.list("conv-b")
    assertEquals(1, forB.size)
    assertEquals("sub-b1", forB[0].getConversationId())
  }

  @Test
  fun listForUnknownParentIsEmpty() {
    assertTrue(registryWith(Triple("r", "conv-a", "s1")).list("conv-z").isEmpty())
  }

  @Test
  fun listReturnsSnapshotNotLiveView() {
    val registry = registryWith(Triple("r", "conv-a", "s1"))
    val snapshot = registry.list(null)
    registry.register("r2", "sub-2", "conv-a", "r2", null, null)
    assertEquals("快照不应随后续注册变化", 1, snapshot.size)
  }
}

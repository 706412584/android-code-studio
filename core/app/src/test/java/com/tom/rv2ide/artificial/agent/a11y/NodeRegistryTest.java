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

package com.tom.rv2ide.artificial.agent.a11y;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * 无障碍节点注册表的回归测试（Kotlin 类，从 Java 调用）。
 *
 * <p><b>为什么只测这两个静态方法</b>：注册表的其余部分要 {@code AccessibilityNodeInfo}
 * （Android 类，JVM 上拿不到），而仓库惯例是不 mock Android 类——把判定与 id 生成
 * 抽成纯函数后，这两个最容易写错的点就能在 JVM 上钉住。节点存取本身由实机验证覆盖。
 *
 * <p><b>TTL 为什么值得测</b>：它决定了「dump 完过一会儿再点」是否可用。判反了
 * （比如用 {@code >=} 而非 {@code >}，或算错方向）会让节点要么过早失效、
 * 要么永不过期——前者让正常操作随机失败，后者持有失效引用点到错误的位置。
 */
public final class NodeRegistryTest {

  // ---------------------------------------------------------------------------
  // TTL 判定
  // ---------------------------------------------------------------------------

  @Test
  public void freshEntryIsNotExpired() {
    assertFalse(NodeRegistry.isExpired(1_000L, 1_000L));
    assertFalse("刚创建不久不应过期", NodeRegistry.isExpired(1_000L, 30_000L));
  }

  @Test
  public void entryExactlyAtTtlIsStillValid() {
    // 边界：恰好等于 TTL 时**仍有效**（判定用 >，不是 >=）。
    // 这个边界不是随意定的：TTL 是「至少可用这么久」的承诺。
    long created = 5_000L;
    assertFalse(
        "恰好到 TTL 边界应仍有效",
        NodeRegistry.isExpired(created, created + NodeRegistry.TTL_MS));
  }

  @Test
  public void entryPastTtlIsExpired() {
    long created = 5_000L;
    assertTrue(
        "超过 TTL 应过期", NodeRegistry.isExpired(created, created + NodeRegistry.TTL_MS + 1));
  }

  @Test
  public void ttlIsOneMinute() {
    // 钉住常量本身：改小会让「dump 后思考几秒再点」失败，改大则持有失效引用更久。
    assertEquals(60_000L, NodeRegistry.TTL_MS);
  }

  // ---------------------------------------------------------------------------
  // id 生成
  // ---------------------------------------------------------------------------

  @Test
  public void idIsPaddedBase36() {
    // 可读性：Agent 要复述「点 a3f2」。序号 1 应得到补零后的 "0001" 而非 "1"。
    assertEquals("0001", NodeRegistry.nextId(1L));
    assertEquals("000a", NodeRegistry.nextId(10L));
  }

  @Test
  public void idIsFourCharsWithinMask() {
    // 掩码内最大序号（0xFFFFF = 1048575）应恰好 4 位 base36。
    assertEquals(4, NodeRegistry.nextId(0xFFFFFL).length());
    assertTrue(NodeRegistry.nextId(0xFFFFFL).length() <= 4);
  }

  @Test
  public void idWrapsAroundMask() {
    // 序号超过掩码后回绕——保证 id 长度有界（不会随会话时长无限增长）。
    assertEquals(
        "回绕后应与掩码内同余", NodeRegistry.nextId(1L), NodeRegistry.nextId(0x100000L + 1L));
  }

  @Test
  public void consecutiveIdsAreUnique() {
    Set<String> seen = new HashSet<>();
    for (long i = 1; i <= 1_000; i++) {
      assertTrue("id 不应重复: " + i, seen.add(NodeRegistry.nextId(i)));
    }
    assertEquals(1_000, seen.size());
  }

  @Test
  public void differentSequencesGiveDifferentIds() {
    assertNotEquals(NodeRegistry.nextId(1L), NodeRegistry.nextId(2L));
    assertNotEquals(NodeRegistry.nextId(100L), NodeRegistry.nextId(101L));
  }
}

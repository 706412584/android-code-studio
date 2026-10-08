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

package com.tom.rv2ide.artificial.agent.compose;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.tom.rv2ide.artificial.agent.compose.components.independent.PastedText;
import com.tom.rv2ide.artificial.agent.compose.components.independent.PastedTextBufferKt;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * PastedTextBuffer 纯逻辑部分的回归测试（移植自 Aharou 的同源逻辑）。
 *
 * <p>覆盖移植时最容易破坏的三个性质：
 * <ol>
 *   <li><b>单遍展开</b>——粘贴内容本身长得像标记时不递归；
 *   <li><b>未知 id 原样保留</b>——手打的假标记不阻断发送；
 *   <li><b>折叠只由插入段触发</b>——普通按键不会反复折断已有内容。
 * </ol>
 */
public class PastedTextBufferTest {

  @Test
  public void expand_replacesKnownMarkerOnce() {
    Set<Integer> consumed = new HashSet<>();
    // 单遍性质：缓冲文本自身包含 "[Pasted#2]" 字面量，也不能被再展开
    String bufferText = "inner [Pasted#2] literal";
    String text = "a [Pasted#1] b";
    java.util.List<PastedText> buffer =
        Collections.singletonList(new PastedText(1, bufferText));

    var result = PastedTextBufferKt.expandPastePlaceholders(text, buffer);

    assertEquals("a " + bufferText + " b", result.getFirst());
    assertEquals(Collections.singleton(1), result.getSecond());
  }

  @Test
  public void expand_keepsUnknownMarkersVerbatim() {
    var result =
        PastedTextBufferKt.expandPastePlaceholders(
            "x [Pasted#99] y", Collections.singletonList(new PastedText(1, "one")));
    assertEquals("x [Pasted#99] y", result.getFirst());
    assertTrue(result.getSecond().isEmpty());
  }

  @Test
  public void expand_acceptsLowercaseMarker() {
    var result =
        PastedTextBufferKt.expandPastePlaceholders(
            "see [pasted#7] now", Collections.singletonList(new PastedText(7, "LOW")));
    assertEquals("see LOW now", result.getFirst());
  }

  @Test
  public void expand_noBufferReturnsInputUnchanged() {
    var result = PastedTextBufferKt.expandPastePlaceholders("hello [Pasted#1]", Collections.emptyList());
    assertEquals("hello [Pasted#1]", result.getFirst());
    assertTrue(result.getSecond().isEmpty());
  }

  @Test
  public void pastedIdsIn_findsReferencedIds() {
    Set<Integer> ids = PastedTextBufferKt.pastedIdsIn("a [Pasted#1] b [pasted#3]");
    assertEquals(new HashSet<>(java.util.Arrays.asList(1, 3)), ids);
  }

  @Test
  public void placeholderFormat_roundTrips() {
    assertEquals("[Pasted#12]", PastedText.Companion.placeholderFor(12));
    assertEquals(12, new PastedText(12, "x").getId());
  }
}

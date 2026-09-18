/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
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

package com.tom.rv2ide.artificial.agent;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 锁定上下文后缀的解析。
 *
 * <p>这里最容易出错的是 {@link ContextSizeParser#stripSuffix}：剥多了会把真实模型名
 * 改坏（某些网关的模型名确实带方括号），剥少了会把 {@code [1m]} 发给服务端导致 404。
 * 因此「只在能解析成大小时才剥离」这条规则要有测试守着。
 */
public class ContextSizeParserTest {

  @Test
  public void parsesMillionSuffix() {
    assertEquals(1000000, ContextSizeParser.parseFromModelId("deepseek-v4-pro[1m]"));
  }

  @Test
  public void parsesKiloSuffix() {
    assertEquals(200000, ContextSizeParser.parseFromModelId("glm-5.2[200k]"));
  }

  @Test
  public void parsesPlainNumberSuffix() {
    assertEquals(128000, ContextSizeParser.parseFromModelId("model[128000]"));
  }

  @Test
  public void suffixIsCaseInsensitive() {
    assertEquals(1000000, ContextSizeParser.parseFromModelId("model[1M]"));
    assertEquals(1000000, ContextSizeParser.parseFromModelId("model[1m]"));
  }

  @Test
  public void noSuffixIsUnset() {
    assertEquals(ContextSizeParser.UNSET, ContextSizeParser.parseFromModelId("plain-model"));
    assertEquals(ContextSizeParser.UNSET, ContextSizeParser.parseFromModelId(null));
  }

  @Test
  public void malformedSuffixIsUnset() {
    assertEquals(ContextSizeParser.UNSET, ContextSizeParser.parseFromModelId("model[]"));
    assertEquals(ContextSizeParser.UNSET, ContextSizeParser.parseFromModelId("model[abc]"));
    assertEquals(ContextSizeParser.UNSET, ContextSizeParser.parseFromModelId("model[0]"));
    assertEquals(ContextSizeParser.UNSET, ContextSizeParser.parseFromModelId("model[-5]"));
  }

  @Test
  public void unclosedBracketIsUnset() {
    assertEquals(ContextSizeParser.UNSET, ContextSizeParser.parseFromModelId("model[1m"));
  }

  @Test
  public void stripsSuffix() {
    assertEquals("deepseek-v4-pro", ContextSizeParser.stripSuffix("deepseek-v4-pro[1m]"));
    assertEquals("glm-5.2", ContextSizeParser.stripSuffix("glm-5.2[200k]"));
  }

  @Test
  public void doesNotStripNonSizeBrackets() {
    // 关键用例：某些网关的模型名本身带方括号，剥掉会发错名字。
    assertEquals("foo[bar]", ContextSizeParser.stripSuffix("foo[bar]"));
    assertEquals("foo[bar]", ContextSizeParser.stripSuffix("foo[bar]"));
  }

  @Test
  public void stripHandlesNull() {
    assertEquals("", ContextSizeParser.stripSuffix(null));
  }

  @Test
  public void usesLastBracket() {
    // 取最后一个方括号：前面的方括号属于模型名本身。
    assertEquals("a[b]", ContextSizeParser.stripSuffix("a[b][1m]"));
    assertEquals(1000000, ContextSizeParser.parseFromModelId("a[b][1m]"));
  }

  @Test
  public void parseAcceptsPlainNumbers() {
    assertEquals(4096, ContextSizeParser.parse("4096"));
    assertEquals(200000, ContextSizeParser.parse("200k"));
    assertEquals(1000000, ContextSizeParser.parse("1m"));
  }

  @Test
  public void parseRejectsGarbage() {
    assertEquals(ContextSizeParser.UNSET, ContextSizeParser.parse(""));
    assertEquals(ContextSizeParser.UNSET, ContextSizeParser.parse(null));
    assertEquals(ContextSizeParser.UNSET, ContextSizeParser.parse("abc"));
  }

  @Test
  public void formatRoundTrips() {
    assertEquals("1M", ContextSizeParser.format(1000000));
    assertEquals("200K", ContextSizeParser.format(200000));
    assertEquals("4096", ContextSizeParser.format(4096));
    assertEquals("", ContextSizeParser.format(0));
  }
}

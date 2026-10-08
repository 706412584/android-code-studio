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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.tom.rv2ide.artificial.agent.compose.model.CardColor;
import com.tom.rv2ide.artificial.agent.compose.model.ColumnWidth;
import com.tom.rv2ide.artificial.agent.compose.model.ContainerStyle;
import com.tom.rv2ide.artificial.agent.compose.model.SpacingSize;
import com.tom.rv2ide.artificial.agent.compose.model.TextSize;
import com.tom.rv2ide.artificial.agent.compose.model.TextWeight;
import org.junit.Test;

/**
 * AdaptiveCardModel 解析族的回归测试（移植自 Aharou，纯数据模型零 Android 依赖）。
 *
 * <p>锁的是**容错语义**：非法输入回退到默认值而不是抛异常——面板脚本是用户可控的，
 * 解析层崩溃等于把脚本错误变成 UI 崩溃。
 */
public class AdaptiveCardModelTest {

  /** Kotlin  在字节码里是 INSTANCE 单例类；用解析结果自身构造期望值最稳。 */
  private static CardColor colorInstance(String semanticName) {
    return CardColor.Companion.fromString(semanticName);
  }

  @Test
  public void cardColor_parsesSemanticNames_caseInsensitive() {
    assertEquals(colorInstance("accent"), CardColor.Companion.fromString("ACCENT"));
    assertEquals(colorInstance("good"), CardColor.Companion.fromString("good"));
    assertEquals(colorInstance("attention"), CardColor.Companion.fromString("danger"));
    assertEquals(colorInstance("attention"), CardColor.Companion.fromString(" error "));
    assertEquals(colorInstance("default"), CardColor.Companion.fromString("default"));
    assertEquals(colorInstance("subtle"), CardColor.Companion.fromString("subtle"));
    assertEquals(colorInstance("warning"), CardColor.Companion.fromString("warning"));
  }

  @Test
  public void cardColor_customHex_passthrough() {
    CardColor.Custom custom = (CardColor.Custom) CardColor.Companion.fromString("#FF8800");
    assertEquals("#FF8800", custom.getHex());
  }

  @Test
  public void cardColor_nullOrGarbage_fallsBackToDefault() {
    assertEquals(colorInstance("default"), CardColor.Companion.fromString(null));
    assertEquals(colorInstance("default"), CardColor.Companion.fromString(""));
    assertEquals(colorInstance("default"), CardColor.Companion.fromString("not-a-color"));
    // 没有 # 前缀的十六进制不是 Custom
    assertEquals(colorInstance("default"), CardColor.Companion.fromString("FF8800"));
  }

  @Test
  public void containerStyle_aliases() {
    assertEquals(ContainerStyle.ATTENTION, ContainerStyle.Companion.fromString("error"));
    assertEquals(ContainerStyle.ATTENTION, ContainerStyle.Companion.fromString("danger"));
    assertEquals(ContainerStyle.ACCENT, ContainerStyle.Companion.fromString("accent"));
    assertEquals(ContainerStyle.DEFAULT, ContainerStyle.Companion.fromString("weird"));
    assertEquals(ContainerStyle.DEFAULT, ContainerStyle.Companion.fromString(null));
  }

  @Test
  public void textSize_aliases() {
    assertEquals(TextSize.EXTRA_LARGE, TextSize.Companion.fromString("xl"));
    assertEquals(TextSize.EXTRA_LARGE, TextSize.Companion.fromString("extra_large"));
    assertEquals(TextSize.MEDIUM, TextSize.Companion.fromString("MEDIUM"));
    assertEquals(TextSize.DEFAULT, TextSize.Companion.fromString("bogus"));
  }

  @Test
  public void textWeight_aliases() {
    assertEquals(TextWeight.BOLDER, TextWeight.Companion.fromString("semibold"));
    assertEquals(TextWeight.LIGHTER, TextWeight.Companion.fromString("light"));
    assertEquals(TextWeight.DEFAULT, TextWeight.Companion.fromString("other"));
  }

  @Test
  public void spacingSize_aliases() {
    assertEquals(SpacingSize.SMALL, SpacingSize.Companion.fromString("sm"));
    assertEquals(SpacingSize.LARGE, SpacingSize.Companion.fromString("lg"));
    assertEquals(SpacingSize.MEDIUM, SpacingSize.Companion.fromString("unknown"));
  }

  private static ColumnWidth widthInstance(String name) {
    return ColumnWidth.Companion.fromString(name);
  }

  @Test
  public void columnWidth_weightAndFixed_andFallback() {
    ColumnWidth.Weighted w = (ColumnWidth.Weighted) ColumnWidth.Companion.fromString("2.5");
    assertEquals(2.5f, w.getWeight(), 1e-6f);

    ColumnWidth.Fixed f = (ColumnWidth.Fixed) ColumnWidth.Companion.fromString("120dp");
    assertEquals(120, f.getDp());

    assertEquals(widthInstance("auto"), ColumnWidth.Companion.fromString("auto"));
    assertEquals(widthInstance("stretch"), ColumnWidth.Companion.fromString("stretch"));
    // 非法数值回退 Stretch
    assertEquals(widthInstance("stretch"), ColumnWidth.Companion.fromString("abc"));
    assertEquals(widthInstance("stretch"), ColumnWidth.Companion.fromString(null));
    // 负权重不成立
    assertEquals(widthInstance("stretch"), ColumnWidth.Companion.fromString("-1"));
  }

  @Test
  public void cardPadding_zeroDetection() {
    assertTrue(com.tom.rv2ide.artificial.agent.compose.model.CardPadding.Companion.getZero().isZero());
    assertTrue(new com.tom.rv2ide.artificial.agent.compose.model.CardPadding(0, 0, 0, 0).isZero());
    assertFalse(com.tom.rv2ide.artificial.agent.compose.model.CardPadding.Companion.all(4).isZero());
  }
}

/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * {@link OcrPort} 的数据对象：Line 的坐标与 Result 的两态契约。
 *
 * <p>两态互斥由工厂方法保证（ok/failure），调用方靠 isOk 分流——若 failure 也能
 * 带非空 lines、或 ok 能带 error，工具层的分支就会互相打架。这里把边界钉住。
 */
final class OcrPortTest {

  @Test
  void lineNormalizesNullTextToEmpty() {
    OcrPort.Line line = new OcrPort.Line(null, 1, 2, 3, 4);
    assertEquals("", line.getText());
    assertEquals(1, line.getLeft());
    assertEquals(2, line.getTop());
    assertEquals(3, line.getRight());
    assertEquals(4, line.getBottom());
  }

  @Test
  void lineKeepsCoordinatesVerbatim() {
    OcrPort.Line line = new OcrPort.Line("设置", 100, 200, 400, 260);
    assertEquals("设置", line.getText());
    assertEquals(100, line.getLeft());
    assertEquals(260, line.getBottom());
  }

  @Test
  void okResultCarriesLinesAndEngine() {
    OcrPort.Line line = new OcrPort.Line("a", 0, 0, 1, 1);
    OcrPort.Result result = OcrPort.Result.ok(Arrays.asList(line), "MLKit-CN");

    assertTrue(result.isOk());
    assertEquals("", result.getError());
    assertEquals(1, result.getLines().size());
    assertEquals("MLKit-CN", result.getEngine());
  }

  @Test
  void okWithEmptyLinesIsStillValidConclusion() {
    // 「图上没有文字」是有效结论，不是失败——空列表必须仍是 ok。
    OcrPort.Result result = OcrPort.Result.ok(Collections.emptyList(), "MLKit-CN");
    assertTrue(result.isOk());
    assertTrue(result.getLines().isEmpty());
  }

  @Test
  void okNormalizesNullLinesAndNullEngine() {
    OcrPort.Result result = OcrPort.Result.ok(null, null);
    assertTrue(result.isOk());
    assertTrue(result.getLines().isEmpty());
    assertEquals("", result.getEngine());
  }

  @Test
  void failureResultHasEmptyLinesAndEngine() {
    OcrPort.Result result = OcrPort.Result.failure("引擎未初始化");

    assertFalse(result.isOk());
    assertEquals("引擎未初始化", result.getError());
    assertTrue(result.getLines().isEmpty(), "失败结果不应带 lines");
    assertEquals("", result.getEngine(), "失败时引擎标识为空串");
  }

  @Test
  void failureNormalizesNullError() {
    OcrPort.Result result = OcrPort.Result.failure(null);
    assertFalse(result.isOk());
    assertEquals("", result.getError());
  }
}

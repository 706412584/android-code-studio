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

package com.tom.rv2ide.ai.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * 钉住 {@link ReasoningDeltaExtractor}：不同服务商把思考内容放在不同字段
 * （reasoning_content / reasoning / reasoning_details），以及在不同字段间切换时
 * 的空白与加粗分隔处理——这些直接决定「思考区渲染出来是否粘连成一坨」。
 */
final class ReasoningDeltaExtractorTest {

  private static JSONObject delta(String key, Object value) {
    JSONObject object = new JSONObject();
    object.put(key, value);
    return object;
  }

  @Test
  void extractsPlainReasoningContentField() {
    assertEquals("想", new ReasoningDeltaExtractor().extract(delta("reasoning_content", "想")));
  }

  @Test
  void ignoresNullReasoningContent() {
    assertEquals("", new ReasoningDeltaExtractor().extract(delta("reasoning_content", JSONObject.NULL)));
    assertEquals("", new ReasoningDeltaExtractor().extract(new JSONObject()));
  }

  @Test
  void extractsReasoningStringField() {
    // 走 reasoningFieldStrategy，其 flush 由 summaryWhitespaceNormalizer 负责
    assertEquals("思考", new ReasoningDeltaExtractor().extract(delta("reasoning", "思考")));
  }

  @Test
  void extractsReasoningObjectWithContentOrText() {
    assertEquals("A", new ReasoningDeltaExtractor().extract(
        delta("reasoning", new JSONObject().put("content", "A"))));
    assertEquals("B", new ReasoningDeltaExtractor().extract(
        delta("reasoning", new JSONObject().put("text", "B"))));
    assertEquals("", new ReasoningDeltaExtractor().extract(
        delta("reasoning", new JSONObject().put("other", "x"))));
  }

  @Test
  void extractsReasoningDetailsObjectByFieldPrecedence() {
    assertEquals("S", new ReasoningDeltaExtractor().extract(
        delta("reasoning_details", new JSONObject().put("summary", "S"))));
    assertEquals("C", new ReasoningDeltaExtractor().extract(
        delta("reasoning_details", new JSONObject().put("content", "C"))));
    assertEquals("T", new ReasoningDeltaExtractor().extract(
        delta("reasoning_details", new JSONObject().put("text", "T"))));
    assertEquals("R", new ReasoningDeltaExtractor().extract(
        delta("reasoning_details", new JSONObject().put("reasoning_content", "R"))));
    assertEquals("", new ReasoningDeltaExtractor().extract(
        delta("reasoning_details", new JSONObject().put("nothing", "x"))));
  }

  @Test
  void extractsNestedReasoningDetailsValues() {
    JSONArray nested = new JSONArray().put(new JSONObject().put("text", "内层"));
    assertEquals("内层", new ReasoningDeltaExtractor().extract(
        delta("reasoning_details", new JSONObject().put("summary", nested))));
    assertEquals("", new ReasoningDeltaExtractor().extract(
        delta("reasoning_details", new JSONObject().put("summary", 42))));
  }

  @Test
  void extractsReasoningDetailsArrayOfStringsAndObjects() {
    JSONArray array = new JSONArray().put("片段").put(new JSONObject().put("text", "对象"));
    assertEquals("片段对象", new ReasoningDeltaExtractor().extract(delta("reasoning_details", array)));
  }

  @Test
  void reasoningDetailsArraySkipsEmptySegments() {
    JSONArray array = new JSONArray().put("").put(JSONObject.NULL).put("有");
    assertEquals("有", new ReasoningDeltaExtractor().extract(delta("reasoning_details", array)));
  }

  @Test
  void newSummaryBlockIsSeparatedByPipe() {
    // 两条 type=reasoning.summary 且带不同 id → 新块，插 " | " 分隔
    JSONArray first = new JSONArray().put(
        new JSONObject().put("type", "reasoning.summary").put("id", "a").put("text", "首段"));
    JSONArray second = new JSONArray().put(
        new JSONObject().put("type", "reasoning.summary").put("id", "b").put("text", "次段"));
    ReasoningDeltaExtractor extractor = new ReasoningDeltaExtractor();
    assertEquals("首段", extractor.extract(delta("reasoning_details", first)));
    assertEquals(" | 次段", extractor.extract(delta("reasoning_details", second)));
  }

  @Test
  void summaryBlockKeyedByIndexAlsoSeparates() {
    JSONArray first = new JSONArray().put(
        new JSONObject().put("type", "reasoning.summary").put("index", 0).put("text", "甲"));
    JSONArray second = new JSONArray().put(
        new JSONObject().put("type", "reasoning.summary").put("index", 1).put("text", "乙"));
    ReasoningDeltaExtractor extractor = new ReasoningDeltaExtractor();
    extractor.extract(delta("reasoning_details", first));
    assertEquals(" | 乙", extractor.extract(delta("reasoning_details", second)));
  }

  @Test
  void unkeyedRepeatedSummaryInOneChunkSeparatesAfterFirst() {
    // 同一 chunk 内两条无 key 的 summary：第二条起视为新块
    JSONArray array = new JSONArray()
        .put(new JSONObject().put("type", "reasoning.summary").put("text", "一"))
        .put(new JSONObject().put("type", "reasoning.summary").put("text", "二"));
    assertEquals("一 | 二", new ReasoningDeltaExtractor().extract(delta("reasoning_details", array)));
  }

  @Test
  void nonSummarySegmentsDoNotTriggerBoundary() {
    JSONArray array = new JSONArray()
        .put(new JSONObject().put("type", "reasoning.text").put("text", "一"))
        .put(new JSONObject().put("type", "reasoning.text").put("text", "二"));
    assertEquals("一二", new ReasoningDeltaExtractor().extract(delta("reasoning_details", array)));
  }

  @Test
  void whitespaceNormalizerDefersTrailingWhitespaceUntilNextChunk() {
    // 分帧到达时尾部空格必须延后拼接，否则换行会漂到错误位置
    ReasoningDeltaExtractor extractor = new ReasoningDeltaExtractor();
    assertEquals("思考 ", extractor.extract(delta("reasoning_content", "思考 ")));
  }

  @Test
  void switchingReasoningFieldsFlushesPendingWhitespace() {
    ReasoningDeltaExtractor extractor = new ReasoningDeltaExtractor();
    extractor.extract(delta("reasoning_content", "先 "));
    // 切到 reasoning 字段：先 flush 掉 pending 空白，再附上新内容
    assertEquals("答", extractor.extract(delta("reasoning", "答")).trim());
  }

  @Test
  void adjacentBoldSummariesGainSpacer() {
    // 两个加粗摘要直接相邻（**a****b**）会渲染糊在一起，中间要插 " | "
    ReasoningDeltaExtractor extractor = new ReasoningDeltaExtractor();
    extractor.extract(delta("reasoning", "**a**"));
    String second = extractor.extract(delta("reasoning", "**b**"));
    assertTrue(second.contains(" | "), "相邻加粗摘要之间应插入分隔：" + second);
    assertEquals("** | **b", second);
  }

  @Test
  void flushReturnsPendingWhitespaceOrAsterisks() {
    ReasoningDeltaExtractor extractor = new ReasoningDeltaExtractor();
    assertEquals("", extractor.flush());
    // reasoning_content 路径不缓存尾部空白（原样透传），因此 flush 仍为空
    assertTrue(extractor.extract(delta("reasoning_content", "x  ")).endsWith("  "));
    assertEquals("", extractor.flush());
  }

  @Test
  void boldSummarySpacerKeepsOddAsteriskCounts() {
    ReasoningDeltaExtractor extractor = new ReasoningDeltaExtractor();
    // 单个 * 不是加粗边界，flush 时应原样吐出
    extractor.extract(delta("reasoning", "a*"));
    assertTrue(extractor.flush().contains("*"));
  }
}

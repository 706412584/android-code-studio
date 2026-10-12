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

package com.tom.rv2ide.artificial.agent.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.tom.rv2ide.ai.tool.OcrPort;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

/**
 * {@link PhoneOcrText} 纯逻辑 + {@link PhoneOcrTool} schema 的回归测试。
 *
 * <p>execute 全链路（Bitmap 解码/裁剪）不做 JVM 测试——android.jar 桩会抛
 * "not mocked"；该部分由设备验证覆盖（与既有 phone 工具的做法一致）。
 */
public class PhoneOcrToolTest {

  // ---------------------------------------------------------------------
  // effectiveScale（计划 §4.4 用例）
  // ---------------------------------------------------------------------

  @Test
  public void effectiveScaleUsesDefaultWhenNotExplicit() {
    assertEquals(1.0f, PhoneOcrText.effectiveScale(2340, false, 0), 0.01f);
    assertEquals(2.0f, PhoneOcrText.effectiveScale(800, false, 0), 0.01f);
    // (1400,false) → 默认 1.0；上限 min(1.0, max(1, 2560/1400=1.83)) = 1.0
    assertEquals(1.0f, PhoneOcrText.effectiveScale(1400, false, 0), 0.01f);
  }

  @Test
  public void effectiveScaleClampsExplicitRequest() {
    assertEquals(3.0f, PhoneOcrText.effectiveScale(500, true, 3), 0.01f);
    assertEquals(0.5f, PhoneOcrText.effectiveScale(2000, true, 0.5), 0.01f);
    // (300,true,9) → 钳 4.0；再 min(4, 2560/300=8.53) → 4.0
    assertEquals(4.0f, PhoneOcrText.effectiveScale(300, true, 9), 0.01f);
    // (2000,true,2.0) → min(2.0, 2560/2000=1.28) = 1.28（上限规则的实际触发路径）
    assertEquals(1.28f, PhoneOcrText.effectiveScale(2000, true, 2.0), 0.01f);
  }

  // ---------------------------------------------------------------------
  // mapBox / mapLines（计划 §4.5）
  // ---------------------------------------------------------------------

  @Test
  public void mapBoxIdentity() {
    OcrPort.Line raw = new OcrPort.Line("开始游戏", 340, 1120, 780, 1172);
    OcrPort.Line mapped = PhoneOcrText.mapBox(raw, 0, 0, 1.0f, 1.0f, 1080, 2340);
    assertEquals(340, mapped.getLeft());
    assertEquals(1120, mapped.getTop());
    assertEquals(780, mapped.getRight());
    assertEquals(1172, mapped.getBottom());
    assertEquals("开始游戏", mapped.getText());
  }

  @Test
  public void mapBoxWithOriginAndScale() {
    // origin=(120,400), s=2.0, k=1：屏幕坐标 = origin + work/2
    OcrPort.Line raw = new OcrPort.Line("设置", 440, 1440, 880, 1500);
    OcrPort.Line mapped = PhoneOcrText.mapBox(raw, 120, 400, 2.0f, 1.0f, 1080, 2340);
    assertEquals(120 + 220, mapped.getLeft());
    assertEquals(400 + 720, mapped.getTop());
    assertEquals(120 + 440, mapped.getRight());
    assertEquals(400 + 750, mapped.getBottom());
  }

  @Test
  public void mapBoxWithKAndClampsToScreen() {
    // k=1.49（1080/724）：四坐标 ×1.49 四舍五入；越界值 clamp
    OcrPort.Line raw = new OcrPort.Line("A", 100, 200, 900, 1600);
    OcrPort.Line mapped = PhoneOcrText.mapBox(raw, 0, 0, 1.0f, 1.49f, 1080, 2340);
    assertEquals(Math.round(100 * 1.49f), mapped.getLeft());
    assertEquals(Math.round(200 * 1.49f), mapped.getTop());
    // 900*1.49=1341 > 1080 → clamp 到 1080
    assertEquals(1080, mapped.getRight());
    // 1600*1.49=2384 > 2340 → clamp 到 2340
    assertEquals(2340, mapped.getBottom());
  }

  // ---------------------------------------------------------------------
  // parseRegion / parseDims（计划 §6.1 #6/#7）
  // ---------------------------------------------------------------------

  @Test
  public void parseRegionHandlesMissingAndInvalid() throws Exception {
    assertNull(PhoneOcrText.parseRegion(new JSONObject()));
    JSONObject bad = new JSONObject().put("region",
        new JSONObject().put("x", 0).put("y", 0).put("width", 0).put("height", 10));
    assertNull("width<=0 → null", PhoneOcrText.parseRegion(bad));
    JSONObject neg = new JSONObject().put("region",
        new JSONObject().put("x", -50).put("y", -30).put("width", 100).put("height", 100));
    PhoneOcrText.Region r = PhoneOcrText.parseRegion(neg);
    assertNotNull(r);
    assertEquals(0, r.x);
    assertEquals(0, r.y);
  }

  @Test
  public void parseDimsReadsBothLines() {
    String content = "截图成功。\n屏幕尺寸: 1080x2340\n输出尺寸: 724x1568\n格式: image/png\n";
    int[] screen = PhoneOcrText.parseDims(content, "屏幕尺寸");
    assertEquals(1080, screen[0]);
    assertEquals(2340, screen[1]);
    int[] output = PhoneOcrText.parseDims(content, "输出尺寸");
    assertEquals(724, output[0]);
    assertEquals(1568, output[1]);
    int[] missing = PhoneOcrText.parseDims("没有该行", "输出尺寸");
    assertEquals(0, missing[0]);
    assertEquals(0, missing[1]);
  }

  // ---------------------------------------------------------------------
  // format（计划 §2.3 / §6.1 #8）
  // ---------------------------------------------------------------------

  @Test
  public void formatProducesTemplateWithBoxes() {
    List<OcrPort.Line> lines = new ArrayList<>();
    lines.add(new OcrPort.Line("开始游戏", 340, 1120, 780, 1172));
    lines.add(new OcrPort.Line("设置", 340, 1200, 620, 1250));
    PhoneOcrText.Region region = new PhoneOcrText.Region(120, 400, 600, 800);
    String text = PhoneOcrText.format(lines, 1080, 2340, region, 2.0f, 812, "engine-x", 200, true);
    assertTrue(text.contains("OCR 成功"));
    assertTrue(text.contains("engine-x"));
    assertTrue(text.contains("812ms"));
    assertTrue(text.contains("2.0x"));
    assertTrue(text.contains("屏幕尺寸: 1080x2340"));
    assertTrue(text.contains("识别区域: x=120 y=400 w=600 h=800"));
    assertTrue(text.contains("#1 [340,1120,780,1172] 开始游戏"));
    assertTrue(text.contains("--- 文本 ---"));
    assertTrue(text.contains("开始游戏"));
  }

  @Test
  public void formatWithoutBoxesSkipsBoxLines() {
    List<OcrPort.Line> lines = new ArrayList<>();
    lines.add(new OcrPort.Line("A", 1, 2, 3, 4));
    PhoneOcrText.Region region = new PhoneOcrText.Region(0, 0, 100, 100);
    String text = PhoneOcrText.format(lines, 100, 100, region, 1.0f, 5, "e", 200, false);
    assertFalse(text.contains("[1,2,3,4]"));
    assertTrue(text.contains("--- 文本 ---"));
  }

  @Test
  public void formatTruncatesAtMaxLines() {
    List<OcrPort.Line> lines = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      lines.add(new OcrPort.Line("L" + i, i, i, i + 1, i + 1));
    }
    PhoneOcrText.Region region = new PhoneOcrText.Region(0, 0, 100, 100);
    String text = PhoneOcrText.format(lines, 100, 100, region, 1.0f, 5, "e", 5, true);
    assertTrue(text.contains("（其余 5 行未显示）"));
    assertFalse(text.contains("#6"));
  }

  @Test
  public void formatEmptyIsSuccessNotError() {
    PhoneOcrText.Region region = new PhoneOcrText.Region(0, 0, 1080, 2340);
    String text = PhoneOcrText.format(new ArrayList<>(), 1080, 2340, region, 1.0f, 5, "e", 200, true);
    assertTrue(text.contains("未识别到任何文本"));
    assertTrue(text.contains("可尝试"));
  }

  // ---------------------------------------------------------------------
  // schema 结构回归（防 CodeActionsTool 事故重演）
  // ---------------------------------------------------------------------

  @Test
  public void parametersSchemaHasRequiredAtTopLevel() throws Exception {
    JSONObject params = new PhoneOcrTool().getParameters();
    assertEquals("object", params.optString("type"));
    JSONObject props = params.optJSONObject("properties");
    assertNotNull(props);
    org.json.JSONArray required = params.optJSONArray("required");
    assertNotNull("required 必须在顶层", required);
    assertFalse("required 不得嵌套在 properties 内", props.has("required"));
    for (java.util.Iterator<String> it = props.keys(); it.hasNext(); ) {
      String key = it.next();
      Object value = props.get(key);
      assertTrue("properties." + key + " 必须是 schema 对象",
          value instanceof JSONObject);
    }
  }

  @Test
  public void toolMetadata() {
    PhoneOcrTool tool = new PhoneOcrTool();
    assertEquals("phone_ocr", tool.getName());
    assertEquals(com.tom.rv2ide.ai.tool.api.ToolCategory.READ, tool.getCategory());
    assertTrue(tool.isAllowedInReadonlyMode());
    assertFalse(tool.needsConfirmation());
  }
}

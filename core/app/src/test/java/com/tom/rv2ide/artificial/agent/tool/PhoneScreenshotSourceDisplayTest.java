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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.ToolInvoker;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

/**
 * {@code display=device|vscreen} 来源分派的回归测试。
 *
 * <p><b>为什么需要它</b>：视觉回归的四个工具（baseline / compare / action_capture / ocr）
 * 都经 {@link PhoneScreenshotSource} 取图。来源分派一旦写错，表现是「截了物理屏却当成
 * 影子屏」，而两者尺寸/内容不同——基线比对会全量报差异，但不会有任何报错。
 * 这里用假 {@link ToolInvoker} 锁住三件事：
 * <ul>
 *   <li>{@code display} 归一：空值/未知值回落 device
 *   <li>vscreen 走 {@code vscreen} 工具且只传 shot 参数，不把 display 透传给子工具
 *   <li>两种来源解析出同一套「尺寸 + 路径」结果
 * </ul>
 */
public class PhoneScreenshotSourceDisplayTest {

  /** 记录被调用的工具名与参数，并返回一段与真实工具同格式的结果文本。 */
  private static final class RecordingInvoker implements ToolInvoker {
    final List<String> toolNames = new ArrayList<>();
    final List<JSONObject> args = new ArrayList<>();
    private final String path;

    RecordingInvoker(String path) {
      this.path = path;
    }

    @Override
    public ToolResult invoke(String toolName, JSONObject args, ToolContext context) {
      toolNames.add(toolName);
      this.args.add(args == null ? new JSONObject() : args);
      return ToolResult.of(
          "call-1",
          toolName,
          "截图成功。\n屏幕尺寸: 1080x1920\n输出尺寸: 1080x1920\n文件路径:\n" + path + "\n",
          false);
    }
  }

  @Test
  public void normalizeDisplayFallsBackToDevice() {
    assertEquals(PhoneScreenshotSource.DISPLAY_DEVICE,
        PhoneScreenshotSource.normalizeDisplay(null));
    assertEquals(PhoneScreenshotSource.DISPLAY_DEVICE,
        PhoneScreenshotSource.normalizeDisplay(""));
    assertEquals(PhoneScreenshotSource.DISPLAY_DEVICE,
        PhoneScreenshotSource.normalizeDisplay("physical"));
    assertEquals(PhoneScreenshotSource.DISPLAY_VSCREEN,
        PhoneScreenshotSource.normalizeDisplay("VSCREEN"));
    assertEquals(PhoneScreenshotSource.DISPLAY_VSCREEN,
        PhoneScreenshotSource.normalizeDisplay(" vscreen "));
  }

  @Test
  public void deviceDisplayInvokesPhoneScreenshot() throws Exception {
    RecordingInvoker invoker = new RecordingInvoker(tempPng());
    JSONObject extra = new JSONObject().put("display", "device").put("maxDimension", 0);

    PhoneScreenshotSource.Shot shot = PhoneScreenshotSource.capture(invoker, null, extra);

    assertTrue(shot.ok);
    assertEquals(1, invoker.toolNames.size());
    assertEquals("phone_screenshot", invoker.toolNames.get(0));
    // display 是来源选择，不能透传给子工具（它不认识这个键）。
    assertTrue(!invoker.args.get(0).has("display"));
    // inline 由本方法强制 false，调用方不可覆盖。
    assertEquals(false, invoker.args.get(0).optBoolean("inline", true));
    assertEquals(0, invoker.args.get(0).optInt("maxDimension", -1));
    assertEquals(1080, shot.screenWidth);
    assertEquals(1920, shot.screenHeight);
  }

  @Test
  public void vscreenDisplayInvokesVscreenShot() throws Exception {
    RecordingInvoker invoker = new RecordingInvoker(tempPng());
    JSONObject extra = new JSONObject().put("display", "vscreen");

    PhoneScreenshotSource.Shot shot = PhoneScreenshotSource.capture(invoker, null, extra);

    assertTrue(shot.ok);
    assertEquals(1, invoker.toolNames.size());
    assertEquals("vscreen", invoker.toolNames.get(0));
    assertEquals("shot", invoker.args.get(0).optString("action"));
    assertEquals(false, invoker.args.get(0).optBoolean("inline", true));
    // phone_screenshot 专有参数不该出现在 vscreen 调用里。
    assertTrue(!invoker.args.get(0).has("maxDimension"));
    assertEquals(1080, shot.screenWidth);
  }

  @Test
  public void errorResultIsReportedAsFailure() throws Exception {
    ToolInvoker failing =
        (toolName, args, context) -> ToolResult.of("call-1", toolName, "影子屏未运行，请先 action=start", true);
    PhoneScreenshotSource.Shot shot =
        PhoneScreenshotSource.capture(
            failing, null, new JSONObject().put("display", "vscreen"));
    assertTrue(!shot.ok);
    assertNotNull(shot.error);
    assertTrue(shot.error.contains("截图失败"));
  }

  /** 结果解析只要求路径存在，内容不必是真 PNG。 */
  private static String tempPng() {
    try {
      java.io.File file = java.io.File.createTempFile("acs-shot", ".png");
      file.deleteOnExit();
      return file.getAbsolutePath();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }
}

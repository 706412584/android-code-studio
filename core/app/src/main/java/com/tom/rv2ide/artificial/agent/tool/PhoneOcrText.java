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

import com.tom.rv2ide.ai.tool.OcrPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * phone_ocr 的纯逻辑（零 Android import）：缩放决策、坐标映射、region 解析、结果格式化。
 *
 * <p>拆出来是为了 JVM 单测——{@code PhoneOcrTool} 的 Bitmap 部分只能设备验证
 * （与既有 phone 工具的做法一致）。
 */
final class PhoneOcrText {

  /** 自动放大的触发阈值：识别区域长边小于该值时默认放大 2 倍。 */
  static final int AUTO_UPSCALE_THRESHOLD_PX = 1200;
  /** 放大后工作图长边上限（内存与耗时保护）。 */
  static final int MAX_WORK_LONG_SIDE_PX = 2560;
  /** 显式 scale 的钳制范围。 */
  static final double MIN_SCALE = 0.25;
  static final double MAX_SCALE = 4.0;

  private PhoneOcrText() {}

  /** 屏幕上的矩形区域（物理像素）。 */
  static final class Region {
    final int x;
    final int y;
    final int width;
    final int height;

    Region(int x, int y, int width, int height) {
      this.x = x;
      this.y = y;
      this.width = width;
      this.height = height;
    }
  }

  /**
   * 计算实际缩放系数（规则见计划 §4.4，顺序固定）：
   * <ol>
   *   <li>显式请求钳到 [{@link #MIN_SCALE}, {@link #MAX_SCALE}]；未显式请求时：
   *       区域长边 &lt; {@link #AUTO_UPSCALE_THRESHOLD_PX} 取 2.0，否则 1.0
   *   <li>只对放大（&gt;1）施加上限：s = min(s, max(1, {@link #MAX_WORK_LONG_SIDE_PX} / longSide))。
   *       默认 1.0 永远保持 1.0（不做隐式缩小），显式缩小（&lt;1）不受上限约束。
   * </ol>
   */
  static float effectiveScale(int longSide, boolean explicit, double requested) {
    float s;
    if (explicit) {
      s = (float) Math.min(Math.max(requested, MIN_SCALE), MAX_SCALE);
    } else {
      s = longSide < AUTO_UPSCALE_THRESHOLD_PX ? 2.0f : 1.0f;
    }
    // 上限只对放大生效（max(1, ...) 保证不会把 s 压到 1 以下）
    s = Math.min(s, Math.max(1.0f, MAX_WORK_LONG_SIDE_PX / (float) longSide));
    return s;
  }

  /**
   * 解析 region 参数；缺失或非法（width/height ≤ 0）返回 null（= 全屏）。
   * 负 x/y 钳到 0（与 PhoneScreenshotTool.parseRegion 同语义）。
   */
  static Region parseRegion(JSONObject input) {
    if (input == null || !input.has("region")) {
      return null;
    }
    JSONObject region = input.optJSONObject("region");
    if (region == null) {
      return null;
    }
    int x = region.optInt("x", 0);
    int y = region.optInt("y", 0);
    int width = region.optInt("width", 0);
    int height = region.optInt("height", 0);
    if (width <= 0 || height <= 0) {
      return null;
    }
    return new Region(Math.max(0, x), Math.max(0, y), width, height);
  }

  /**
   * 把屏幕坐标 region 换算为位图坐标（除以回缩系数 k 并 clamp）。
   * 无交集（收敛后 width/height ≤ 0）返回 null。
   */
  static Region regionToBitmap(Region screen, float k, int bmpWidth, int bmpHeight) {
    int left = clamp((int) (screen.x / k), 0, bmpWidth);
    int top = clamp((int) (screen.y / k), 0, bmpHeight);
    int right = clamp((int) ((screen.x + screen.width) / k), 0, bmpWidth);
    int bottom = clamp((int) ((screen.y + screen.height) / k), 0, bmpHeight);
    int w = right - left;
    int h = bottom - top;
    if (w <= 0 || h <= 0) {
      return null;
    }
    return new Region(left, top, w, h);
  }

  /**
   * 把 OCR 结果的位图坐标映射回屏幕坐标：
   * {@code screen = region.origin + (workCoord / s) * k}，并 clamp 到屏幕内。
   */
  static OcrPort.Line mapBox(OcrPort.Line raw, int originX, int originY, float s, float k,
      int screenW, int screenH) {
    int l = clamp(originX + Math.round((raw.getLeft() / s) * k), 0, screenW);
    int t = clamp(originY + Math.round((raw.getTop() / s) * k), 0, screenH);
    int r = clamp(originX + Math.round((raw.getRight() / s) * k), 0, screenW);
    int b = clamp(originY + Math.round((raw.getBottom() / s) * k), 0, screenH);
    return new OcrPort.Line(raw.getText(), l, t, r, b);
  }

  /** 对整组行做坐标映射。 */
  static List<OcrPort.Line> mapLines(List<OcrPort.Line> raw, int originX, int originY,
      float s, float k, int screenW, int screenH) {
    List<OcrPort.Line> out = new ArrayList<>(raw.size());
    for (OcrPort.Line line : raw) {
      out.add(mapBox(line, originX, originY, s, k, screenW, screenH));
    }
    return out;
  }

  /**
   * 从截图结果文本里解析一行尺寸（如 {@code "屏幕尺寸: 1080x2340"}）。
   * 解析不到返回 {0, 0}。
   */
  static int[] parseDims(String content, String label) {
    if (content == null) {
      return new int[] {0, 0};
    }
    Pattern p = Pattern.compile(Pattern.quote(label) + ":\\s*(\\d+)x(\\d+)");
    Matcher m = p.matcher(content);
    if (!m.find()) {
      return new int[] {0, 0};
    }
    try {
      return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
    } catch (NumberFormatException e) {
      return new int[] {0, 0};
    }
  }

  /**
   * 结果文本（逐字符模板见计划 §2.3）。
   *
   * @param lines      已映射到屏幕坐标的行
   * @param regionScreen 识别区域（屏幕像素）
   * @param s          缩放系数（头部回显）
   * @param elapsedMs  识别耗时
   * @param engine     引擎标识
   * @param maxLines   行数上限（超出截断并提示）
   * @param includeBoxes 是否输出 box 行
   */
  static String format(List<OcrPort.Line> lines, int screenW, int screenH, Region regionScreen,
      float s, long elapsedMs, String engine, int maxLines, boolean includeBoxes) {
    if (lines.isEmpty()) {
      // 空结果是有效结论（必须与失败区分）——给排查建议，不走 error 路径。
      return "OCR 完成，但未识别到任何文本。\n"
          + "屏幕尺寸: " + screenW + "x" + screenH
          + " | 识别区域: x=" + regionScreen.x + " y=" + regionScreen.y
          + " w=" + regionScreen.width + " h=" + regionScreen.height + "\n"
          + "可尝试：缩小 region 到文字所在区域、传 scale 放大、确认屏幕上确实有文字。";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("OCR 成功。引擎: ").append(engine)
        .append(" | 耗时 ").append(elapsedMs).append("ms")
        .append(" | 缩放 ").append(String.format(Locale.US, "%.1fx", s)).append('\n');
    sb.append("屏幕尺寸: ").append(screenW).append('x').append(screenH)
        .append(" | 识别区域: x=").append(regionScreen.x)
        .append(" y=").append(regionScreen.y)
        .append(" w=").append(regionScreen.width)
        .append(" h=").append(regionScreen.height).append('\n');
    sb.append("识别到 ").append(lines.size()).append(" 行文本。");
    if (includeBoxes) {
      sb.append("box 为屏幕物理像素 [左,上,右,下]，中心点可直接传给 phone_click：\n");
      int shown = Math.min(lines.size(), maxLines);
      for (int i = 0; i < shown; i++) {
        OcrPort.Line line = lines.get(i);
        sb.append('#').append(i + 1).append(" [")
            .append(line.getLeft()).append(',').append(line.getTop()).append(',')
            .append(line.getRight()).append(',').append(line.getBottom()).append("] ")
            .append(line.getText()).append('\n');
      }
      if (lines.size() > maxLines) {
        sb.append("（其余 ").append(lines.size() - maxLines).append(" 行未显示）\n");
      }
    } else {
      sb.append('\n');
    }
    sb.append("--- 文本 ---\n");
    for (int i = 0; i < Math.min(lines.size(), maxLines); i++) {
      sb.append(lines.get(i).getText()).append('\n');
    }
    // 截断提示只在 box 段已给过时省略（避免同一条信息出现两次）。
    if (lines.size() > maxLines && !includeBoxes) {
      sb.append("（其余 ").append(lines.size() - maxLines).append(" 行未显示）");
    }
    // 去尾部换行
    int end = sb.length();
    while (end > 0 && sb.charAt(end - 1) == '\n') {
      end--;
    }
    return sb.substring(0, end);
  }

  private static int clamp(int v, int lo, int hi) {
    return Math.min(hi, Math.max(lo, v));
  }
}

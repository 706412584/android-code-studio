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

import java.util.List;

/**
 * 屏幕文字识别（OCR）端口。
 *
 * <p><b>为什么是端口</b>：本模块（core/ai-tool）是纯 JVM、零 Android 依赖的 java-library，
 * 而 OCR 引擎（ML Kit bundled 中文）是 Android AAR。把引擎收在这个中性接口后面，
 * 由 app 层实现并注入 {@link ToolContext}；将来换引擎（Tesseract 等）只换实现。
 *
 * <p><b>为什么没有 isAvailable()</b>：可用性用 {@link Result#failure} 表达——
 * 单独的 isAvailable() 要么逼实现去预探引擎（贵且状态会过期），要么返回陈旧结论。
 * 端口为 null（未注入）由工具层单独报"未接入"。
 */
public interface OcrPort {

  /**
   * 识别一张图片里的文字。
   *
   * <p>实现约定：**必须有界**——在 {@code timeoutMs} 内返回；不得抛异常（一律返回失败 Result）。
   * 坐标为输入图片的像素坐标（原点左上），由调用方负责与屏幕坐标的换算。
   *
   * @param imageBytes 图片字节（PNG/JPEG；本工具只传 PNG）
   * @param timeoutMs 识别时间上限（毫秒）
   * @return 识别结果；引擎不可用/超时/解码失败时为 {@link Result#failure}
   */
  Result recognize(byte[] imageBytes, long timeoutMs);

  /** 一行识别结果。坐标为输入图片像素。 */
  final class Line {
    private final String text;
    private final int left;
    private final int top;
    private final int right;
    private final int bottom;

    public Line(String text, int left, int top, int right, int bottom) {
      this.text = text == null ? "" : text;
      this.left = left;
      this.top = top;
      this.right = right;
      this.bottom = bottom;
    }

    public String getText() { return text; }
    public int getLeft() { return left; }
    public int getTop() { return top; }
    public int getRight() { return right; }
    public int getBottom() { return bottom; }
  }

  /**
   * 一次识别的结果。两种状态互斥，由工厂方法保证：
   * <ul>
   *   <li>{@link #ok} —— 识别完成；{@code lines} 可能为空（= 图上没有文字，这是有效结论）
   *   <li>{@link #failure} —— 引擎/解码/超时失败；{@code error} 非空
   * </ul>
   */
  final class Result {
    private final boolean ok;
    private final String error;
    private final List<Line> lines;
    private final String engine;

    private Result(boolean ok, String error, List<Line> lines, String engine) {
      this.ok = ok;
      this.error = error == null ? "" : error;
      this.lines = lines == null ? java.util.Collections.<Line>emptyList() : lines;
      this.engine = engine == null ? "" : engine;
    }

    /** 识别完成（lines 可能为空）。 */
    public static Result ok(List<Line> lines, String engine) {
      return new Result(true, "", lines, engine);
    }

    /** 失败（引擎不可用 / 解码失败 / 超时 / 被中断）。 */
    public static Result failure(String error) {
      return new Result(false, error, null, "");
    }

    public boolean isOk() { return ok; }
    public String getError() { return error; }
    public List<Line> getLines() { return lines; }
    /** 引擎标识（写入结果头部，便于排查）；失败时为空串。 */
    public String getEngine() { return engine; }
  }
}

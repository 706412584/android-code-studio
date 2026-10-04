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

import android.content.Context;
import android.graphics.Bitmap;
import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.ToolInvoker;
import com.tom.rv2ide.ai.tool.ToolInvokerAware;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 动作级连拍：执行一次动作后，在多个时间点连续截图并<b>暂存</b>。
 *
 * <p><b>为什么需要连拍</b>：一次动作（点击/滑动/启动）之后的画面是动态演变的——
 * 单张截图只能看到某一瞬间，无法回答「动作有没有生效、有没有闪一下又恢复」。
 * 连拍 t0 / t+300 / t+800 能覆盖「立即 / 过渡 / 稳定」三个阶段。
 *
 * <p><b>核心约束</b>：连拍的 N 张原图<b>绝不逐张返回</b>。它们只在内部暂存
 * （{@link PhoneScreenshotStore}），本工具只返回：
 * <ol>
 *   <li>各帧相对 t0 的变化指标（数字）
 *   <li><b>一张</b>合成对比图（t0 与末帧并排 + 差异红框），若配置了基线则优先给「基线 vs t0」
 * </ol>
 * 需要深入时，再用 {@code phone_screenshot_compare source=last} 对比暂存帧。
 *
 * <p>动作本身可选：传入 {@code actionTool}/{@code actionArgs} 时，先经工具注册表
 * 调用该动作工具（如 phone_click），<b>紧接着</b>连拍——这样 t0 才是真正的「动作后立即」。
 * 不传动作则从调用时刻开始连拍。
 */
public final class PhoneActionCaptureTool extends BaseTool implements ToolInvokerAware {

  private static final Logger log = LoggerFactory.getLogger(PhoneActionCaptureTool.class);

  /** 默认连拍时间点（毫秒）。覆盖立即 / 过渡 / 稳定三阶段。 */
  private static final long[] DEFAULT_OFFSETS = {0L, 300L, 800L};

  private static final int MAX_FRAMES = 6;

  private final Context appContext;

  /**
   * 子工具调用入口。装配方在构建执行器后注入（见 {@link ToolInvokerAware}）。
   * 为 null 时子调用会明确报错，而不是退回直接 {@code execute} 绕过权限。
   */
  private volatile ToolInvoker toolInvoker;

  public PhoneActionCaptureTool(Context context) {
    this.appContext = context.getApplicationContext();
  }

  @Override
  public void setToolInvoker(ToolInvoker invoker) {
    this.toolInvoker = invoker;
  }

  @Override
  public String getName() {
    return ToolNames.PHONE_ACTION_CAPTURE;
  }

  @Override
  public String getDescription() {
    return "动作级连拍：可选先执行一次动作工具，然后在多个时间点（默认 0/300/800ms）连续截图。"
        + "原图只在内部暂存，<b>不逐张返回</b>；只返回一张合成对比图与帧间数字指标。"
        + "可选与项目基线对比（saveBaseline / baselineName）。"
        + "后续可用 phone_screenshot_compare source=last 深入对比暂存帧。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.SYSTEM;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.PHONE_CONTROL;
  }

  @Override
  public boolean needsConfirmation() {
    // 本工具可用 actionTool 驱动 phone_click 等有副作用的动作，等于绕过动作工具自身的确认门，
    // 因此必须与 phone_click 同级要求确认。
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "actionTool",
                    new JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "可选。先执行的动作工具名，如 phone_click / phone_swipe / launch_app"))
                .put(
                    "actionArgs",
                    new JSONObject()
                        .put("type", "object")
                        .put("description", "可选。动作工具的参数对象"))
                .put(
                    "offsetsMs",
                    new JSONObject()
                        .put("type", "array")
                        .put("description", "连拍时间点（毫秒），默认 [0,300,800]")
                        .put("items", new JSONObject().put("type", "number")))
                .put(
                    "tolerance",
                    new JSONObject()
                        .put(
                            "type",
                            "number")
                        .put(
                            "description",
                            "差异容差（每通道 0-255），默认 "
                                + PhoneScreenshotCompareTool.DEFAULT_TOLERANCE))
                .put(
                    "saveBaseline",
                    new JSONObject()
                        .put("type", "boolean")
                        .put("description", "把 t0 帧保存为项目基线，默认 false"))
                .put(
                    "baselineName",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "基线名，默认 default"))
                .put(
                    "compareToBaseline",
                    new JSONObject()
                        .put("type", "boolean")
                        .put("description", "若基线存在，额外做基线回归对比，默认 true"))
                .put(
                    "label",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "会话标签，写入文件名便于识别")))
        .put("required", new org.json.JSONArray());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    long[] offsets = parseOffsets(input.optJSONArray("offsetsMs"));
    int tolerance = (int) input.optDouble("tolerance", PhoneScreenshotCompareTool.DEFAULT_TOLERANCE);
    String label = input.optString("label", "").trim();
    String baselineName = input.optString("baselineName", "default").trim();
    boolean saveBaseline = input.optBoolean("saveBaseline", false);
    boolean compareToBaseline = input.optBoolean("compareToBaseline", true);
    String projectRoot = context == null ? "" : context.getHomePath();

    if (context != null) {
      context.reportProgress("动作级连拍");
    }

    // 清掉上一轮暂存，避免磁盘与内存堆积
    String previous = PhoneScreenshotStore.latestSession();
    if (!previous.isEmpty()) {
      PhoneScreenshotStore.clear(appContext, previous);
    }
    String session = PhoneScreenshotStore.newSession();

    // 可选：先执行动作
    String actionNote = "";
    String actionTool = input.optString("actionTool", "").trim();
    if (!actionTool.isEmpty()) {
      JSONObject actionArgs = input.optJSONObject("actionArgs");
      ToolResult actionResult = invokeAction(actionTool, actionArgs, context);
      if (actionResult == null) {
        return error("动作工具不存在或未注册: " + actionTool);
      }
      if (actionResult.isError()) {
        return error("动作执行失败（未进入连拍）: " + actionResult.getContent());
      }
      actionNote = "动作: " + actionTool + " 执行成功\n";
    }

    // 连拍并暂存
    List<PhoneScreenshotStore.Frame> captured = new ArrayList<>();
    long startNanos = System.nanoTime();
    for (int i = 0; i < offsets.length; i++) {
      long offset = offsets[i];
      long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
      long wait = offset - elapsedMs;
      if (wait > 0) {
        try {
          Thread.sleep(wait);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      PhoneScreenshotSource.Shot shot = PhoneScreenshotSource.capture(toolInvoker, context);
      if (!shot.ok) {
        if (captured.isEmpty()) {
          return error("连拍失败（首帧）: " + shot.error);
        }
        log.info("第 {} 帧截图失败: {}", i, shot.error);
        break;
      }
      Bitmap bitmap = PhoneScreenshotStore.decode(shot.file);
      if (bitmap == null) {
        log.warn("第 {} 帧解码失败: {}", i, shot.file);
        continue;
      }
      File staged =
          PhoneScreenshotStore.addFrame(
              appContext, session, offset, label.isEmpty() ? null : label, bitmap);
      if (staged != null) {
        captured.add(new PhoneScreenshotStore.Frame(offset, label, staged));
      }
    }

    if (captured.isEmpty()) {
      return error("连拍没有采集到任何帧。请确认 phone_screenshot 工具已注册且可用。");
    }

    // 指标：各帧相对 t0 的变化
    Bitmap first = PhoneScreenshotStore.decode(captured.get(0).file);
    if (first == null) {
      return error("首帧无法解码: " + captured.get(0).file);
    }
    List<PhoneScreenshotStore.DiffMetrics> frameDiffs = new ArrayList<>();
    for (int i = 1; i < captured.size(); i++) {
      Bitmap frame = PhoneScreenshotStore.decode(captured.get(i).file);
      if (frame == null) {
        frameDiffs.add(new PhoneScreenshotStore.DiffMetrics());
        continue;
      }
      frameDiffs.add(PhoneScreenshotStore.compare(first, frame, tolerance));
      frame.recycle();
    }

    // 基线对比
    File baselineFile = PhoneScreenshotStore.baselineFile(projectRoot, baselineName);
    PhoneScreenshotStore.DiffMetrics baselineDiff = null;
    if (saveBaseline) {
      PhoneScreenshotStore.saveBaseline(captured.get(0).file, baselineFile);
    } else if (compareToBaseline && baselineFile.exists()) {
      Bitmap baseline = PhoneScreenshotStore.decode(baselineFile);
      if (baseline != null) {
        baselineDiff = PhoneScreenshotStore.compare(baseline, first, tolerance);
        baseline.recycle();
      }
    }

    // 合成一张对比图：优先「基线 vs t0」，否则「t0 vs 末帧」
    Bitmap last = captured.size() > 1
        ? PhoneScreenshotStore.decode(captured.get(captured.size() - 1).file)
        : null;
    Bitmap comparison;
    String imageDesc;
    if (baselineDiff != null) {
      Bitmap baseline = PhoneScreenshotStore.decode(baselineFile);
      comparison =
          PhoneScreenshotStore.renderComparison(
              baseline, first, tolerance, "baseline:" + PhoneScreenshotStore.safeName(baselineName), "t0");
      if (baseline != null) {
        baseline.recycle();
      }
      imageDesc = "对比图: 基线 vs t0（右栏红=相对基线的变化）";
    } else if (last != null) {
      comparison =
          PhoneScreenshotStore.renderComparison(
              first, last, tolerance, "t0", "t+" + captured.get(captured.size() - 1).offsetMs + "ms");
      imageDesc = "对比图: t0 vs 末帧（右栏红=帧间变化）";
    } else {
      comparison = null;
      imageDesc = "";
    }

    // 组装文本
    StringBuilder sb = new StringBuilder();
    sb.append("动作级连拍完成\n");
    sb.append(actionNote);
    sb.append("会话: ").append(session).append('\n');
    sb.append("帧数: ").append(captured.size()).append('\n');
    sb.append("时间点: ").append(Arrays.toString(offsets)).append(" ms\n");
    sb.append("暂存: 原图已暂存，未返回；如需深入对比用 phone_screenshot_compare source=last\n");

    sb.append("\n帧间变化（相对 t0，容差 ").append(tolerance).append("）:\n");
    for (int i = 0; i < frameDiffs.size(); i++) {
      PhoneScreenshotStore.Frame frame = captured.get(i + 1);
      PhoneScreenshotStore.DiffMetrics m = frameDiffs.get(i);
      sb.append("  t+")
          .append(frame.offsetMs)
          .append("ms: ")
          .append(String.format(java.util.Locale.ROOT, "%.2f%% 变化", m.diffPercent))
          .append(", 包围盒 ")
          .append(m.boundingBox())
          .append('\n');
    }
    if (frameDiffs.isEmpty()) {
      sb.append("  (只有一帧)\n");
    }

    if (saveBaseline) {
      sb.append("\n已保存基线: ").append(baselineFile.getAbsolutePath()).append('\n');
    } else if (baselineDiff != null) {
      sb.append("\n基线回归对比（")
          .append(PhoneScreenshotStore.safeName(baselineName))
          .append("）:\n");
      sb.append(PhoneScreenshotStore.formatMetrics(baselineDiff, tolerance));
    } else if (compareToBaseline) {
      sb.append("\n(无基线可比；如需建立基线，传 saveBaseline=true 或先用 phone_baseline action=save)\n");
    }

    first.recycle();
    if (last != null) {
      last.recycle();
    }

    if (comparison == null) {
      return ok(sb.toString());
    }
    sb.append('\n').append(imageDesc);
    String base64 = PhoneScreenshotStore.encodeBase64Bounded(comparison);
    comparison.recycle();
    if (base64.isEmpty()) {
      return ok(sb.toString());
    }
    return ToolResult.withImage(getName(), sb.toString(), "image/png", base64);
  }

  private ToolResult invokeAction(String toolName, JSONObject args, ToolContext context) {
    ToolInvoker invoker = toolInvoker;
    if (invoker == null) {
      // 不退回直接 execute：那会绕过权限判定，与本次修复的目的相悖。
      return ToolResult.error("动作工具调用入口不可用（装配方未注入 ToolInvoker）: " + toolName);
    }
    try {
      return invoker.invoke(toolName, args, context);
    } catch (Exception e) {
      log.warn("动作工具执行异常: {}", toolName, e);
      return ToolResult.error("动作执行异常: " + e.getMessage());
    }
  }

  private static long[] parseOffsets(JSONArray array) {
    if (array == null || array.length() == 0) {
      return DEFAULT_OFFSETS.clone();
    }
    List<Long> values = new ArrayList<>();
    for (int i = 0; i < array.length() && values.size() < MAX_FRAMES; i++) {
      long value = (long) array.optDouble(i, -1);
      if (value >= 0) {
        values.add(value);
      }
    }
    if (values.isEmpty()) {
      return DEFAULT_OFFSETS.clone();
    }
    long[] result = new long[values.size()];
    for (int i = 0; i < values.size(); i++) {
      result[i] = values.get(i);
    }
    Arrays.sort(result);
    return result;
  }
}

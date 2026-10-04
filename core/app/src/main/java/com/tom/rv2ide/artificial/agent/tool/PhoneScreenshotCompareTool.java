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
import java.util.List;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 截图对比工具：帧间 diff / 基线 diff / 生成单张对比图。
 *
 * <p>三种来源（{@code source}）：
 * <ul>
 *   <li>{@code last} — 与最近一次动作连拍（{@link PhoneActionCaptureTool}）的帧对比
 *   <li>{@code baseline} — 与项目内 {@code .acs/baseline/<name>.png} 对比
 *   <li>{@code file} — 与指定 PNG 文件对比
 * </ul>
 * 对比对象（{@code against}）默认为「当前新截一张」。因此本工具可以独立使用：
 * 不依赖连拍工具，自己截当前画面再和基线/文件比。
 *
 * <p><b>输出约束</b>：无论比对几张，都只返回<b>一张</b>合成对比图（并排 + 差异红框）
 * 加数字指标，绝不把原始截图逐张塞进上下文。
 */
public final class PhoneScreenshotCompareTool extends BaseTool implements ToolInvokerAware {

  private static final Logger log = LoggerFactory.getLogger(PhoneScreenshotCompareTool.class);

  /** 默认差异容差（每通道 0-255）。吸收抗锯齿/压缩噪声。 */
  static final int DEFAULT_TOLERANCE = 16;

  /** 基线回归的默认判定阈值：变化像素占比超过它视为「回归」。 */
  static final double DEFAULT_REGRESSION_PERCENT = 1.0;

  private final Context appContext;

  /** 截图子调用入口；装配方在执行器构建后注入。见 {@link ToolInvokerAware}。 */
  private volatile ToolInvoker toolInvoker;

  public PhoneScreenshotCompareTool(Context context) {
    this.appContext = context.getApplicationContext();
  }

  @Override
  public void setToolInvoker(ToolInvoker invoker) {
    this.toolInvoker = invoker;
  }

  @Override
  public String getName() {
    return ToolNames.PHONE_SCREENSHOT_COMPARE;
  }

  @Override
  public String getDescription() {
    return "截图对比：把当前画面与最近一次动作连拍、项目基线、或指定图片比对，"
        + "返回一张合成对比图（左参考/中当前/右差异，变化处标红）与数字指标"
        + "（变化像素占比、平均/最大通道差、变化区域包围盒）。"
        + "source=baseline 时按 regressThresholdPercent 判定是否回归。"
        + "用途：验证 UI 改动是否只在预期区域生效、动作后画面是否出现异常变化。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.PHONE_CONTROL;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
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
                    "source",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "对比来源：last（最近连拍）/ baseline（项目基线）/ file")
                        .put(
                            "enum",
                            new org.json.JSONArray().put("last").put("baseline").put("file")))
                .put(
                    "baselineName",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "source=baseline 时的基线名，默认 default"))
                .put(
                    "path",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "source=file 时的参考 PNG 绝对路径"))
                .put(
                    "tolerance",
                    new JSONObject()
                        .put("type", "number")
                        .put("description", "差异容差（每通道 0-255），默认 " + DEFAULT_TOLERANCE))
                .put(
                    "regressThresholdPercent",
                    new JSONObject()
                        .put("type", "number")
                        .put(
                            "description",
                            "回归判定阈值（变化像素占比 %），默认 " + DEFAULT_REGRESSION_PERCENT)))
        .put("required", new org.json.JSONArray().put("source"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String source = input.optString("source", "").trim().toLowerCase();
    if (source.isEmpty()) {
      return error("source 不能为空（last / baseline / file）");
    }
    int tolerance = (int) input.optDouble("tolerance", DEFAULT_TOLERANCE);
    double threshold =
        input.optDouble("regressThresholdPercent", DEFAULT_REGRESSION_PERCENT);
    String projectRoot = context == null ? "" : context.getHomePath();

    if (context != null) {
      context.reportProgress("截图对比: " + source);
    }

    // 1) 解析参考图
    Bitmap reference;
    String referenceLabel;
    switch (source) {
      case "last":
        {
          String session = PhoneScreenshotStore.latestSession();
          List<PhoneScreenshotStore.Frame> frames = PhoneScreenshotStore.frames(session);
          if (frames.isEmpty()) {
            return error(
                "没有可用的连拍暂存。请先用 phone_action_capture 做一次动作级连拍，"
                    + "或改用 source=baseline / file。");
          }
          PhoneScreenshotStore.Frame first = frames.get(0);
          reference = PhoneScreenshotStore.decode(first.file);
          referenceLabel = "t+" + first.offsetMs + "ms (session " + session + ")";
          break;
        }
      case "baseline":
        {
          String name = input.optString("baselineName", "default").trim();
          File baseline = PhoneScreenshotStore.baselineFile(projectRoot, name);
          if (!baseline.exists()) {
            return error(
                "基线不存在: "
                    + baseline.getAbsolutePath()
                    + "。可用 phone_baseline 的 action=save 保存一张基线。"
                    + "现有基线: "
                    + PhoneScreenshotStore.listBaselines(projectRoot));
          }
          reference = PhoneScreenshotStore.decode(baseline);
          referenceLabel = "baseline:" + PhoneScreenshotStore.safeName(name);
          break;
        }
      case "file":
        {
          String path = input.optString("path", "").trim();
          if (path.isEmpty()) {
            return error("source=file 需要提供 path");
          }
          File file = new File(path);
          if (!file.exists()) {
            return error("参考图不存在: " + path);
          }
          reference = PhoneScreenshotStore.decode(file);
          referenceLabel = file.getName();
          break;
        }
      default:
        return error("不支持的 source: " + source + "（应为 last / baseline / file）");
    }

    if (reference == null) {
      return error("参考图无法解码（可能不是有效 PNG）。");
    }

    // 2) 截取当前画面
    PhoneScreenshotSource.Shot shot = PhoneScreenshotSource.capture(toolInvoker, context);
    if (!shot.ok) {
      reference.recycle();
      return error(shot.error);
    }
    Bitmap current = PhoneScreenshotStore.decode(shot.file);
    if (current == null) {
      reference.recycle();
      return error("当前截图无法解码: " + shot.file);
    }

    // 3) 比对
    PhoneScreenshotStore.DiffMetrics metrics =
        PhoneScreenshotStore.compare(reference, current, tolerance);
    Bitmap comparison =
        PhoneScreenshotStore.renderComparison(reference, current, tolerance, referenceLabel, "current");

    StringBuilder sb = new StringBuilder();
    sb.append("截图对比 (").append(source).append(")\n");
    sb.append("参考: ").append(referenceLabel).append('\n');
    sb.append("当前: ").append(shot.file.getName()).append('\n');
    sb.append(PhoneScreenshotStore.formatMetrics(metrics, tolerance));

    boolean regressed = metrics.diffPercent > threshold;
    if ("baseline".equals(source)) {
      sb.append("判定: ")
          .append(regressed ? "存在差异（超过阈值 " : "无显著差异（阈值 ")
          .append(threshold)
          .append("%）\n");
      if (regressed) {
        sb.append("变化集中在包围盒 ")
            .append(metrics.boundingBox())
            .append("；请确认这是预期的 UI 改动，否则视为回归。\n");
      }
    }

    reference.recycle();
    current.recycle();

    String base64 = comparison == null ? "" : PhoneScreenshotStore.encodeBase64Bounded(comparison);
    if (comparison != null) {
      comparison.recycle();
    }
    if (base64.isEmpty()) {
      return ok(sb + "\n(对比图渲染失败，仅返回指标)");
    }
    return ToolResult.withImage(getName(), sb.toString(), "image/png", base64);
  }
}

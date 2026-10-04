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

/**
 * 管理项目内的截图基线（{@code <projectRoot>/.acs/baseline/*.png}）。
 *
 * <p>基线放项目内而非应用私有目录，是为了：
 * <ul>
 *   <li>随项目走——换设备/清应用数据后基线不丢
 *   <li>可被 git 管理——UI 基线可以随代码一起评审、回滚
 *   <li>可人工替换——设计稿或上一版截图直接拷进来即可当基线
 * </ul>
 *
 * <p>动作：
 * <ul>
 *   <li>{@code save} — 截当前画面存为基线（可指定 name）
 *   <li>{@code list} — 列出已有基线
 *   <li>{@code delete} — 删除指定基线
 * </ul>
 */
public final class PhoneBaselineTool extends BaseTool implements ToolInvokerAware {

  private final Context appContext;

  /** 截图子调用入口；装配方在执行器构建后注入。见 {@link ToolInvokerAware}。 */
  private volatile ToolInvoker toolInvoker;

  public PhoneBaselineTool(Context context) {
    this.appContext = context.getApplicationContext();
  }

  @Override
  public void setToolInvoker(ToolInvoker invoker) {
    this.toolInvoker = invoker;
  }

  @Override
  public String getName() {
    return ToolNames.PHONE_BASELINE;
  }

  @Override
  public String getDescription() {
    return "管理截图回归基线（项目内 .acs/baseline/）。"
        + "action=save 截当前画面存为基线；action=list 列出基线；action=delete 删除基线。"
        + "基线可被 phone_screenshot_compare 与 phone_action_capture 用于回归对比。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.WRITE;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.PHONE_CONTROL;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "action",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "save / list / delete")
                        .put(
                            "enum",
                            new org.json.JSONArray().put("save").put("list").put("delete")))
                .put(
                    "name",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "基线名，默认 default")))
        .put("required", new org.json.JSONArray().put("action"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String action = input.optString("action", "").trim().toLowerCase();
    String name = PhoneScreenshotStore.safeName(input.optString("name", "default"));
    String projectRoot = context == null ? "" : context.getHomePath();

    if (projectRoot.isEmpty()) {
      return error("无法确定项目根目录，不能读写基线。");
    }

    switch (action) {
      case "save":
        return save(name, projectRoot, context);
      case "list":
        return list(projectRoot);
      case "delete":
        return delete(name, projectRoot);
      default:
        return error("不支持的 action: " + action + "（应为 save / list / delete）");
    }
  }

  private ToolResult save(String name, String projectRoot, ToolContext context) {
    if (context != null) {
      context.reportProgress("保存基线: " + name);
    }
    PhoneScreenshotSource.Shot shot = PhoneScreenshotSource.capture(toolInvoker, context);
    if (!shot.ok) {
      return error("截图失败，无法保存基线: " + shot.error);
    }
    File baseline = PhoneScreenshotStore.baselineFile(projectRoot, name);
    if (!PhoneScreenshotStore.saveBaseline(shot.file, baseline)) {
      return error("写入基线失败: " + baseline.getAbsolutePath());
    }
    return ok(
        "已保存基线: "
            + baseline.getAbsolutePath()
            + "\n后续可用 phone_screenshot_compare source=baseline baselineName="
            + name
            + " 做回归对比。");
  }

  private ToolResult list(String projectRoot) {
    List<String> names = PhoneScreenshotStore.listBaselines(projectRoot);
    File dir = PhoneScreenshotStore.baselineDir(projectRoot);
    if (names.isEmpty()) {
      return ok("当前没有基线。目录: " + dir.getAbsolutePath() + "\n可用 action=save 建立基线。");
    }
    StringBuilder sb = new StringBuilder();
    sb.append("基线目录: ").append(dir.getAbsolutePath()).append('\n');
    sb.append("共 ").append(names.size()).append(" 个:\n");
    for (String name : names) {
      sb.append("  - ").append(name).append('\n');
    }
    return ok(sb.toString());
  }

  private ToolResult delete(String name, String projectRoot) {
    File baseline = PhoneScreenshotStore.baselineFile(projectRoot, name);
    if (!baseline.exists()) {
      return error("基线不存在: " + baseline.getAbsolutePath());
    }
    if (!baseline.delete()) {
      return error("删除基线失败: " + baseline.getAbsolutePath());
    }
    return ok("已删除基线: " + baseline.getAbsolutePath());
  }
}

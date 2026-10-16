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

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 构建变体工具：列出 Android 模块的变体 / 切换指定模块的变体。
 *
 * <p><b>切换为什么慢</b>：变体选择的真实生效点在 tooling 端同步时，切换 = 触发一次
 * 完整项目重新同步（重建项目模型 + workspace + 索引）。因此本工具必须按长操作处理：
 * 默认等待上限 10 分钟、响应取消信号、失败/超时文案要说明「同步可能仍在后台进行」。
 *
 * <p>app 层工具（同 DiagnosticsTool）：需要 IWorkspace 与编辑器 Activity，
 * 而 ai-tool 模块零 Android 依赖，故经 {@link BuildVariantPort} 注入。
 */
public final class BuildVariantTool extends BaseTool {

  /** 默认等待上限：10 分钟（与 GradleBuildTool 一致，首次同步可能很慢）。 */
  static final long DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L;

  /** 最大等待上限：30 分钟。 */
  static final long MAX_TIMEOUT_MS = 30 * 60 * 1000L;

  @Override
  public String getName() {
    return ToolNames.BUILD_VARIANT;
  }

  @Override
  public String getDescription() {
    return "查询并切换 Android 模块的构建变体（debug / release 及 flavor）。"
        + "action=list（默认）：列出所有 Android 模块、每个模块的变体列表、当前选中项，"
        + "以及每个变体的 assemble 任务名与 applicationId；可带 module 只看一个模块。"
        + "action=select：把指定模块切换到指定变体，需同时给 module（如 \":app\"）与 variant（如 \"release\"）。"
        + "注意：切换会触发**完整的项目重新同步**（不是瞬时操作，通常数十秒、大项目可能数分钟），"
        + "同步期间不能构建；成功后请用返回的新 assemble 任务名重新构建——"
        + "此前的构建产物（APK）属于旧变体，不能继续沿用。"
        + "写操作，需要用户确认；只读模式下不可用。";
  }

  @Override
  public ToolCategory getCategory() {
    // 与 gradle_build / git_write 同级：切换改变项目构建配置并触发重型 Gradle 操作。
    // 分类决定只读模式下是否放行（ToolPermissionService.canExecuteTool），
    // 整包按写处理（list 也一并受限）——与 GitTool/GIT_WRITE 拆分的动机相同，
    // 但本工具按任务约定保持单工具 + action 参数。
    return ToolCategory.SYSTEM;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.GENERIC;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    // 与 category=SYSTEM 一致；list 虽只读，但单工具无法按 action 分级。
    return false;
  }

  @Override
  public boolean needsConfirmation() {
    // 切换会触发完整项目同步（重、改变后续构建行为）；确认模式下应经用户同意。
    // AUTO 模式下不拦截（见 ToolPermissionService）。
    return true;
  }

  @Override
  public boolean isConcurrencySafe() {
    // 切换与构建/同步互斥（tooling 端串行），且内部有「检查状态→触发→等待」的时序假设，
    // 并发执行会让前置检查失效。默认串行（返回 false）。
    return false;
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
                        .put("enum", new JSONArray().put("list").put("select"))
                        .put("description", "list=列出现状（默认）；select=切换变体"))
                .put(
                    "module",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "模块路径，如 \":app\"。select 时必填；list 时可选（只看该模块）"))
                .put(
                    "variant",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "目标变体名（如 release / debug）。select 时必填，可先用 list 查可用名"))
                .put(
                    "timeoutMs",
                    new JSONObject()
                        .put("type", "number")
                        .put(
                            "description",
                            "select 的等待上限（毫秒），默认 " + DEFAULT_TIMEOUT_MS
                                + "，最大 " + MAX_TIMEOUT_MS)));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    if (context == null) {
      return error("工具上下文缺失（内部错误）。");
    }
    // 跨项目守卫：变体走编辑器打开的项目的全局构建服务，会话绑定别的项目时必须拦。
    String mismatch = context.getProjectMismatchReason();
    if (!mismatch.isEmpty()) {
      return error(mismatch);
    }
    BuildVariantPort port = context.getBuildVariant();
    if (port == null) {
      return error("当前环境未接入项目变体能力（build_variant 端口未注入）。");
    }
    String reason = port.unavailableReason();
    if (!reason.isEmpty()) {
      return error(reason);
    }

    String action = input.optString("action", "list").trim().toLowerCase(Locale.US);
    switch (action) {
      case "list":
        return doList(port, input.optString("module", "").trim());
      case "select":
        return doSelect(port, input, context);
      default:
        return error("未知 action：\"" + action + "\"。可用：list / select。");
    }
  }

  // ---- list ----

  private ToolResult doList(BuildVariantPort port, String moduleFilter) {
    List<BuildVariantPort.Module> modules = port.listModules();
    if (!moduleFilter.isEmpty()) {
      List<BuildVariantPort.Module> matched = new ArrayList<>();
      for (BuildVariantPort.Module m : modules) {
        if (moduleFilter.equals(m.getProjectPath())) {
          matched.add(m);
        }
      }
      if (matched.isEmpty()) {
        return error("模块 \"" + moduleFilter + "\" 不存在。可用模块：" + pathsOf(modules));
      }
      modules = matched;
    }
    return ok(formatModules(modules));
  }

  /** 可用模块路径清单，用于「模块不存在」的错误文案。 */
  private static String pathsOf(List<BuildVariantPort.Module> modules) {
    if (modules.isEmpty()) {
      return "（无）";
    }
    StringBuilder sb = new StringBuilder();
    for (BuildVariantPort.Module m : modules) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append(m.getProjectPath());
    }
    return sb.toString();
  }

  /** 包内可见以便 JVM 单测（照 GradleBuildTool.describeFailure 的先例）。 */
  static String formatModules(List<BuildVariantPort.Module> modules) {
    if (modules.isEmpty()) {
      return "当前没有已同步的 Android 模块（是否还在 Gradle 同步中？）。"
          + "请等待项目同步完成后重试；若该项目不是 Android 项目，则没有构建变体。";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("Android 模块（").append(modules.size()).append(" 个）：\n");
    for (BuildVariantPort.Module m : modules) {
      sb.append('\n').append(m.getProjectPath())
          .append("（").append(m.isApplication() ? "application" : "library").append("）");
      if (!m.getNamespace().isEmpty()) {
        sb.append(" namespace=").append(m.getNamespace());
      }
      sb.append('\n');
      sb.append("  当前选中：")
          .append(m.getSelectedVariant().isEmpty() ? "（未知，可能未完成同步）" : m.getSelectedVariant())
          .append('\n');
      if (m.getVariants().isEmpty()) {
        sb.append("  变体：尚未解析（可能还在 Gradle 同步中）\n");
        continue;
      }
      for (BuildVariantPort.Variant v : m.getVariants()) {
        sb.append("  - ").append(v.getName());
        if (v.getName().equals(m.getSelectedVariant())) {
          sb.append("（当前）");
        }
        if (!v.getAssembleTaskName().isEmpty()) {
          sb.append("，assemble: ").append(v.getAssembleTaskName());
        }
        if (!v.getApplicationId().isEmpty()) {
          sb.append("，applicationId: ").append(v.getApplicationId());
        }
        sb.append('\n');
      }
    }
    return sb.toString();
  }

  // ---- select ----

  private ToolResult doSelect(BuildVariantPort port, JSONObject input, ToolContext context) {
    String module = input.optString("module", "").trim();
    String variant = input.optString("variant", "").trim();
    if (module.isEmpty() || variant.isEmpty()) {
      return error("select 需要同时提供 module（如 \":app\"）与 variant（如 \"release\"）。"
          + "可先用 action=list 查看可用模块与变体名。");
    }
    long timeout = Math.min(input.optLong("timeoutMs", DEFAULT_TIMEOUT_MS), MAX_TIMEOUT_MS);
    if (timeout <= 0) {
      timeout = DEFAULT_TIMEOUT_MS;
    }

    BuildVariantPort.Result result = port.switchTo(module, variant, timeout, context.getCancellation());
    return ok(formatSwitchResult(result, timeout));
  }

  /** 包内可见以便 JVM 单测。 */
  static String formatSwitchResult(BuildVariantPort.Result result, long timeoutMs) {
    switch (result.getStatus()) {
      case SWITCHED:
        StringBuilder sb = new StringBuilder();
        sb.append("已将模块 ").append(result.getModulePath())
            .append(" 的构建变体切换为 ").append(result.getVariantName())
            .append("，项目已重新同步。\n");
        if (!result.getAssembleTaskName().isEmpty()) {
          sb.append("新的 assemble 任务：").append(result.getAssembleTaskName()).append('\n');
        }
        sb.append("注意：此前构建的 APK 属于旧变体，需要重新构建；")
            .append("项目索引与语言服务器可能仍在后台重建，稍后再使用诊断/符号搜索更可靠。");
        return sb.toString();
      case ALREADY_SELECTED:
        return "模块 " + result.getModulePath() + " 当前已是 " + result.getVariantName()
            + "，无需切换。";
      case FAILED:
        return "切换失败：" + result.getReason();
      case TIMED_OUT:
        return "切换超时（等待 " + (timeoutMs / 1000) + " 秒）：" + result.getReason();
      case CANCELLED:
        return "已停止等待。注意：已触发的项目同步不会因此中止，可能仍在后台进行——"
            + "稍后可用 action=list 确认变体是否已生效。";
      default:
        return "未知结果状态。";
    }
  }
}

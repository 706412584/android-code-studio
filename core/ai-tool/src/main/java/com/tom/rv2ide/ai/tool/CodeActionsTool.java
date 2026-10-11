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
import java.io.File;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * IDE 原生 Java 代码动作工具：类型系统感知的代码生成/重构（就地写回）。
 *
 * <p><b>为什么用 IDE 原生动作而不是让模型手写</b>：getter/setter、override、import、
 * throws 这些结构由编译器类型信息生成，import 正确、泛型正确、缩进正确；模型手写
 * 容易漏 import、写错签名。这正是「集成在 IDE 里」相对通用 agent 的优势。
 *
 * <p><b>与 format_code 的分工</b>：格式化是「文件级、无位置」；本工具需要**位置**
 * （大多数动作在光标处符号上生效）与 action 名。
 *
 * <p><b>实现走端口</b>：本模块零 Android/LSP 依赖，动作执行（编译器/Rewrite/写盘）
 * 由 app 层 {@link CodeActionsPort} 实现完成。
 */
public final class CodeActionsTool extends BaseTool {

  /** 单个动作的分析超时（ms）。编译 + AST 改写，比格式化重；首次编译较慢给足 30s。 */
  static final long TIMEOUT_MS = 30_000L;

  /** 第一期放行的 action 名（与适配器 supportedActions 一致）。 */
  static final String ACTION_IMPLEMENT_ABSTRACT = "implement_abstract_methods";
  static final String ACTION_CREATE_MISSING_METHOD = "create_missing_method";
  static final String ACTION_ADD_IMPORT = "add_import";
  static final String ACTION_ADD_THROWS = "add_throws";
  static final String ACTION_REMOVE_UNUSED_THROWS = "remove_unused_throws";
  static final String ACTION_SUPPRESS_UNCHECKED = "suppress_unchecked";

  @Override
  public String getName() {
    return ToolNames.CODE_ACTIONS;
  }

  @Override
  public String getDescription() {
    return "调用 IDE 原生的 Java 代码动作（类型系统感知），而不是手写代码。"
        + "参数：file_path（文件）、action（动作名）、line/column（位置，行号从 1 开始，"
        + "column 默认 1）、argument（部分动作需要，如 add_import 的类名）。"
        + "支持的动作："
        + "implement_abstract_methods（为类补全所有未实现的抽象方法）、"
        + "create_missing_method（在调用点生成被调用方法的骨架，含 TODO）、"
        + "add_import（添加 import，argument 传完全限定类名）、"
        + "add_throws（为方法补 throws 声明，argument 传异常类名）、"
        + "remove_unused_throws（移除方法上未被抛出的 throws）、"
        + "suppress_unchecked（给方法加 @SuppressWarnings(\"unchecked\")）。"
        + "**优先用它代替手写这些结构**：动作基于编译器类型信息生成，import/泛型/缩进都正确，"
        + "手写容易漏 import、写错签名。"
        + "仅 Java（.java）；需项目已在编辑器中打开（语言服务器随项目会话启动）。"
        + "生成后的代码含 TODO 占位，仍需你用 file_edit 补全方法体。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.WRITE;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.WRITE;
  }

  @Override
  public boolean needsConfirmation() {
    // 会改代码，与 format_code 同级。
    return true;
  }

  @Override
  public boolean shouldRecordDiff() {
    return true;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    return false;
  }

  @Override
  public boolean isConcurrencySafe() {
    // 编译器有全局锁（JavaCompilerService 的 SynchronizedTask），并发无益。
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
                    "file_path",
                    new JSONObject().put("type", "string").put("description", "要执行动作的 Java 源文件"))
                .put(
                    "action",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "动作名")
                        .put(
                            "enum",
                            new JSONArray()
                                .put(ACTION_IMPLEMENT_ABSTRACT)
                                .put(ACTION_CREATE_MISSING_METHOD)
                                .put(ACTION_ADD_IMPORT)
                                .put(ACTION_ADD_THROWS)
                                .put(ACTION_REMOVE_UNUSED_THROWS)
                                .put(ACTION_SUPPRESS_UNCHECKED)))
                .put(
                    "line",
                    new JSONObject()
                        .put("type", "integer")
                        .put("description", "行号，从 1 开始（与 file_read/grep 一致）"))
                .put(
                    "column",
                    new JSONObject()
                        .put("type", "integer")
                        .put("description", "列号，从 1 开始（可省略，默认 1）"))
                .put(
                    "argument",
                    new JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "动作的可选参数：add_import 传完全限定类名（如 java.util.List）；"
                                + "add_throws 可传异常类名；其余动作不需要")))
        // required 必须与 properties 同级，不能嵌进 properties 内部。
        // 嵌进去会让它被当成一个名为 required 的**属性**（值却是数组），严格校验的
        // provider 直接以 400 拒绝整个 tools 数组——**所有工具**（不止本工具）全部
        // 不可用。真机实测 agnes provider 报 "Tool 11 function has invalid 'parameters'
        // schema: ['file_path', 'action'] is not of type 'object', 'boolean'"。
        .put("required", new JSONArray().put("file_path").put("action"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    CodeActionsPort port = context == null ? null : context.getCodeActions();
    if (port == null) {
      return error("当前环境未接入 IDE 语言服务器，无法执行代码动作。");
    }
    String reason = port.unavailableReason();
    if (!reason.isEmpty()) {
      return error(reason);
    }

    String filePath = input.optString("file_path", "").trim();
    if (filePath.isEmpty()) {
      return error("缺少 file_path 参数。");
    }
    File file;
    try {
      file = FileToolPathPolicy.resolve(context, filePath);
    } catch (java.io.IOException e) {
      return error("路径无效：" + e.getMessage());
    }
    if (!file.exists() || !file.isFile()) {
      return error("文件不存在：" + filePath);
    }
    if (!port.isSupported(file.getAbsolutePath())) {
      return error("该文件类型没有 Java 代码动作（仅 .java）：" + file.getName());
    }

    String action = input.optString("action", "").trim().toLowerCase(Locale.US);
    if (action.isEmpty()) {
      return error("缺少 action 参数。可用：" + join(port.supportedActions()));
    }
    if (!port.supportedActions().contains(action)) {
      return error("不支持的 action：\"" + action + "\"。可用：" + join(port.supportedActions()));
    }

    // 位置：模型给 1 基，端口要 0 基（与 lsp_navigate 的先例一致）。
    int line1 = input.optInt("line", 1);
    int column1 = input.optInt("column", 1);
    int line = Math.max(0, line1 - 1);
    int column = Math.max(0, column1 - 1);

    // add_import 的参数校验放工具层：能立即反馈，不必等端口往返。
    String argument = input.optString("argument", "").trim();
    if (ACTION_ADD_IMPORT.equals(action) && !argument.contains(".")) {
      return error("add_import 需要 argument 传完全限定类名（如 java.util.List）。");
    }

    CodeActionsPort.Result result =
        port.perform(file.getAbsolutePath(), action, line, column, argument, TIMEOUT_MS);

    if (result.isUnavailable()) {
      return error("语言服务器当前不可用：" + result.getUnavailableReason());
    }
    if (result.isUnsupportedAction()) {
      return error(result.getFailureReason());
    }
    if (result.isTimedOut()) {
      return error(result.getFailureReason());
    }
    if (result.isFailed()) {
      return error(result.getFailureReason());
    }
    if (!result.isChanged()) {
      return ok(result.getSummary().isEmpty() ? "未发生改动。" : result.getSummary());
    }

    StringBuilder sb = new StringBuilder();
    sb.append(result.getSummary().isEmpty() ? "代码动作已完成。" : result.getSummary());
    if (result.getChangedChars() > 0) {
      sb.append("（").append(result.getChangedChars()).append(" 字符）");
    }
    if (!result.getChangedFiles().isEmpty()) {
      sb.append("\n改动文件：");
      sb.append(join(result.getChangedFiles()));
    }
    return ok(ToolResult.truncateContent(sb.toString()));
  }

  private static String join(java.util.List<String> values) {
    StringBuilder sb = new StringBuilder();
    for (String v : values) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append(v);
    }
    return sb.toString();
  }
}

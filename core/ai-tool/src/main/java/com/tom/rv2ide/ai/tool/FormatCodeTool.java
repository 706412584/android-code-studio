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
import java.io.IOException;
import org.json.JSONObject;

/**
 * 代码格式化（经 IDE 语言服务器）。
 *
 * <p>手机上改完代码无法手动对齐缩进/换行，让模型自己去猜格式规范也不可靠。
 * 直接用语言服务器的 formatter（Java 走 google-java-format，Kotlin 走 KSL）。
 *
 * <p>会**就地写回文件**，属写类操作（需确认、记录 diff）。
 */
public final class FormatCodeTool extends BaseTool {

  static final long TIMEOUT_MS = 20_000L;

  @Override
  public String getName() {
    return ToolNames.FORMAT_CODE;
  }

  @Override
  public String getDescription() {
    return "用 IDE 语言服务器格式化一个源文件（Java 走 google-java-format，Kotlin 走 KSL），"
        + "**就地写回**。参数：file_path。"
        + "改完代码后用它对齐格式，比手动调整或让模型猜缩进可靠。"
        + "仅 Java / Kotlin 支持；需项目已在编辑器中打开。";
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
    return true;
  }

  @Override
  public boolean shouldRecordDiff() {
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put("file_path", new JSONObject().put("type", "string").put("description", "要格式化的源文件")))
        .put("required", new org.json.JSONArray().put("file_path"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    CodeFormatPort port = context == null ? null : context.getCodeFormat();
    if (port == null) {
      return error("当前环境未接入 IDE 语言服务器，无法格式化。");
    }
    String reason = port.unavailableReason();
    if (!reason.isEmpty()) {
      return error("语言服务器当前不可用：" + reason + "。格式化需要项目已在编辑器中打开。");
    }

    File file;
    try {
      file = FileToolPathPolicy.resolve(context, input.optString("file_path", ""));
    } catch (IOException e) {
      return error("路径无效：" + e.getMessage());
    }
    if (!file.isFile()) {
      return error("文件不存在：" + input.optString("file_path"));
    }
    if (!port.isSupported(file.getAbsolutePath())) {
      return error("该文件类型没有格式化器（仅 Java / Kotlin）：" + file.getName());
    }

    CodeFormatPort.Result result = port.format(file.getAbsolutePath(), TIMEOUT_MS);
    if (result.isUnavailable()) {
      return error("语言服务器不可用：" + result.getUnavailableReason());
    }
    if (result.isFailed()) {
      String hint = result.isTimedOut() ? "（可稍后重试，首次较慢）" : "";
      return error("格式化失败：" + result.getFailureReason() + hint);
    }

    String display = display(context, file);
    if (!result.isChanged()) {
      // 「无需改动」是成功，不是失败——否则模型会以为工具坏了而反复重试。
      return ok(display + " 已符合格式规范，无需改动。");
    }
    return ok("已格式化 " + display + "（改动 " + result.getChangedChars() + " 个字符）。");
  }

  private static String display(ToolContext context, File file) {
    try {
      return FileToolPathPolicy.displayPath(context.getHomePath(), file);
    } catch (IOException e) {
      return file.getAbsolutePath();
    }
  }
}

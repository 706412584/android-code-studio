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

import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ShellBackendRegistry;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import org.json.JSONObject;

/**
 * 向当前聚焦的输入框输入文本：{@code input text}。
 *
 * <p><b>两个必须让模型知道的限制</b>（否则会静默输入错误内容，比报错更糟）：
 *
 * <ol>
 *   <li><b>只支持 ASCII，不支持中文/emoji 等非 ASCII 字符</b>。{@code input text} 的底层
 *       是按 ASCII 键码注入的，非 ASCII 字符会被丢弃或变成乱码。这里<b>主动拦截</b>并
 *       给出明确错误，而不是把一段乱码打进被测应用——后者会让模型以为「输入成功」，
 *       在错误的界面上继续测试。
 *   <li><b>空格必须转义成 {@code %s}</b>。{@code input} 命令把参数按空格切分，直接传
 *       带空格的文本会变成多个参数。本工具<b>自动</b>把空格替换为 {@code %s}，调用方
 *       只需给正常文本。
 * </ol>
 *
 * <p>换行/制表符同样不被 {@code input text} 支持：换行请用
 * {@code phone_global_action ENTER}，制表用 {@code phone_global_action TAB}。
 *
 * <p>文本还需经过 shell 转义：本工具用单引号包裹并对内嵌单引号做 {@code '\''} 处理，
 * 因此文本里的 {@code $ & ; | * ~} 等 shell 元字符不会被执行或展开。
 *
 * <p>需要 adb 级权限（Shizuku 后端）；未启用时返回明确错误。
 */
public final class PhoneInputTextTool extends BaseTool {

  private static final long TIMEOUT_MS = 15_000L;

  /** 单次输入长度上限，避免构造出超长命令。 */
  private static final int MAX_LENGTH = 500;

  private final ShellBackendRegistry registry;

  public PhoneInputTextTool(ShellBackendRegistry registry) {
    this.registry = registry;
  }

  @Override
  public String getName() {
    return ToolNames.PHONE_INPUT_TEXT;
  }

  @Override
  public String getDescription() {
    return "向当前聚焦的输入框输入文本（等价 adb shell input text）。"
        + "限制：**只支持 ASCII 字符，不支持中文、emoji 等非 ASCII**（传入非 ASCII 会直接报错，"
        + "不会静默输入乱码）；空格会被自动转义为 %s，直接给正常文本即可；"
        + "不支持换行/制表符，换行请用 phone_global_action 的 ENTER，制表用 TAB。"
        + "输入前请先用 phone_click_view 点中目标输入框使其获得焦点。"
        + "需要 adb 级权限（Shizuku 后端）。";
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
    // 与 shell_execute 同级：执行 adb 级命令（input text），需经确认门。
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
                    "text",
                    new JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "要输入的文本。仅支持 ASCII；空格无需手动转义（会自动变成 %s）；"
                                + "不支持换行/制表符；最长 "
                                + MAX_LENGTH
                                + " 字符。")))
        .put("required", new org.json.JSONArray().put("text"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    if (!input.has("text")) {
      return error("必须提供 text。");
    }
    String text = input.optString("text", "");
    if (text.isEmpty()) {
      return error("text 不能为空。");
    }
    if (text.length() > MAX_LENGTH) {
      return error("text 过长（" + text.length() + " 字符），上限 " + MAX_LENGTH + "。");
    }

    // 逐字符检查：非 ASCII 直接拒绝，并指出第一个问题字符的位置。
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c > 127) {
        return error(
            "input text 只支持 ASCII 字符，不支持中文/emoji 等非 ASCII（位置 "
                + i
                + " 处的字符 '"
                + c
                + "'）。\n"
                + "可选做法：改用 ASCII 文本；或让被测应用提供测试入口（如 Intent extra、"
                + "测试专用 Activity）；或在设备上安装 ADBKeyboard 输入法后用 "
                + "phone_global_action 切换输入法再输入。");
      }
      if (c == '\n' || c == '\r') {
        return error("input text 不支持换行。请用 phone_global_action 的 ENTER 键提交/换行。");
      }
      if (c == '\t') {
        return error("input text 不支持制表符。请用 phone_global_action 的 TAB 键。");
      }
    }

    String escaped = escapeForInputText(text);

    if (context != null) {
      context.reportProgress("输入文本: " + abbreviate(text, 40));
    }

    PhoneUiSelector.Exec exec =
        PhoneUiSelector.exec(registry, "input text " + escaped, TIMEOUT_MS);
    if (!exec.ok) {
      return error(exec.error);
    }
    return ok("已输入文本（" + text.length() + " 字符）。\n原文: " + abbreviate(text, 80));
  }

  /**
   * 把文本转成可安全交给 {@code input text} 的单个 shell 参数。
   *
   * <p>两步：
   * <ol>
   *   <li>空格 → {@code %s}（input 命令自身把 {@code %s} 还原成空格）
   *   <li>整串用单引号包裹，内嵌单引号转义为 {@code '\''}，防止 shell 解释元字符
   * </ol>
   */
  static String escapeForInputText(String text) {
    String withSpaces = text.replace(" ", "%s");
    // 单引号包裹：shell 内单引号之间的一切都是字面量，唯一的例外是单引号本身，
    // 用 '\'' 收尾-转义-重开即可。
    return "'" + withSpaces.replace("'", "'\\''") + "'";
  }

  private static String abbreviate(String value, int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(0, max) + "…";
  }
}

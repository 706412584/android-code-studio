/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from LineCode Pro (https://github.com/LangLang03/LineCodePro),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.tool.mcp;

import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ExceptionUtils;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import org.json.JSONObject;

/**
 * 把远程 MCP 工具包装成本地 {@link BaseTool}，使模型能像调用内置工具一样调用它。
 *
 * <p><b>为什么用适配器而不是改工具层</b>：{@code ToolRegistry} 本来就只要求
 * 「实现 ToolInfo + 能执行」，MCP 工具与内置工具在这个抽象下没有区别。
 * 适配器让 MCP 完全落在工具层之内，agent 循环、权限判定、卡片渲染都不需要知道
 * 这个工具是远程的。
 *
 * <p><b>工具名前缀</b>：注册时加上 {@code mcpx_} 前缀（见 {@code ToolNames}）。
 * 远程 server 的工具名可能与本应用的内置工具**撞名**（都叫 {@code file_read}），
 * 不加前缀会让内置工具被静默覆盖——模型以为在写本地文件，实际调用了远程服务。
 *
 * <p><b>权限</b>：MCP 工具一律声明 {@link #needsConfirmation()} 为 true。
 * 远程工具的行为不可预知（可能删数据、发请求），而用户配置它时未必想过这一点；
 * 默认要求确认比默认放行更安全。
 *
 * <p>本类不引用 Android 类型，可单测。
 */
public final class McpToolAdapter extends BaseTool {

  /** 注册到本地时使用的名字前缀。 */
  public static final String PREFIX = "mcpx_";

  private final McpClient client;
  private final McpToolInfo info;
  private final String localName;
  private final String serverLabel;

  /**
   * @param client 已配置的 MCP 客户端
   * @param info server 侧的工具描述
   * @param serverLabel server 的展示名，用于工具描述与错误信息里指明来源
   */
  public McpToolAdapter(McpClient client, McpToolInfo info, String serverLabel) {
    this.client = client;
    this.info = info;
    this.localName = PREFIX + sanitize(info.getName());
    this.serverLabel = serverLabel == null ? "" : serverLabel;
  }

  /**
   * 把远程工具名规整为本地可用名。
   *
   * <p>模型对工具名的字符集有隐含预期（下划线、字母、数字），而 MCP 允许更宽的命名
   * （点号、斜杠、空格）。这里把非 {@code [A-Za-z0-9_]} 的字符换成下划线，
   * 避免生成一个模型难以稳定复述的名字。
   */
  static String sanitize(String rawName) {
    if (rawName == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(rawName.length());
    for (int i = 0; i < rawName.length(); i++) {
      char c = rawName.charAt(i);
      if (Character.isLetterOrDigit(c) || c == '_') {
        sb.append(c);
      } else {
        sb.append('_');
      }
    }
    return sb.toString();
  }

  /** 本地注册名（带前缀）。 */
  public String getLocalName() {
    return localName;
  }

  /** server 侧的原始名，调用时需要回传。 */
  public String getRemoteName() {
    return info.getName();
  }

  @Override
  public String getName() {
    return localName;
  }

  @Override
  public String getDescription() {
    String description = info.getDescription();
    if (serverLabel.isEmpty()) {
      return description;
    }
    // 标明来源：用户可能同时接多个 server，出问题时需要知道是哪一个。
    return description.isEmpty()
        ? "（来自 MCP server: " + serverLabel + "）"
        : description + "\n（来自 MCP server: " + serverLabel + "）";
  }

  @Override
  public ToolCategory getCategory() {
    // 远程工具可能读写任何东西，保守归为 WRITE：只读模式下不放行。
    return ToolCategory.WRITE;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.GENERIC;
  }

  @Override
  public boolean needsConfirmation() {
    // 远程工具行为不可预知，且用户配置时未必想到这一层。默认要求确认。
    return true;
  }

  @Override
  public JSONObject getParameters() {
    return info.getInputSchema();
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    if (context != null) {
      context.reportProgress("调用 MCP 工具 " + info.getName());
    }
    try {
      String output = client.callTool(info.getName(), input);
      if (output == null || output.trim().isEmpty()) {
        // 空输出要明说，否则模型会以为调用没发生而重试。
        return ok("（MCP 工具 " + info.getName() + " 没有返回文本内容。）");
      }
      return ok(output);
    } catch (Exception e) {
      ExceptionUtils.restoreInterrupt(e);
      return error("MCP 调用失败：" + ExceptionUtils.describeException(e));
    }
  }
}

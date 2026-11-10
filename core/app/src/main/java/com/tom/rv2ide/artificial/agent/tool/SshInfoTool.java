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
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import com.tom.rv2ide.ssh.SshComponents;
import com.tom.rv2ide.ssh.SshProfile;
import com.tom.rv2ide.ssh.SshProfileStore;
import com.tom.rv2ide.ssh.SshSessionRegistry;
import org.json.JSONObject;

/**
 * 报告当前 SSH 连接的事实：配置、连接状态、远端 {@code $HOME}。
 *
 * <p><b>为什么需要它</b>：{@code ssh} 只是 shell 后端的其中一个选项，模型从提示词里只看到
 * 一句「在远端服务器上执行命令」。它不知道<b>连的是哪台机器</b>、<b>哪个用户</b>、
 * 远端<b>工作目录</b>在哪——于是只能猜：把本机路径（{@code /data/data/.../ACSProjects/X}）
 * 拿去远端用，必然 `cd` 失败；或者猜远端装了哪些工具，失败后反复重试。
 *
 * <p>本工具把这些变成可查的事实。<b>只读且不触发连接</b>：报告状态不该为了「看一眼」
 * 付一次 1-3 秒的 SSH 握手（也避免在用户没打算用远端时凭空建立起连接）。
 */
public final class SshInfoTool extends BaseTool {

  @Override
  public String getName() {
    return ToolNames.SSH_INFO;
  }

  @Override
  public String getDescription() {
    return "查询当前 SSH 后端的配置与连接状态：激活配置（名称/主机/端口/用户名/远端工作区）、"
        + "是否已连接、远端 $HOME。"
        + "用 backend=\"ssh\" 执行命令前先调它，可以一次拿到远端主机的真实路径，"
        + "不用猜（猜错会让命令在远端 cd 失败，报错指向不明）。只读，且不会主动建立连接。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.GENERIC;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    // 纯读取内存/偏好状态，不碰网络、不碰磁盘。
    return true;
  }

  @Override
  public boolean isConcurrencySafe() {
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    // 无参数：需要「查哪条」的场景不存在——后端只认一条激活配置。
    return new JSONObject().put("type", "object").put("properties", new JSONObject());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    SshSessionRegistry registry = SshComponents.sessions();
    if (registry == null) {
      return error("SSH 组件未初始化（IDE 尚未完成启动）。请稍后重试。");
    }
    SshProfileStore profiles = SshComponents.profiles();
    if (profiles == null) {
      return error("SSH 配置未初始化（IDE 尚未完成启动）。请稍后重试。");
    }

    SshProfile active = profiles.active();
    boolean connected = isConnectedFor(registry, context);
    String remoteHome = remoteHomeFor(registry, context);

    StringBuilder sb = new StringBuilder();

    if (active == null) {
      // 未配置时也要明确回报：模型据此知道「ssh 后端现在不能用」，而不是猜。
      sb.append("SSH 未配置。请在 AI 设置 → 工具与权限 → SSH 连接里添加一条连接。\n");
      sb.append("当前无法用 backend=\"ssh\" 执行命令。\n");
      return ok(sb.toString());
    }

    sb.append("SSH 已配置：\n");
    sb.append("- 配置名: ").append(active.getName()).append('\n');
    sb.append("- 主机: ").append(active.getHost()).append(':').append(active.getPort()).append('\n');
    sb.append("- 用户名: ").append(active.getUsername()).append('\n');
    sb.append("- 认证方式: ")
        .append(active.getAuth() instanceof com.tom.rv2ide.ssh.SshAuthRef.Password
            ? "密码" : "私钥")
        .append('\n');
    // 远端工作区为空表示「用远端 $HOME」，必须说清——否则模型会以为没配置工作目录。
    String workspace = active.getRemoteWorkspacePath();
    sb.append("- 远端工作区: ")
        .append(workspace == null || workspace.trim().isEmpty() ? "（未配置，使用远端 $HOME）" : workspace)
        .append('\n');

    sb.append("- 连接状态: ").append(connected ? "已连接" : "未连接").append('\n');
    if (connected) {
      sb.append("- 远端 $HOME: ").append(remoteHome == null ? "（未知）" : remoteHome).append('\n');
    } else {
      // 未连接时说明「为什么」以及「用它会怎样」——避免模型在这种情况下反复重试。
      String lastError = registry.getLastError();
      if (lastError != null && !lastError.trim().isEmpty()) {
        sb.append("- 最近一次连接失败: ").append(lastError).append('\n');
      }
      sb.append("（执行 backend=\"ssh\" 的命令时会自动尝试连接。）\n");
    }

    sb.append("\n提示：远端路径与手机上的路径无关。给 shell_execute 传 cwd 时用远端工作区路径，")
        .append("不要用 /data/data/... 这类本机路径。");
    return ok(sb.toString());
  }

  /** 是否有该项目的活跃会话；不触发连接。 */
  private static boolean isConnectedFor(SshSessionRegistry registry, ToolContext context) {
    String projectPath = context == null ? "" : context.getHomePath();
    return registry.isConnected(projectPath);
  }

  /** 远端 $HOME；只在已连接时拿得到（未连接返回 null）。 */
  private static String remoteHomeFor(SshSessionRegistry registry, ToolContext context) {
    String projectPath = context == null ? "" : context.getHomePath();
    com.tom.rv2ide.ssh.RemoteSshConnection connection = registry.peek(projectPath);
    return connection == null ? null : connection.getRemoteHome();
  }
}
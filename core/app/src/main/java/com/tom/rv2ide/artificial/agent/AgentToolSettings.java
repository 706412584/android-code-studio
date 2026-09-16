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

package com.tom.rv2ide.artificial.agent;

import android.content.Context;
import android.content.SharedPreferences;
import com.tom.rv2ide.ai.tool.ToolSettingsPort;
import java.util.Collections;
import java.util.Set;

/**
 * 把 IDE 的偏好设置适配成工具层需要的 {@link ToolSettingsPort}。
 *
 * <p>工具层刻意不依赖 Android，配置通过这个窄接口注入。本类负责读取实际的偏好值，
 * 因此是「工具层不碰 Android」这条边界的落点。
 *
 * <p>权限模式默认 {@code confirm}：AI 修改用户代码属于有副作用的操作，
 * 默认需要确认比默认放行更安全。用户可在 AI 设置里切换为自动。
 */
public final class AgentToolSettings implements ToolSettingsPort {

  private static final String PREFS_NAME = "ai_agent_tools";

  /** 权限模式键。 */
  public static final String KEY_PERMISSION_MODE = "permission_mode";

  /** shell 后端键。 */
  public static final String KEY_SHELL_BACKEND = "shell_backend";

  /** agent 模式键：开启后 ChatFragment 走工具调用循环而非单发生成。 */
  public static final String KEY_AGENT_MODE = "agent_mode";

  /** 危险工具（shell / 安装 / 启动）确认状态键。 */
  public static final String KEY_DANGEROUS_CONFIRMED = "dangerous_confirmed";

  /** 危险工具的一次性确认闸门，由 UI 层注入（如 ChatFragment 弹窗）。 */
  public interface DangerousToolConfirmer {
    /**
     * 询问用户是否允许执行危险工具。可在任意线程调用；实现方负责切到主线程，
     * 并在用户答复（或取消）后返回。
     *
     * @param toolName 将要执行的危险工具名
     * @param args 工具参数原始 JSON 文本，供 UI 展示
     * @return true 表示放行本次及之后的危险工具调用
     */
    boolean confirm(String toolName, String args);
  }

  /** 确认闸门；null 表示无法询问用户（后台运行），危险工具将被拒绝。 */
  private volatile DangerousToolConfirmer confirmer;

  /** 本次运行已确认标志；每次 run 开始时重置，之后跨工具保持。 */
  private volatile boolean runConfirmed;

  private final SharedPreferences prefs;

  public AgentToolSettings(Context context) {
    this.prefs = context.getApplicationContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  public void setDangerousToolConfirmer(DangerousToolConfirmer confirmer) {
    this.confirmer = confirmer;
  }

  /** 一次新的 agent 运行开始时调用，重置跨会话的确认记忆。 */
  public void beginRun() {
    runConfirmed = false;
  }

  /** agent 模式是否开启。默认关闭，保持旧路径行为。 */
  public boolean isAgentModeEnabled() {
    return prefs.getBoolean(KEY_AGENT_MODE, false);
  }

  public void setAgentModeEnabled(boolean enabled) {
    prefs.edit().putBoolean(KEY_AGENT_MODE, enabled).apply();
  }

  /** 供 executor 线程调用：危险工具是否可放行（含询问用户）。 */
  @Override
  public boolean areDangerousToolsConfirmed() {
    if (runConfirmed || prefs.getBoolean(KEY_DANGEROUS_CONFIRMED, false)) {
      return true;
    }
    return false;
  }

  /**
   * 带工具名与参数询问用户。
   *
   * <p>只有这一条路径会弹窗——{@link #areDangerousToolsConfirmed()} 不再主动询问，
   * 否则执行器拿不到工具上下文，只能显示「某个危险工具」，用户无从判断。
   */
  @Override
  public boolean confirmDangerousTool(String toolName, String arguments) {
    if (areDangerousToolsConfirmed()) {
      return true;
    }
    DangerousToolConfirmer c = confirmer;
    if (c == null) {
      // 没有 UI 可问（后台运行）→ 拒绝，宁可让工具失败也不无确认执行
      return false;
    }
    if (c.confirm(toolName, arguments)) {
      runConfirmed = true;
      return true;
    }
    return false;
  }

  /** 确认危险工具；确认状态跨会话保持。 */
  public void confirmDangerousTools(boolean confirmed) {
    prefs.edit().putBoolean(KEY_DANGEROUS_CONFIRMED, confirmed).apply();
  }

  @Override
  public String getPermissionMode() {
    String mode = prefs.getString(KEY_PERMISSION_MODE, PERMISSION_CONFIRM);
    if (PERMISSION_AUTO.equals(mode) || PERMISSION_READONLY.equals(mode)) {
      return mode;
    }
    return PERMISSION_CONFIRM;
  }

  /** 设置权限模式。非法值会被忽略。 */
  public void setPermissionMode(String mode) {
    if (PERMISSION_AUTO.equals(mode)
        || PERMISSION_CONFIRM.equals(mode)
        || PERMISSION_READONLY.equals(mode)) {
      prefs.edit().putString(KEY_PERMISSION_MODE, mode).apply();
    }
  }

  @Override
  public Set<String> getEnabledToolNames() {
    // 空集合表示不限制，即全部内置工具可用。
    return Collections.emptySet();
  }

  @Override
  public String getShellBackendId() {
    return prefs.getString(KEY_SHELL_BACKEND, "termux");
  }

  /** 设置 shell 后端 id。 */
  public void setShellBackendId(String backendId) {
    if (backendId != null && !backendId.isEmpty()) {
      prefs.edit().putString(KEY_SHELL_BACKEND, backendId).apply();
    }
  }
}

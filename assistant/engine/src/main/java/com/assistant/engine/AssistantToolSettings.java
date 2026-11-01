/*
 * This file is part of AndroidCodeStudio.
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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.assistant.engine;

import android.content.Context;
import android.content.SharedPreferences;
import com.tom.rv2ide.ai.tool.DangerousToolDecision;
import com.tom.rv2ide.ai.tool.RunApprovedRuleStore;
import com.tom.rv2ide.ai.tool.ToolPermissionRule;
import com.tom.rv2ide.ai.tool.ToolSettingsPort;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 独立助手的 {@link ToolSettingsPort} 实现——**照 ACS 的 {@code AgentToolSettings} 的语义**。
 *
 * <p><b>为什么必须有这个类</b>：不给 {@code ConversationRunner} 传它时，内核退回
 * {@link ToolSettingsPort#defaults()}，而那个默认实现的 {@code getPermissionMode()}
 * 返回 {@link ToolSettingsPort#PERMISSION_AUTO}。于是
 * {@code ToolPermissionService.needsConfirmation()} 恒为 false，
 * {@code file_delete} / {@code shell_execute} 这类危险工具**直接执行、不问用户**——
 * 而设置页的权限开关写着「危险工具逐次确认」，界面与实际行为相反，这是最坏的一种：
 * 用户以为有保护。
 *
 * <p><b>三道闸门与 ACS 一致</b>（判定顺序由 {@code ToolExecutor.confirmViaRules} 决定）：
 * <ol>
 *   <li>全局放行 {@link #areDangerousToolsConfirmed()}，等价于关掉确认；
 *   <li>持久化规则 {@link #hasDangerousToolRule(String)}（「始终允许」），按工具 + 参数粒度；
 *   <li>运行内记忆，按会话隔离（{@link RunApprovedRuleStore}）。
 * </ol>
 *
 * <p><b>没有 UI 可问时拒绝而不是放行</b>：{@code confirmer} 为 null（例如后台运行、
 * Activity 已销毁）时 {@link #confirmDangerousTool} 返回 false。宁可让工具失败，
 * 也不能因为「弹不出窗」就把危险操作静默放过去。
 *
 * <p><b>线程</b>：{@code confirmDangerousTool} / {@code askUserQuestion} 会被工具执行
 * 线程调用，且是阻塞式的（等用户点完才返回）。实现方（宿主）负责切主线程弹窗并唤醒。
 * 串行化用 {@link #confirmationLock}：工具可能并发执行，不加锁会出现两个重叠弹窗，
 * 且先答复的结果被后答复的覆盖。
 */
public final class AssistantToolSettings implements ToolSettingsPort {

  private static final String PREFS_NAME = "ai_agent_tools";
  private static final String KEY_PERMISSION_MODE = "permission_mode";
  private static final String KEY_SHELL_BACKEND = "shell_backend";
  private static final String KEY_DANGEROUS_CONFIRMED = "dangerous_confirmed";
  private static final String KEY_DANGEROUS_RULES = "dangerous_rules";

  /** 危险工具的一次性确认闸门，由宿主注入（弹窗）。可在任意线程调用。 */
  public interface DangerousToolConfirmer {
    /**
     * 询问用户是否放行危险工具。
     *
     * <p>实现方若需要「始终允许」，应自行调用
     * {@link AssistantToolSettings#rememberDangerousToolRule(String)}——
     * 只有实现方拿得到用户点的是哪个按钮。
     *
     * @param toolName 工具规范名
     * @param args 工具参数原始 JSON（供界面展示「要执行什么」）
     * @return true 表示放行本次调用
     */
    boolean confirm(String toolName, String args);
  }

  /** 提问闸门，由宿主注入（复用对话页的提问面板）。 */
  public interface QuestionAsker {
    /**
     * 就若干道选择题询问用户。
     *
     * @return 与题目等长的答案；null 表示用户取消或没有 UI 可问
     */
    java.util.List<String> ask(java.util.List<Question> questions);
  }

  private volatile DangerousToolConfirmer confirmer;
  private volatile QuestionAsker asker;

  private final RunApprovedRuleStore runApprovedRules = new RunApprovedRuleStore();

  /** 确认串行化锁（见类注释）。 */
  private final Object confirmationLock = new Object();

  private final SharedPreferences prefs;

  public AssistantToolSettings(Context context) {
    this.prefs =
        context
            .getApplicationContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  public void setDangerousToolConfirmer(DangerousToolConfirmer confirmer) {
    this.confirmer = confirmer;
  }

  public void setQuestionAsker(QuestionAsker asker) {
    this.asker = asker;
  }

  // ---- askUserQuestion ----

  @Override
  public java.util.List<String> askUserQuestion(java.util.List<Question> questions) {
    QuestionAsker a = asker;
    if (a == null) {
      // 没有 UI 可问。返回 null 而不是空列表：空列表会被误读成「用户全选了空」。
      return null;
    }
    return a.ask(questions);
  }

  // ---- 运行内授权记忆 ----

  /** 一次新的运行开始时调用，清除**该会话**的运行内授权记忆。 */
  public void beginRun(String conversationId) {
    synchronized (confirmationLock) {
      runApprovedRules.clear(conversationId);
    }
  }

  @Override
  public void clearRunApprovedRules(String conversationId) {
    synchronized (confirmationLock) {
      runApprovedRules.clear(conversationId);
    }
  }

  // ---- 三道闸门 ----

  @Override
  public boolean areDangerousToolsConfirmed() {
    return prefs.getBoolean(KEY_DANGEROUS_CONFIRMED, false);
  }

  /** 设置全局放行（关掉确认弹窗）。 */
  public void setDangerousToolsConfirmed(boolean confirmed) {
    prefs.edit().putBoolean(KEY_DANGEROUS_CONFIRMED, confirmed).apply();
  }

  @Override
  public boolean hasDangerousToolRule(String ruleKey) {
    if (ruleKey == null || ruleKey.isEmpty()) {
      return false;
    }
    if (areDangerousToolsConfirmed()) {
      return true;
    }
    return prefs.getStringSet(KEY_DANGEROUS_RULES, Collections.emptySet()).contains(ruleKey);
  }

  @Override
  public void rememberDangerousToolRule(String ruleKey) {
    if (ruleKey == null || ruleKey.isEmpty()) {
      return;
    }
    Set<String> existing = getDangerousToolRules();
    if (!existing.add(ruleKey)) {
      return;
    }
    // 必须写一份新集合：getStringSet 返回的实例不保证是拷贝，改原集合再 put 同一引用
    // 可能不触发落盘。
    prefs.edit().putStringSet(KEY_DANGEROUS_RULES, existing).apply();
  }

  /** 已持久化的授权规则快照，供设置页展示与撤销。 */
  public Set<String> getDangerousToolRules() {
    return new LinkedHashSet<>(prefs.getStringSet(KEY_DANGEROUS_RULES, Collections.emptySet()));
  }

  /** 清空全部授权规则（设置页的「重置授权」）。 */
  public void clearDangerousToolRules() {
    prefs.edit().remove(KEY_DANGEROUS_RULES).apply();
    synchronized (confirmationLock) {
      runApprovedRules.clearAll();
    }
  }

  @Override
  public boolean confirmDangerousTool(String toolName, String arguments) {
    return confirmDangerousTool(null, toolName, arguments);
  }

  /**
   * 询问用户是否放行危险工具，运行内授权按**会话**隔离。
   *
   * <p>「查规则 → 询问 → 记结果」整体持锁：同一会话的并发调用因此排队，第二个调用
   * 进入时能看到第一个刚写入的运行内规则，不会弹出第二个对话框。
   */
  @Override
  public boolean confirmDangerousTool(
      String conversationId, String toolName, String arguments) {
    synchronized (confirmationLock) {
      if (areDangerousToolsConfirmed()) {
        return true;
      }
      String ruleKey = ToolPermissionRule.keyFor(toolName, arguments);
      if (runApprovedRules.contains(conversationId, ruleKey)) {
        return true;
      }
      DangerousToolConfirmer c = confirmer;
      if (c == null) {
        // 没有 UI 可问（后台运行 / Activity 已销毁）→ 拒绝。
        return false;
      }
      if (!c.confirm(toolName, arguments)) {
        return false;
      }
      // 记入该会话的运行内记忆：避免同一条命令在长任务里被反复询问。
      runApprovedRules.approve(conversationId, ruleKey);
      return true;
    }
  }

  /**
   * 敏感档确认：**逐次询问、不可记忆**。
   *
   * <p>刻意跳过全局放行、持久化规则、运行内记忆三道捷径——这类动作按下后退不回来，
   * 保护对象是「模型别乱按」，因此每次都真的弹窗，且答复不写入任何记忆。
   */
  @Override
  public boolean confirmSensitiveTool(
      String conversationId, String toolName, String arguments) {
    synchronized (confirmationLock) {
      DangerousToolConfirmer c = confirmer;
      if (c == null) {
        // 敏感档尤其不能因「无法询问」而放行。
        return false;
      }
      return c.confirm(toolName, arguments);
    }
  }

  /**
   * 处理一次三档答复，由 UI 层在用户点击后调用。
   *
   * @return 是否放行本次调用
   */
  public boolean applyDecision(
      String toolName, String arguments, DangerousToolDecision decision) {
    if (decision == null || decision == DangerousToolDecision.DENY) {
      return false;
    }
    if (decision == DangerousToolDecision.ALLOW_ALWAYS) {
      rememberDangerousToolRule(ToolPermissionRule.keyFor(toolName, arguments));
    }
    return true;
  }

  // ---- 偏好 ----

  @Override
  public String getPermissionMode() {
    String mode = prefs.getString(KEY_PERMISSION_MODE, PERMISSION_CONFIRM);
    if (PERMISSION_AUTO.equals(mode) || PERMISSION_READONLY.equals(mode)) {
      return mode;
    }
    // 非法值（含 null、空串）一律当「确认」，不能降级成 AUTO。
    return PERMISSION_CONFIRM;
  }

  /**
   * 设置权限模式；非法值被忽略。
   *
   * <p>与设置页的 {@code PermissionPrefs} 共用同一份偏好（{@code ai_agent_tools}
   * 的 {@code permission_mode}），因此界面上改完立即生效。
   */
  public void setPermissionMode(String mode) {
    if (PERMISSION_AUTO.equals(mode)
        || PERMISSION_CONFIRM.equals(mode)
        || PERMISSION_READONLY.equals(mode)) {
      prefs.edit().putString(KEY_PERMISSION_MODE, mode).apply();
    }
  }

  @Override
  public Set<String> getEnabledToolNames() {
    // 空集合 = 不限制（全部内置工具可用）。
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

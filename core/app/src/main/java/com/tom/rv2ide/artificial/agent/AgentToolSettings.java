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
import com.tom.rv2ide.ai.tool.DangerousToolDecision;
import com.tom.rv2ide.ai.tool.RunApprovedRuleStore;
import com.tom.rv2ide.ai.tool.ToolPermissionRule;
import com.tom.rv2ide.ai.tool.ToolSettingsPort;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 把 IDE 的偏好设置适配成工具层需要的 {@link ToolSettingsPort}。
 *
 * <p>工具层刻意不依赖 Android，配置通过这个窄接口注入。本类负责读取实际的偏好值，
 * 因此是「工具层不碰 Android」这条边界的落点。
 *
 * <p>权限模式默认 {@code confirm}：AI 修改用户代码属于有副作用的操作，
 * 默认需要确认比默认放行更安全。用户可在 AI 设置里切换为自动。
 *
 * <p><b>授权分三级</b>（取代原先的单一全局布尔）：
 * <ol>
 *   <li>全局放行——{@link #areDangerousToolsConfirmed()}，等价于关掉确认；
 *   <li>规则放行——{@link #hasDangerousToolRule(String)}，按「工具 + 参数粒度」持久化，
 *       例如只放行 {@code git} 开头的 shell 命令；
 *   <li>本次运行放行——{@link #beginRun(String)} 之后的内存记忆，进程重启即失效。
 *       该记忆**按会话隔离**（见 {@link RunApprovedRuleStore}），避免并行会话互相放行。
 * </ol>
 */
public final class AgentToolSettings implements ToolSettingsPort {

  private static final String PREFS_NAME = "ai_agent_tools";

  /** 权限模式键。 */
  public static final String KEY_PERMISSION_MODE = "permission_mode";

  /** shell 后端键。 */
  public static final String KEY_SHELL_BACKEND = "shell_backend";

  /** agent 模式键：开启后 ChatFragment 走工具调用循环而非单发生成。 */
  public static final String KEY_AGENT_MODE = "agent_mode";

  /**
   * 全局危险工具放行键。
   *
   * <p>保留是为了兼容旧版本已写入的值，以及提供「我知道自己在做什么，别再问我」的出口。
   * 新代码应优先写规则（{@link #rememberDangerousToolRule}）。
   */
  public static final String KEY_DANGEROUS_CONFIRMED = "dangerous_confirmed";

  /** 授权规则集合键。元素形如 {@code shell_execute\u0000git}。 */
  public static final String KEY_DANGEROUS_RULES = "dangerous_rules";

  /** 危险工具的一次性确认闸门，由 UI 层注入（如 ChatFragment 弹窗）。 */
  public interface DangerousToolConfirmer {
    /**
     * 询问用户是否允许执行危险工具。可在任意线程调用；实现方负责切到主线程，
     * 并在用户答复（或取消）后返回。
     *
     * <p>实现方若需要「始终允许」，应自行调用
     * {@link AgentToolSettings#rememberDangerousToolRule(String)}——只有实现方拿得到
     * 用户点击的按钮，也只有它能决定写什么规则。
     *
     * @param toolName 将要执行的危险工具名（规范名）
     * @param args 工具参数原始 JSON 文本，供 UI 展示
     * @return true 表示放行本次调用
     */
    boolean confirm(String toolName, String args);
  }

  /** 提问回调，由 UI 层注入（如 FloatingAssistantView 弹窗）。 */
  public interface QuestionAsker {
    /**
     * 就若干道选择题询问用户。可在任意线程调用；实现方负责切到主线程，
     * 并在用户答复（或取消）后返回。
     *
     * @param questions 待问的题目（1-4 道）
     * @return 答案列表；null 表示取消
     */
    java.util.List<String> ask(java.util.List<Question> questions);
  }

  /** 确认闸门；null 表示无法询问用户（后台运行），危险工具将被拒绝。 */
  private volatile DangerousToolConfirmer confirmer;

  /** 提问闸门；null 表示无法询问用户，提问将返回 null。 */
  private volatile QuestionAsker asker;

  /**
   * 各会话「本次运行」已放行的规则键（含该会话内确认过的），按会话 id 分桶。
   *
   * <p>逻辑抽在纯 Java 的 {@link RunApprovedRuleStore}（见该类 KDoc 说明为何必须按会话
   * 隔离），本类只负责把它接到 Android 偏好上。读写仍在 {@link #confirmationLock} 内，
   * 与确认弹窗的串行化共用一把锁。
   */
  private final RunApprovedRuleStore runApprovedRules = new RunApprovedRuleStore();

  /**
   * 确认串行化锁。
   *
   * <p>工具可能并发执行（{@code isConcurrencySafe} 为 true 的工具会被并行调度）。
   * 若不加锁，两个工具同时发现「没有规则」就会各弹一个对话框：用户看到两个重叠弹窗，
   * 且先答复的那个结果会被后答复的覆盖。串行化后第二个调用进入时会看到第一个刚写入的
   * 运行内规则，从而直接放行，不再重复询问。
   */
  private final Object confirmationLock = new Object();

  private final SharedPreferences prefs;

  public AgentToolSettings(Context context) {
    this.prefs = context.getApplicationContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  public void setDangerousToolConfirmer(DangerousToolConfirmer confirmer) {
    this.confirmer = confirmer;
  }

  public void setQuestionAsker(QuestionAsker asker) {
    this.asker = asker;
  }

  @Override
  public java.util.List<String> askUserQuestion(java.util.List<Question> questions) {
    QuestionAsker a = asker;
    if (a == null) {
      return null;
    }
    return a.ask(questions);
  }

  /** 一次新的 agent 运行开始时调用，重置跨会话的确认记忆。 */
  public void beginRun() {
    beginRun(null);
  }

  /**
   * 一次新的 agent 运行开始时调用，只重置**该会话**的确认记忆。
   *
   * <p>只清目标会话的桶：并发会话各有各的运行，清掉别的会话已批准的规则会让它重复弹窗。
   */
  public void beginRun(String conversationId) {
    synchronized (confirmationLock) {
      runApprovedRules.clear(conversationId);
    }
  }

  /** agent 模式是否开启。默认关闭，保持旧路径行为。 */
  public boolean isAgentModeEnabled() {
    return prefs.getBoolean(KEY_AGENT_MODE, false);
  }

  public void setAgentModeEnabled(boolean enabled) {
    prefs.edit().putBoolean(KEY_AGENT_MODE, enabled).apply();
  }

  /** 供 executor 线程调用：危险工具是否可全局放行。 */
  @Override
  public boolean areDangerousToolsConfirmed() {
    return prefs.getBoolean(KEY_DANGEROUS_CONFIRMED, false);
  }

  @Override
  public boolean hasDangerousToolRule(String ruleKey) {
    if (ruleKey == null || ruleKey.isEmpty()) {
      return false;
    }
    if (areDangerousToolsConfirmed()) {
      return true;
    }
    // 持久化规则（「始终允许」）跨会话有效。
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
    // 必须写一份新集合：SharedPreferences 对 getStringSet 返回的实例不保证拷贝，
    // 直接改原集合再 put 同一引用可能不触发落盘。
    prefs.edit().putStringSet(KEY_DANGEROUS_RULES, existing).apply();
  }

  /** 已持久化的授权规则键快照，供设置页展示与撤销。 */
  public Set<String> getDangerousToolRules() {
    return new LinkedHashSet<>(prefs.getStringSet(KEY_DANGEROUS_RULES, Collections.emptySet()));
  }

  /** 撤销一条授权规则。 */
  public void forgetDangerousToolRule(String ruleKey) {
    if (ruleKey == null || ruleKey.isEmpty()) {
      return;
    }
    Set<String> existing = getDangerousToolRules();
    if (!existing.remove(ruleKey)) {
      return;
    }
    prefs.edit().putStringSet(KEY_DANGEROUS_RULES, existing).apply();
  }

  /** 清空全部授权规则（设置页的「重置授权」）。 */
  public void clearDangerousToolRules() {
    prefs.edit().remove(KEY_DANGEROUS_RULES).apply();
    synchronized (confirmationLock) {
      runApprovedRules.clearAll();
    }
  }

  /** 丢弃某会话的运行内授权记忆（会话运行结束时调用）。 */
  @Override
  public void clearRunApprovedRules(String conversationId) {
    synchronized (confirmationLock) {
      runApprovedRules.clear(conversationId);
    }
  }

  /**
   * 带工具名与参数询问用户（不区分会话）。
   *
   * <p>保留给不传会话的调用方；带会话的路径见
   * {@link #confirmDangerousTool(String, String, String)}。
   */
  @Override
  public boolean confirmDangerousTool(String toolName, String arguments) {
    return confirmDangerousTool(null, toolName, arguments);
  }

  /**
   * 带工具名与参数询问用户，运行内授权按**会话**隔离。
   *
   * <p>只有这一条路径会弹窗——{@link #areDangerousToolsConfirmed()} 不再主动询问，
   * 否则执行器拿不到工具上下文，只能显示「某个危险工具」，用户无从判断。
   *
   * <p>整个「查规则 → 询问 → 记结果」过程在同一把锁内，因此同一会话的并发调用会排队：
   * 第二个调用进入时能看到第一个刚写入的运行内规则，不会弹出第二个对话框。
   * 不同会话各有各的桶，互不影响。
   *
   * @param conversationId 产生该调用的会话 id；null/空串归入无会话桶
   */
  @Override
  public boolean confirmDangerousTool(
      String conversationId, String toolName, String arguments) {
    String ruleKey = ToolPermissionRule.keyFor(toolName, arguments);
    synchronized (confirmationLock) {
      if (areDangerousToolsConfirmed()) {
        return true;
      }
      if (runApprovedRules.contains(conversationId, ruleKey)) {
        return true;
      }
      DangerousToolConfirmer c = confirmer;
      if (c == null) {
        // 没有 UI 可问（后台运行）→ 拒绝，宁可让工具失败也不无确认执行
        return false;
      }
      if (!c.confirm(toolName, arguments)) {
        return false;
      }
      // 该会话的运行内记忆：避免同一命令在长任务里被反复询问。
      runApprovedRules.approve(conversationId, ruleKey);
      return true;
    }
  }

  /**
   * 处理一次三档答复。
   *
   * <p>由 UI 层在用户点击按钮后调用：{@code ALLOW_ALWAYS} 会写持久化规则，
   * {@code ALLOW_ONCE} 只记本次运行，{@code DENY} 什么都不记。
   *
   * @return 是否放行本次调用
   */
  public boolean applyDecision(String toolName, String arguments, DangerousToolDecision decision) {
    if (decision == null || decision == DangerousToolDecision.DENY) {
      return false;
    }
    if (decision == DangerousToolDecision.ALLOW_ALWAYS) {
      rememberDangerousToolRule(ToolPermissionRule.keyFor(toolName, arguments));
    }
    return true;
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

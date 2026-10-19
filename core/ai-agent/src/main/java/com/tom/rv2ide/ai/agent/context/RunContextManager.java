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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.agent.context;

import com.tom.rv2ide.ai.agent.conversation.ConversationCompaction;
import com.tom.rv2ide.ai.agent.conversation.ConversationHistory;
import com.tom.rv2ide.ai.protocol.ModelMessage;
import com.tom.rv2ide.ai.protocol.ToolModelMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次 agent 运行期间的上下文预算：跟踪用量，并在每次请求前裁剪。
 *
 * <p><b>为什么在循环内部裁剪，而不只是入口做一次</b>：一轮对话里消息持续增长
 * （每轮追加助手消息与工具结果）。入口处算好的历史在若干轮后可能已经超出窗口——
 * 尤其是一次读入大文件之后。只在入口裁剪的循环，长任务跑到一半就会因超限失败。
 *
 * <p><b>裁剪与压缩的分工</b>：
 * <ul>
 *   <li><b>裁剪</b>（本类，每次请求前）：本地、无网络、立即生效，代价是永久丢信息。
 *   <li><b>压缩</b>（{@link ConversationCompaction} + {@link ContextCompactor}，入口一次）：
 *       需要一次模型调用，产出摘要并**持久化**，把"每次运行都重新丢弃同一段"变成"只摘要一次"。
 * </ul>
 * 两者互补：压缩让削减持久化，裁剪保证运行中途不会超限。
 *
 * <p><b>不可裁的两条消息</b>：系统提示词与**本次用户请求**。
 * {@link ContextTrimmer} 从最旧开始丢，而本次用户请求恰好排在历史之后、助手轮次之前——
 * 放任裁剪会把它丢掉，模型便"不知道自己在做什么"。这是裁剪最危险的失败模式，
 * 因此这里把两条消息钉住，只在它们之外做取舍。
 */
public final class RunContextManager {

  /** 上下文窗口未配置时 {@link #historyBudget()} 的取值。 */
  private static final int UNLIMITED = Integer.MAX_VALUE;

  /**
   * 用量变化通知。
   *
   * <p>存在的理由：界面要在输入区显示「当前上下文占用 / 窗口大小」，而用量只在
   * 循环内部（每次请求前裁剪、每轮记录服务端 usage）才更新。没有这个回调，UI
   * 只能在一轮运行结束后拿到最终值，中途看不到占用增长。
   *
   * <p>实现方需容忍来自循环线程的调用，且**必须快速返回**——它跑在发请求的路径上。
   */
  public interface UsageListener {
    void onUsage(int usedTokens, int contextSize);
  }

  private final TokenUsageTracker tracker;

  /** 系统提示词 + 工具定义的估算成本；这些不占历史预算但确实占窗口。 */
  private final int overheadTokens;

  /** 用量回调，可为 null。 */
  private final UsageListener usageListener;

  public RunContextManager(TokenUsageTracker tracker, int overheadTokens) {
    this(tracker, overheadTokens, null);
  }

  public RunContextManager(
      TokenUsageTracker tracker, int overheadTokens, UsageListener usageListener) {
    this.tracker = tracker;
    this.overheadTokens = Math.max(0, overheadTokens);
    this.usageListener = usageListener;
  }

  /** 本次运行的系统提示词 + 工具定义估算成本。 */
  public int overheadTokens() {
    return overheadTokens;
  }

  /** 上下文窗口大小；未配置时返回 0。 */
  public int contextSize() {
    return tracker.getContextSize();
  }

  /**
   * 当前用量。
   *
   * <p><b>不要再加 overheadTokens</b>：{@link #fit} 与 {@code maybeCompact} 在
   * {@code tracker.record(...)} 时**已经把开销算进去了**（那里传的是
   * {@code estimate(...) + overheadTokens}）。这里再加一次会重复计数，界面上的
   * 占用比例会比实际偏高一大截。
   */
  public int currentTokens() {
    return tracker.currentTokens();
  }

  /**
   * 裁剪消息列表以适配窗口。
   *
   * @param messages 完整消息列表：{@code [系统提示词, 历史..., 本次请求, 本轮轮次...]}
   * @param historyEnd 本次用户请求的下标（即历史区间的结束位置）
   * @return 可直接发给模型的消息列表；首条仍是系统提示词
   */
  public List<ModelMessage> fit(List<ModelMessage> messages, int historyEnd) {
    if (messages == null || messages.isEmpty()) {
      return new ArrayList<>();
    }

    // 图片数量限制与 token 预算**独立**：即使窗口未配置（预算不限），也不能让几十张
    // 截图的 base64 同时驻留内存——那是 512MB 堆 OOM 的直接成因（真机复现过）。
    // 就地修改：调用方持有的列表同步变小，被剥离的 base64 立刻可回收。
    ConversationHistory.limitImages(
        messages, ConversationHistory.MAX_IMAGES_IN_HISTORY, historyEnd);

    int budget = historyBudget();
    if (budget == UNLIMITED) {
      // 窗口未配置：无从判断，只记录用量供调用方参考。
      //
      // 这里刻意**不**调 notifyUsage()：走到这一支意味着 contextSize <= 0，
      // 而 notifyUsage() 在 size <= 0 时直接返回，调了也是空转。UI 那边拿不到
      // 比例会显示「占用未知」，这是正确行为——没有分母就不该编一个比例出来。
      tracker.record(TokenEstimator.estimate(messages), 0);
      return new ArrayList<>(messages);
    }

    return fitToBudget(messages, historyEnd, budget);
  }

  /**
   * 反应式收缩：服务端已报上下文超限（400 context_length 等）后的激进裁剪。
   *
   * <p><b>与 {@link #fit} 的分工</b>：fit 是事前预防（按预算裁到阈值内），本方法是事后
   * 补救——请求已经被服务端拒绝，说明实际用量超出（常见于本地估算低估：图片按张固定
   * 估算、各服务商分词差异）。此时按**当前估算的一半**重建消息列表，给重试留出余量。
   *
   * <p>保留规则与 fit 一致：系统提示词、本次用户请求与最近轮次优先保留；
   * 裁剪以工具调用组为单位（见 {@link ContextTrimmer}）。
   *
   * <p><b>就地修改传入列表</b>：调用方（{@code AgentSession}）持有的就是这条列表，
   * 收缩后直接用它重试——不换对象，避免调用方还要替换引用。
   *
   * @return 是否发生了实际收缩（无变化时调用方不应重试）
   */
  public boolean shrinkForOverflow(List<ModelMessage> messages, int historyEnd) {
    if (messages == null || messages.size() <= 1) {
      return false;
    }
    int current = TokenEstimator.estimate(messages) + overheadTokens;
    // 目标取「当前估算的一半」与「绝对预算的一半」的较小者。
    //
    // 只用 current/2 的漏洞：本地估算低估（正是超限的常见成因——图片按张固定估算、
    // 分词差异）时 current 远小于 contextSize，current/2 可能仍大于 historyBudget，
    // fitToBudget 返回的列表与上一次循环裁过的结果相同 → 判定「未收缩」→ 不重试。
    // 那恰是最需要重试的场景。取绝对预算兜底后，收缩幅度以「窗口的一半」为下界保证。
    int absolute = historyBudget();
    int target = absolute == UNLIMITED ? Math.max(1, current / 2)
            : Math.max(1, Math.min(current / 2, absolute / 2));
    List<ModelMessage> shrunk = fitToBudget(messages, historyEnd, target);
    if (shrunk.size() >= messages.size()) {
      return false;
    }
    messages.clear();
    messages.addAll(shrunk);
    notifyUsage();
    return true;
  }

  private List<ModelMessage> fitToBudget(
      List<ModelMessage> messages, int historyEnd, int budget) {
    ModelMessage system = messages.get(0);
    if (messages.size() == 1) {
      tracker.record(TokenEstimator.estimate(system) + overheadTokens, 0);
      // 不用 List.of：它是 Java 9 API，Android 上直到 API 30 才有。
      // 这类问题编译期与 JVM 单测都发现不了，只在真机上抛 NoSuchMethodError。
      List<ModelMessage> onlySystem = new ArrayList<>(1);
      onlySystem.add(system);
      notifyUsage();
      return onlySystem;
    }

    int requestIndex = Math.max(1, Math.min(historyEnd, messages.size() - 1));
    ModelMessage request = messages.get(requestIndex);
    List<ModelMessage> history = messages.subList(1, requestIndex);
    List<ModelMessage> turns = messages.subList(requestIndex + 1, messages.size());

    // 本次运行的轮次最新、与当前任务最相关，先给它们留位置；装不下时它们自己也要裁。
    // 预算传 max(1,...)：ContextTrimmer 把 <=0 解释为"不限"，传 0 会让它原样保留全部轮次。
    List<ModelMessage> keptTurns =
        dropLeadingToolResults(ContextTrimmer.trim(turns, Math.max(1, budget)).getMessages());
    int remaining = budget - TokenEstimator.estimate(keptTurns);
    // 同理：remaining <= 0 时不能交给 ContextTrimmer，否则历史会原样留下。
    List<ModelMessage> keptHistory =
        remaining > 0
            ? dropLeadingToolResults(ContextTrimmer.trim(history, remaining).getMessages())
            : new ArrayList<>();

    List<ModelMessage> result = new ArrayList<>(keptHistory.size() + keptTurns.size() + 2);
    result.add(system);
    result.addAll(keptHistory);
    result.add(request);
    result.addAll(keptTurns);
    tracker.record(TokenEstimator.estimate(result) + overheadTokens, 0);
    notifyUsage();
    return result;
  }

  /**
   * 去掉开头的孤立工具结果。
   *
   * <p>裁剪按工具调用组进行，理论上不会留下孤立结果；但历史来自磁盘，可能由旧版本或
   * 中断的会话写入。以工具结果开头发请求时多数服务商会报 400（找不到对应的 tool_call），
   * 因此这里兜底丢弃——它们本就无所指，丢掉不损失信息。
   */
  private static List<ModelMessage> dropLeadingToolResults(List<ModelMessage> messages) {
    int start = 0;
    while (start < messages.size() && messages.get(start) instanceof ToolModelMessage) {
      start++;
    }
    return start == 0 ? messages : new ArrayList<>(messages.subList(start, messages.size()));
  }

  /**
   * 历史部分的 token 预算。
   *
   * <p>用「窗口 − 开销 − 输出预留」，而非「触发点 − 开销 − 输出预留」：裁剪是循环内
   * 每次请求前的**硬约束**，必须保证请求本身能发出去；触发点只用于决定何时压缩。
   * 两者用同一个数会让裁剪在触发点处就停止工作，而请求仍可能因输出预留不足而超限。
   */
  private int historyBudget() {
    int contextSize = tracker.getContextSize();
    if (contextSize <= 0) {
      return UNLIMITED;
    }
    int budget = contextSize - overheadTokens - ConversationCompaction.RESERVE_OUTPUT_TOKENS;
    return Math.max(0, budget);
  }

  /** 记录服务端回报的真实用量，用于校准后续估算。 */
  public void recordResponse(int reportedInputTokens) {
    tracker.record(tracker.getLastEstimatedTokens(), reportedInputTokens);
    notifyUsage();
  }

  /** 把当前用量推给回调。窗口未配置（为 0）时不推——UI 无从计算比例。 */
  private void notifyUsage() {
    if (usageListener == null) {
      return;
    }
    int size = tracker.getContextSize();
    if (size <= 0) {
      return;
    }
    usageListener.onUsage(currentTokens(), size);
  }
}

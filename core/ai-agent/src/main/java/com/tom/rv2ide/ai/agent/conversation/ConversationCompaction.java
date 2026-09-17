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

package com.tom.rv2ide.ai.agent.conversation;

import com.tom.rv2ide.ai.agent.context.ContextTrimmer;
import com.tom.rv2ide.ai.agent.context.TokenEstimator;
import com.tom.rv2ide.ai.agent.context.TokenUsageTracker;
import com.tom.rv2ide.ai.protocol.ModelConfig;
import com.tom.rv2ide.ai.protocol.ModelContextParser;
import com.tom.rv2ide.ai.protocol.ModelMessage;
import com.tom.rv2ide.ai.protocol.UserModelMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 决定"压缩哪一段历史"，并产出对应的 {@link CompactionEntry}。
 *
 * <p><b>为什么压缩以「最旧的一段」为单位</b>：{@code ConversationHistory.fold} 的语义是
 * "序号 ≤ upToOrdinal 的条目被摘要取代"。这要求被压缩的条目必须是**前缀**——
 * 不能挖掉中间一段，否则 fold 无法表达（它只有一个上界）。
 * 这与裁剪的方向也一致：最近的历史与当前任务最相关。
 *
 * <p><b>边界不能落在工具调用与结果之间</b>：与 {@link ContextTrimmer} 同一约束。
 * 若助手消息请求了工具却被压掉、而工具结果仍留在历史里，多数服务商会直接报 400。
 * 因此边界若落在工具结果之前，必须向后延伸到该结果之后。
 *
 * <p><b>已有压缩的处理</b>：{@code fold} 把**所有**摘要按序拼在最前，因此新一轮压缩
 * 只应覆盖"上一次边界之后"的消息——把已覆盖的条目重新摘要会得到重复内容。
 * 若算出的边界没有超过上一次，则视为无需压缩。
 *
 * <p><b>为什么单独成类而不是塞进 {@code ContextCompactor}</b>：压缩器只关心
 * "把消息变成摘要"（纯字符串进、字符串出）；"选哪一段、上界是几"是会话结构的决策，
 * 依赖条目序号。分开后两者都能独立单测。
 */
public final class ConversationCompaction {

  /**
   * 为模型输出预留的 token。
   *
   * <p>不留这块，输入刚好用满窗口时请求会因"输入+输出超限"而失败——
   * 而输出长度不由我们控制。
   */
  public static final int RESERVE_OUTPUT_TOKENS = 8192;

  /** 至少要保留的模型可见消息数：压缩不能把历史清空成只剩一段摘要。 */
  public static final int MIN_KEEP_MESSAGES = ContextTrimmer.MIN_KEEP_MESSAGES;

  private ConversationCompaction() {}

  /**
   * 可用于历史消息的 token 预算。
   *
   * <p>从上下文窗口的**硬压缩阈值**（而非窗口本身）往下扣：阈值以上就该压缩，
   * 因此预算是"到触发点为止还剩多少"。再扣掉系统提示词、工具定义与输出预留——
   * 这些不占历史预算但确实占窗口。
   *
   * @param config 模型配置，用于解析上下文窗口
   * @param overheadTokens 系统提示词 + 工具定义的估算 token 数
   * @return 历史预算；{@code config} 为 null 时返回 {@link Integer#MAX_VALUE}（视为不限制）
   */
  public static int historyBudget(ModelConfig config, int overheadTokens) {
    int contextSize = contextSizeOf(config);
    if (contextSize <= 0) {
      return Integer.MAX_VALUE;
    }
    int triggerPoint = (int) (contextSize * TokenUsageTracker.COMPACT_TRIGGER_RATIO);
    int budget = triggerPoint - Math.max(0, overheadTokens) - RESERVE_OUTPUT_TOKENS;
    return Math.max(0, budget);
  }

  /**
   * 上下文窗口大小。
   *
   * <p>走 {@link ModelContextParser#parse(ModelConfig)}，因此未显式配置时回退到
   * 旧 {@code modelId[大小]} 后缀，最终兜底为解析器的默认窗口。只有 {@code config}
   * 为 null 才返回 0（无配置即无从判断）。
   */
  public static int contextSizeOf(ModelConfig config) {
    if (config == null) {
      return 0;
    }
    return ModelContextParser.parse(config).getContextTokens();
  }

  /**
   * 选出应被压缩的一段（最旧、且在上一次压缩边界之后）。
   *
   * @param entries 会话全部条目（按序，含 meta、标题与既有压缩条目）
   * @param keepBudgetTokens 保留最近历史所用的 token 预算
   * @return 选择结果；{@link Selection#isEmpty()} 为 true 表示无需压缩
   */
  public static Selection select(List<ConversationLog.EntryLocation> entries, int keepBudgetTokens) {
    if (entries == null || entries.isEmpty()) {
      return Selection.none();
    }

    int alreadyCompactedUpTo = maxCompactedOrdinal(entries);
    // 既有摘要本身也在历史里占位，必须先扣掉，否则预算算多了会推迟压缩
    int budget = keepBudgetTokens - summaryTokens(entries, alreadyCompactedUpTo);
    if (budget <= 0) {
      budget = 0;
    }

    // 从最新往回累计"仍在历史里"的消息成本，超出预算处即为边界。
    int keptTokens = 0;
    int keptMessages = 0;
    int boundaryIndex = -1;
    for (int i = entries.size() - 1; i >= 0; i--) {
      ConversationLog.EntryLocation location = entries.get(i);
      if (location.getOrdinal() <= alreadyCompactedUpTo) {
        // 已被上一次压缩覆盖，不在历史里，也就不占预算
        continue;
      }
      ModelMessage message = ConversationHistory.toMessage(location.getEntry());
      if (message == null) {
        continue; // meta / 标题 / 压缩条目不占模型预算
      }
      int tokens = TokenEstimator.estimate(message);
      if (keptMessages >= MIN_KEEP_MESSAGES && keptTokens + tokens > budget) {
        boundaryIndex = i;
        break;
      }
      keptTokens += tokens;
      keptMessages++;
    }

    if (boundaryIndex < 0) {
      return Selection.none(); // 全部历史都在预算内
    }

    // 边界不能落在工具调用与结果之间：把紧随其后的工具结果一并划入被压缩区间。
    while (boundaryIndex + 1 < entries.size()
        && entries.get(boundaryIndex + 1).getEntry() instanceof ToolResultEntry) {
      boundaryIndex++;
    }

    return build(entries, alreadyCompactedUpTo, boundaryIndex);
  }

  /** 既有压缩覆盖的最大序号；无压缩条目时返回 -1。 */
  private static int maxCompactedOrdinal(List<ConversationLog.EntryLocation> entries) {
    int max = -1;
    for (ConversationLog.EntryLocation location : entries) {
      if (location.getEntry() instanceof CompactionEntry) {
        max = Math.max(max, ((CompactionEntry) location.getEntry()).getUpToOrdinal());
      }
    }
    return max;
  }

  /** 既有摘要折算的 token 数——它们作为一条用户消息出现在历史开头。 */
  private static int summaryTokens(List<ConversationLog.EntryLocation> entries, int upToOrdinal) {
    if (upToOrdinal < 0) {
      return 0;
    }
    List<String> summaries = new ArrayList<>();
    for (ConversationLog.EntryLocation location : entries) {
      if (location.getEntry() instanceof CompactionEntry) {
        CompactionEntry compaction = (CompactionEntry) location.getEntry();
        if (!compaction.getSummary().isEmpty()) {
          summaries.add(compaction.getSummary());
        }
      }
    }
    if (summaries.isEmpty()) {
      return 0;
    }
    return TokenEstimator.estimate(new UserModelMessage(String.join("\n\n", summaries)));
  }

  private static Selection build(
      List<ConversationLog.EntryLocation> entries, int alreadyCompactedUpTo, int boundaryIndex) {
    // 只收集"上一次边界之后、本次边界之内"的消息：更早的内容已在既有摘要里，
    // 重新摘要会让 fold 把同一段信息拼两遍。
    List<ModelMessage> messages = new ArrayList<>();
    for (int i = 0; i <= boundaryIndex; i++) {
      ConversationLog.EntryLocation location = entries.get(i);
      if (location.getOrdinal() <= alreadyCompactedUpTo) {
        continue;
      }
      ModelMessage message = ConversationHistory.toMessage(location.getEntry());
      if (message != null) {
        messages.add(message);
      }
    }
    return new Selection(entries.get(boundaryIndex).getOrdinal(), messages);
  }

  /** 一次压缩选择：被覆盖到的最大序号，以及该区间的模型可见消息。 */
  public static final class Selection {
    private static final Selection NONE = new Selection(-1, Collections.emptyList());

    private final int upToOrdinal;
    private final List<ModelMessage> messages;

    private Selection(int upToOrdinal, List<ModelMessage> messages) {
      this.upToOrdinal = upToOrdinal;
      this.messages = messages;
    }

    static Selection none() {
      return NONE;
    }

    public boolean isEmpty() {
      return upToOrdinal < 0 || messages.isEmpty();
    }

    /** 被压缩覆盖的最大条目序号；{@link #isEmpty()} 为 true 时无意义。 */
    public int getUpToOrdinal() {
      return upToOrdinal;
    }

    /** 被压缩区间的模型可见消息，供摘要调用使用。 */
    public List<ModelMessage> getMessages() {
      return messages;
    }
  }
}

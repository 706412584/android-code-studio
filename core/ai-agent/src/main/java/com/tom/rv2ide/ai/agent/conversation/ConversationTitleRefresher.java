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

package com.tom.rv2ide.ai.agent.conversation;

import com.tom.rv2ide.ai.protocol.ModelConfig;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 会话标题刷新协调器：累积轮次 → 按策略判定 → 生成 → 落盘。
 *
 * <p>每个会话一个实例，随会话生命周期存活。宿主在每轮助手完成时调
 * {@link #onTurnFinished(ModelConfig)}，本类负责：
 * <ol>
 *   <li>把这一轮 user+assistant 累积进 {@link #turns}；</li>
 *   <li>用 {@link TitleRefreshPolicy} 判定该不该生成；</li>
 *   <li>生成并 append 一条 {@link TitleEntry#aiTitle}；</li>
 *   <li>通知 UI 刷新（{@link Listener#onTitleUpdated}）。</li>
 * </ol>
 *
 * <p><b>为什么写成同步而非异步</b>：调用方（{@code ConversationPersistingListener}）
 * 本身在事件线程里跑，且标题是旁路——生成失败就跳过本轮，绝不影响对话。
 * 慢网络下最多拖慢这一轮的收尾几秒（{@code READ_TIMEOUT_MS} 上限），
 * 换来的是无需处理跨线程生命周期与取消。
 *
 * <p><b>防覆盖</b>：实例内记录用户是否重命名过；一旦为 true，此后所有触发点
 * 一律短路。与 {@link ConversationReducer} 的优先级链共同保证「用户意图优先」。
 */
public final class ConversationTitleRefresher {

  /** 标题更新通知。UI 层用它刷新列表项，而不必轮询会话文件。 */
  public interface Listener {
    void onTitleUpdated(String conversationId, String title);
  }

  private final String conversationId;
  private final ConversationStore store;
  private final Listener listener;

  /** 生成标题所用的模型配置（复用当前会话的 baseUrl / apiKey / modelId）。 */
  private ModelConfig modelConfig;

  /** 已完成的轮次（user+assistant），供生成时累积上下文。 */
  private final List<TitleGenerator.Turn> turns = new ArrayList<>();

  /** 已触发过的生成点，防止同一轮重复请求。 */
  private final Set<String> firedKeys = new HashSet<>();

  /** 最近一次用户消息（用于换话题判定）。 */
  private String latestUserMessage = "";

  /** 当前生效的标题（占位或 AI 生成），用于换话题比对。 */
  private String currentTitle = "";

  /** 上次生成标题时的轮数；0 表示还没生成过。 */
  private int lastGeneratedTurn = 0;

  /** 用户是否显式重命名过——为 true 时永久停止自动生成。 */
  private boolean hasCustomTitle = false;

  public ConversationTitleRefresher(
      String conversationId, ConversationStore store, Listener listener) {
    this.conversationId = conversationId == null ? "" : conversationId;
    this.store = store;
    this.listener = listener;
  }

  /** 设置生成标题所用的模型配置。宿主在每次运行前刷新（服务商可能被用户换掉）。 */
  public void setModelConfig(ModelConfig config) {
    this.modelConfig = config;
  }

  /** 标记用户重命名——此后不再自动生成。 */
  public void markCustomTitle(String title) {
    hasCustomTitle = true;
    currentTitle = title == null ? "" : title;
  }

  /** 本轮的用户消息（在助手完成前由宿主登记）。 */
  public void onUserMessage(String text) {
    latestUserMessage = text == null ? "" : text;
    // 首轮先给占位标题，让用户立刻在列表里看到有意义的名字，不必等模型。
    if (turns.isEmpty() && currentTitle.isEmpty()) {
      String placeholder = TitleGenerator.derivePlaceholder(text);
      if (!placeholder.isEmpty()) {
        currentTitle = placeholder;
      }
    }
  }

  /**
   * 一轮助手完成。
   *
   * @param assistantText 本轮助手正文（可能为空，如纯工具调用轮）
   */
  public void onTurnFinished(String assistantText) {
    ModelConfig config = modelConfig;
    if (config == null) {
      // 没配模型就无法生成——只累积轮次，不触发。等宿主设置后下一轮再试。
      turns.add(new TitleGenerator.Turn(latestUserMessage, assistantText));
      return;
    }
    if (conversationId.isEmpty() || store == null) {
      return;
    }
    turns.add(new TitleGenerator.Turn(latestUserMessage, assistantText));
    int completedTurns = turns.size();

    TitleRefreshPolicy.State state =
        new TitleRefreshPolicy.State(
            completedTurns, lastGeneratedTurn, hasCustomTitle, currentTitle, latestUserMessage,
            firedKeys);
    TitleRefreshPolicy.Decision decision = TitleRefreshPolicy.evaluate(state);
    if (!decision.shouldGenerate) {
      return;
    }
    firedKeys.add(decision.key);

    String conversationText = TitleGenerator.buildConversationInput(turns);
    String title = TitleGenerator.generate(conversationText, config);
    if (title == null || title.trim().isEmpty()) {
      // 生成失败：保留现有标题（占位或上一版 AI 标题），下一轮再试。
      return;
    }
    try {
      store.append(conversationId, TitleEntry.aiTitle(null, System.currentTimeMillis(), title));
      currentTitle = title;
      lastGeneratedTurn = completedTurns;
      if (listener != null) {
        listener.onTitleUpdated(conversationId, title);
      }
    } catch (IOException | RuntimeException e) {
      // 写盘失败不影响对话；下一轮按策略还会再试。
    }
  }

  /** 测试用：当前轮数。 */
  int turnCount() {
    return turns.size();
  }

  /** 测试用：已触发的 key 集合。 */
  Set<String> firedKeys() {
    return firedKeys;
  }
}

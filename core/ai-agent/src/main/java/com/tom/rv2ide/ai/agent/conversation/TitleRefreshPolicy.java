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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 决定「这一轮该不该刷新会话标题」的纯策略。
 *
 * <p><b>与 cc-haha 的差异</b>：cc-haha 只在第 1 轮与第 3 轮各生成一次，之后
 * {@code count !== 1 && count !== 3} 直接 return——标题就此锁死（实测反馈：长会话
 * 换了话题标题仍是第一句）。本类在此基础上加三类关键触发点：
 *
 * <ul>
 *   <li><b>turn 1</b>：首轮必生成（占位 → AI 标题）。</li>
 *   <li><b>turn 3</b>：上下文更全时再生一次。</li>
 *   <li><b>换话题</b>：距上次生成 ≥ 2 轮，且新用户消息与既有标题语义无关。</li>
 *   <li><b>周期再命名</b>：此后每 {@link #PERIODIC_INTERVAL} 轮再生成一次，
 *       覆盖长会话的主题漂移。</li>
 * </ul>
 *
 * <p><b>用户重命名永远压过自动生成</b>：{@code hasCustomTitle=true} 时一律不触发。
 * 这条与 {@link ConversationReducer} 的优先级链（customTitle > aiTitle > 占位）
 * 同向，防止 AI 把用户写好的标题冲掉。
 *
 * <p><b>纯判定，无副作用</b>：网络调用、写盘都不在这里——调用方按返回值决定动作，
 * 使本类可在 JVM 上穷举测试。
 */
public final class TitleRefreshPolicy {

  /** 长会话的周期再命名间隔（轮）。 */
  static final int PERIODIC_INTERVAL = 8;

  /** 「换话题」判定要求距上次生成至少经过的轮数，避免相邻两轮连续触发。 */
  static final int TOPIC_SHIFT_MIN_GAP = 2;

  private TitleRefreshPolicy() {}

  /** 当前会话的标题状态快照。 */
  public static final class State {
    /** 已完成的助手轮数（含本轮）。 */
    public final int completedTurns;
    /** 上次生成标题时的轮数；从未生成时为 0。 */
    public final int lastGeneratedTurn;
    /** 用户是否显式重命名过。 */
    public final boolean hasCustomTitle;
    /** 当前生效的标题（用于换话题比对）。 */
    public final String currentTitle;
    /** 本条用户消息原文（用于换话题比对）。 */
    public final String latestUserMessage;
    /** 该触发点是否已生成过（防止同一轮重复请求）。 */
    public final Set<String> firedKeys;

    public State(
        int completedTurns,
        int lastGeneratedTurn,
        boolean hasCustomTitle,
        String currentTitle,
        String latestUserMessage,
        Set<String> firedKeys) {
      this.completedTurns = completedTurns;
      this.lastGeneratedTurn = lastGeneratedTurn;
      this.hasCustomTitle = hasCustomTitle;
      this.currentTitle = currentTitle == null ? "" : currentTitle;
      this.latestUserMessage = latestUserMessage == null ? "" : latestUserMessage;
      this.firedKeys = firedKeys == null ? new HashSet<>() : firedKeys;
    }
  }

  /** 触发结果：是否应生成 + 用于去重的 key。 */
  public static final class Decision {
    public final boolean shouldGenerate;
    /** 触发点标识。用于记录「这个点已经生成过」，避免重复请求。 */
    public final String key;

    Decision(boolean shouldGenerate, String key) {
      this.shouldGenerate = shouldGenerate;
      this.key = key;
    }

    static final Decision NO = new Decision(false, "");
  }

  /**
   * 判定本轮是否应生成标题。
   *
   * @param state 当前状态（见 {@link State}）
   * @return 决策；{@link Decision#shouldGenerate} 为 true 时调用方应发起生成
   */
  public static Decision evaluate(State state) {
    if (state.hasCustomTitle) {
      return Decision.NO;
    }
    // 首轮必生成：占位标题此刻已可显示，AI 标题紧随其后。
    if (state.completedTurns == 1) {
      return fresh("turn:1", state);
    }
    // 第 3 轮：上下文足够，标题更准。
    if (state.completedTurns == 3) {
      return fresh("turn:3", state);
    }
    // 周期再命名：此后每 N 轮一次（覆盖长会话的主题漂移）。
    if (state.completedTurns > 3 && state.completedTurns % PERIODIC_INTERVAL == 0) {
      return fresh("periodic:" + state.completedTurns, state);
    }
    // 换话题：距上次生成有间隔，且用户新消息与既有标题语义无关。
    if (state.completedTurns > 1
        && state.completedTurns - state.lastGeneratedTurn >= TOPIC_SHIFT_MIN_GAP
        && isTopicShift(state.currentTitle, state.latestUserMessage)) {
      return fresh("topic:" + state.completedTurns, state);
    }
    return Decision.NO;
  }

  private static Decision fresh(String key, State state) {
    if (state.firedKeys.contains(key)) {
      return Decision.NO;
    }
    return new Decision(true, key);
  }

  /**
   * 判定新用户消息是否与既有标题「话题不同」。
   *
   * <p>实现刻意保守：只有**明显无关**才判 true，避免把「补充上一句」误判为换话题。
   * 判据是两边的关键词集合**完全无交集**——共享任一实词即视为同话题，不触发。
   *
   * <p>为什么不用更复杂的语义模型：换话题是低频但代价高（要多一次模型请求）的判定，
   * 宁可漏判也不要误判。词面重叠在「继续聊同一件事」时几乎必然非空。
   */
  static boolean isTopicShift(String currentTitle, String latestUserMessage) {
    // 短文本不判：标题「标题」、消息「继续」这种没有足够语义的输入，
    // 词面无交集是必然的，判换话题纯属误判（实测：非周期轮因此被反复触发）。
    if (currentTitle == null || currentTitle.trim().length() < MIN_TOPIC_TEXT_LEN
        || latestUserMessage == null || latestUserMessage.trim().length() < MIN_TOPIC_TEXT_LEN) {
      return false;
    }
    Set<String> titleWords = keywords(currentTitle);
    Set<String> messageWords = keywords(latestUserMessage);
    if (titleWords.isEmpty() || messageWords.isEmpty()) {
      return false;
    }
    for (String word : messageWords) {
      if (titleWords.contains(word)) {
        return false;
      }
    }
    return true;
  }

  /** 换话题判定所需的最小文本长度（字符）。低于此值视为「不足以判断话题」。 */
  private static final int MIN_TOPIC_TEXT_LEN = 6;

  /**
   * 提取关键词：中文按 2-4 字滑窗，英文按整词。
   *
   * <p>中文没有空格分词，滑窗是零依赖下最稳的近似——「编译失败」会切出
   * 编译/译失/失败 等片段，只要任一片段重叠就视为同话题。
   */
  private static Set<String> keywords(String text) {
    Set<String> out = new HashSet<>();
    if (text == null) {
      return out;
    }
    String clean = text.trim().toLowerCase(java.util.Locale.ROOT);
    if (clean.isEmpty()) {
      return out;
    }
    // 英文单词（≥3 字母，过滤 the/and 这类虚词的意义不大，词面重叠即可）。
    java.util.regex.Matcher latin =
        java.util.regex.Pattern.compile("[a-z]{3,}").matcher(clean);
    while (latin.find()) {
      out.add(latin.group());
    }
    // 中文 2~4 字滑窗。
    StringBuilder han = new StringBuilder();
    for (int i = 0; i < clean.length(); i++) {
      char c = clean.charAt(i);
      if (c >= 0x4e00 && c <= 0x9fff) {
        han.append(c);
      } else {
        flushHan(han, out);
      }
    }
    flushHan(han, out);
    return out;
  }

  private static void flushHan(StringBuilder han, Set<String> out) {
    String s = han.toString();
    han.setLength(0);
    for (int len = 2; len <= 4; len++) {
      for (int i = 0; i + len <= s.length(); i++) {
        out.add(s.substring(i, i + len));
      }
    }
  }
}

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

package com.tom.rv2ide.ai.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 判定一次 agent 运行的最终文本是否为「模型拒绝执行」。
 *
 * <p><b>为什么在编排层判定</b>:拒绝藏在**正常完成**的响应里——HTTP 200、
 * 无工具调用、事件流全是 COMPLETED。协议层的 {@code RetryPolicy} 只看传输错误,
 * 覆盖不到这种失败。判定必须拿到最终文本,那是编排层(ConversationRunner /
 * AgentOrchestrator)手里的东西。
 *
 * <p><b>误报控制</b>:短语分两级——
 * <ul>
 *   <li><b>强短语</b>(自我指称 + 拒绝意图的组合,如 "作为AI语言模型"、
 *       "I cannot assist"):单独命中即判定。</li>
 *   <li><b>弱短语</b>(客套/免责措辞,如 "很抱歉"、"I'm sorry"):
 *       命中 >= 2 条才判定;仅 1 条时要求文本短(&lt; 300 字符)——
 *       长文里出现单个 "很抱歉" 多半是行文客套,不是整篇拒绝。</li>
 * </ul>
 * 不匹配裸的 "无法/不能":正常技术回答里 "无法在此环境执行" 很常见,
 * 误报让用户白等一轮重试。代价是漏报(换了措辞的拒绝)——漏报只是
 * 回到原行为,误报才是真实代价。
 *
 * <p>纯 Java、无 Android 依赖,可用 JUnit 直接测。默认实现是短语表;
 * 宿主可注入 {@link Classifier}(例如 1-2B 小模型二分类)替换。
 */
public final class RefusalDetector {

  /** 判定结果。 */
  public static final class Verdict {
    public final boolean refused;
    /** 命中依据(面向用户/日志的一句话);未拒绝时为空。 */
    public final String reason;

    Verdict(boolean refused, String reason) {
      this.refused = refused;
      this.reason = reason == null ? "" : reason;
    }

    public static Verdict ok() {
      return new Verdict(false, "");
    }

    public static Verdict refused(String reason) {
      return new Verdict(true, reason);
    }

    public boolean isRefused() {
      return refused;
    }

    public String getReason() {
      return reason;
    }
  }

  /** 自定义判定实现(如小模型二分类)。入参为去除首尾空白后的文本。 */
  public interface Classifier {
    Verdict classify(String text);
  }

  /**
   * 强短语:单条命中即判定。全部是「模型自指 / 明确拒绝意图」的组合,
   * 正常技术回答里不会成串出现。
   */
  private static final String[] STRONG_PHRASES = {
      // 自我指称
      "作为ai", "作为 ai", "ai语言模型", "人工智能助手", "as an ai", "i'm an ai", "i am an ai",
      // 明确拒绝意图(中文)
      "我无法", "我无法满足", "我不能协助", "无法协助", "无法提供", "不提供", "不予",
      "拒绝回答", "拒绝执行", "不能帮你", "无法满足你", "我不能这么做", "我无法完成",
      "我无法为你", "我无法帮", "我不能帮你",
      // 明确拒绝意图(英文)
      "i cannot assist", "i can't assist", "i cannot help", "i can't help",
      "i'm not able", "i am unable", "i must refuse", "i have to decline",
      "not something i can", "out of my scope",
      // 合规话术(出现即表明模型在走拒绝流程)
      "出于安全", "出于伦理", "需要授权", "合法途径", "政策违规", "policy violation",
      "safety guidelines", "against my guidelines", "against my policy",
  };

  /**
   * 弱短语:需 >= 2 条命中,或 1 条命中且全文 < 300 字符。
   */
  private static final String[] WEAK_PHRASES = {
      "很抱歉", "抱歉", "对不起", "温馨提示", "涉及敏感", "违反规定", "不合规",
      "超出我的能力", "超出我", "不在我的能力", "超出了我的能力", "道德边界",
      "安全限制", "i'm sorry", "i apologize", "i will not", "i won't",
      "please understand", "i'd recommend", "safety considerations",
  };

  /** 单命中判定的文本长度上限(字符)。 */
  private static final int SHORT_TEXT_LIMIT = 300;

  private final boolean enabled;
  private final Classifier classifier;

  private RefusalDetector(boolean enabled, Classifier classifier) {
    this.enabled = enabled;
    this.classifier = classifier;
  }

  public RefusalDetector() {
    this(true, new PhraseClassifier());
  }

  /** 关闭判定:行为退化为原样直出(等价于功能未接入)。 */
  public static RefusalDetector disabled() {
    return new RefusalDetector(false, new PhraseClassifier());
  }

  /** 注入自定义分类器(如小模型二分类)。 */
  public RefusalDetector(Classifier classifier) {
    this(true, classifier == null ? new PhraseClassifier() : classifier);
  }

  /** 默认实现:强/弱短语表 + 长度兜底(纯函数,可直接单测)。 */
  static final class PhraseClassifier implements Classifier {
    private final List<Pattern> strong;
    private final List<Pattern> weak;

    PhraseClassifier() {
      strong = compile(STRONG_PHRASES);
      weak = compile(WEAK_PHRASES);
    }

    private static List<Pattern> compile(String[] phrases) {
      List<Pattern> out = new ArrayList<Pattern>(phrases.length);
      for (String phrase : phrases) {
        out.add(Pattern.compile(Pattern.quote(phrase), Pattern.CASE_INSENSITIVE));
      }
      return out;
    }

    @Override
    public Verdict classify(String text) {
      String normalized = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
      if (normalized.isEmpty()) {
        return Verdict.ok();
      }
      int strongHits = 0;
      for (Pattern pattern : strong) {
        if (pattern.matcher(normalized).find()) {
          strongHits++;
          break; // 强短语一条即定论,不必继续
        }
      }
      if (strongHits > 0) {
        return Verdict.refused("命中强拒绝短语");
      }
      // 命中计数:一条弱短语若是另一条命中短语的真子串(如「很抱歉」会同时命中
      // 「很抱歉」与「抱歉」两条表项),视为同一礼貌信号、不重复计——否则一段只出现
      // 一次致歉的长文本会被虚高的计数误判成拒绝(见 singleWeakPhraseInLongTextIsAccepted)。
      java.util.List<String> weakMatched = new java.util.ArrayList<String>();
      for (int i = 0; i < weak.size(); i++) {
        if (weak.get(i).matcher(normalized).find()) {
          weakMatched.add(WEAK_PHRASES[i].toLowerCase(Locale.ROOT));
        }
      }
      int weakHits = 0;
      for (String p : weakMatched) {
        boolean subsumed = false;
        for (String q : weakMatched) {
          if (!q.equals(p) && q.length() > p.length() && q.contains(p)) {
            subsumed = true;
            break;
          }
        }
        if (!subsumed) {
          weakHits++;
        }
      }
      if (weakHits >= 2) {
        return Verdict.refused("命中 " + weakHits + " 条拒绝短语");
      }
      if (weakHits == 1 && normalized.length() < SHORT_TEXT_LIMIT) {
        return Verdict.refused("短文本命中拒绝短语");
      }
      return Verdict.ok();
    }
  }

  /**
   * 判定一次运行结果。
   *
   * <p>{@code result.isFailed()}(网络/超限等传输失败)与空输出都不算拒绝——
   * 那些走各自的既有路径(RetryPolicy / 失败提示),重复判定只会干扰归因。
   */
  public Verdict detect(AgentRunResult result) {
    if (!enabled || result == null || result.isFailed()) {
      return Verdict.ok();
    }
    String text = result.getOutput();
    if (text == null || text.trim().isEmpty()) {
      return Verdict.ok();
    }
    Verdict verdict = classifier.classify(text.trim());
    return verdict == null ? Verdict.ok() : verdict;
  }

  public boolean isEnabled() {
    return enabled;
  }
}

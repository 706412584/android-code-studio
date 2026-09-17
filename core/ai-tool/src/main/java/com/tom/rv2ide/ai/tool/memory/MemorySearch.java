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

package com.tom.rv2ide.ai.tool.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 记忆检索：按与查询词的相关度排序。
 *
 * <p><b>中文分词是这个功能的核心难点</b>。西方语言的「按空格切词」对中文完全失效——
 * 一整句中文会变成一个 token，只有当用户输入与记忆**逐字完全相同**时才匹配得上，
 * 检索等于不存在。这里不用第三方分词器（会引入依赖与体积），而是对 CJK 采用
 * **字符二元组（bigram）**：把「数据库连接」切成「数据/据库/库连/连接」。
 *
 * <p>选 bigram 而非单字的理由：单字会让「数据」与「据理力争」里的「据」互相匹配，
 * 噪声极大；bigram 保留了相邻关系，对中文的检索质量足够好，且实现只需一次线性扫描。
 * 对西方语言仍按词切分并做词干级的小写归一。
 *
 * <p><b>打分</b>采用简化的 TF-IDF 思路：命中词越多分越高，但每个词按它在记忆集合中的
 * 稀有度加权——「的」「是」这类高频词几乎不影响排序，专有名词则权重很高。
 * 不引入真实的 IDF 公式是因为记忆条数很少（通常几十条），过度拟合权重反而更难解释。
 *
 * <p>本类不引用 Android 类型，可单测。
 */
public final class MemorySearch {

  private MemorySearch() {}

  /**
   * 检索记忆。
   *
   * @param entries 全部记忆
   * @param query 查询词（通常是用户请求）
   * @param limit 最多返回条数；{@code <= 0} 表示不限
   * @return 按相关度降序；无匹配时返回空列表
   */
  public static List<MemoryEntry> search(List<MemoryEntry> entries, String query, int limit) {
    if (entries == null || entries.isEmpty()) {
      return Collections.emptyList();
    }
    Set<String> queryTokens = tokenize(query);
    if (queryTokens.isEmpty()) {
      return Collections.emptyList();
    }

    // 先统计每个 token 在全部记忆里出现多少次，用于稀有度加权。
    java.util.Map<String, Integer> documentFrequency = new java.util.HashMap<>();
    List<Set<String>> entryTokens = new ArrayList<>(entries.size());
    for (MemoryEntry entry : entries) {
      Set<String> tokens = tokenize(entry.getText() + " " + String.join(" ", entry.getTags()));
      entryTokens.add(tokens);
      for (String token : tokens) {
        Integer count = documentFrequency.get(token);
        documentFrequency.put(token, count == null ? 1 : count + 1);
      }
    }

    List<Scored> scored = new ArrayList<>();
    for (int i = 0; i < entries.size(); i++) {
      MemoryEntry entry = entries.get(i);
      if (entry.isBlank()) {
        continue;
      }
      double score = 0;
      for (String token : queryTokens) {
        if (!entryTokens.get(i).contains(token)) {
          continue;
        }
        // 越稀有越重要：出现在所有记忆里的 token 权重接近 1，只出现在一条里的权重更高。
        int df = documentFrequency.containsKey(token) ? documentFrequency.get(token) : 1;
        score += Math.log(1.0 + (double) entries.size() / df);
      }
      if (score > 0) {
        scored.add(new Scored(entry, score));
      }
    }

    // 分数相同时按时间倒序：近期记忆通常更相关。
    scored.sort(
        Comparator.comparingDouble((Scored s) -> s.score)
            .reversed()
            .thenComparing(Comparator.comparingLong((Scored s) -> s.entry.getCreatedAt()).reversed()));

    List<MemoryEntry> result = new ArrayList<>(scored.size());
    for (Scored item : scored) {
      if (limit > 0 && result.size() >= limit) {
        break;
      }
      result.add(item.entry);
    }
    return result;
  }

  /**
   * 分词：CJK 走字符二元组，其余按词切分。
   *
   * <p>结果统一小写，使英文检索大小写不敏感（{@code Retrofit} 与 {@code retrofit} 等价）。
   */
  public static Set<String> tokenize(String text) {
    Set<String> tokens = new HashSet<>();
    if (text == null || text.isEmpty()) {
      return tokens;
    }

    StringBuilder word = new StringBuilder();
    char previousCjk = 0;

    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (isCjk(c)) {
        // 进入 CJK 段前先把累积的西文词收掉，避免中英混排时粘连。
        flushWord(word, tokens);
        if (previousCjk != 0) {
          tokens.add(new String(new char[] {previousCjk, c}));
        }
        previousCjk = c;
        continue;
      }
      previousCjk = 0;
      if (Character.isLetterOrDigit(c)) {
        word.append(Character.toLowerCase(c));
      } else {
        flushWord(word, tokens);
      }
    }
    flushWord(word, tokens);

    return tokens;
  }

  /**
   * 是否为需要 bigram 处理的表意文字。
   *
   * <p>覆盖中文（含扩展区）、日文假名与韩文。这三类都没有词间空格，
   * 按空格切词同样失效。
   */
  private static boolean isCjk(char c) {
    return (c >= 0x4E00 && c <= 0x9FFF) // CJK 统一表意文字
        || (c >= 0x3400 && c <= 0x4DBF) // 扩展 A
        || (c >= 0xF900 && c <= 0xFAFF) // 兼容表意文字
        || (c >= 0x3040 && c <= 0x30FF) // 平假名 / 片假名
        || (c >= 0xAC00 && c <= 0xD7AF); // 韩文音节
  }

  /**
   * 收下一个西文词。
   *
   * <p>丢弃单字符词：英文里的单字母（{@code a}、{@code i}）几乎总是噪声，
   * 而中文单字已由 bigram 覆盖，不经过这条路径。
   */
  private static void flushWord(StringBuilder word, Set<String> tokens) {
    if (word.length() >= 2) {
      tokens.add(word.toString());
    }
    word.setLength(0);
  }

  /** 供诊断：把分词结果拼成可读字符串。 */
  public static String describeTokens(String text) {
    List<String> sorted = new ArrayList<>(tokenize(text));
    Collections.sort(sorted);
    return String.join(" ", sorted);
  }

  private static final class Scored {
    final MemoryEntry entry;
    final double score;

    Scored(MemoryEntry entry, double score) {
      this.entry = entry;
      this.score = score;
    }
  }

  /** 归一化查询词用于日志。 */
  static String normalize(String text) {
    return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
  }
}

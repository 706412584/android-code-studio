/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
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

package com.tom.rv2ide.artificial.agent;

import java.util.Locale;

/**
 * 模型名里的上下文窗口后缀，如 {@code deepseek-v4-pro[1m]}、{@code glm-5.2[200k]}。
 *
 * <p><b>为什么把大小写进模型名</b>：同一个模型在不同服务商、不同订阅档位下的上下文窗口
 * 可能不同（1M vs 128K），而模型名本身区分不出来。把后缀放在模型名里，用户在一处
 * 填完，槽位、请求、显示三处都能读到同一个值，不必再开一个「上下文大小」输入框
 * 去跟模型名手动对齐。
 *
 * <p><b>为什么保留原始模型名</b>：后缀是本地元数据，不能发给服务端——
 * {@code deepseek-v4-pro[1m]} 不是合法模型 ID，发过去必然 404。因此
 * {@link #stripSuffix} 负责取出真正要发给 API 的名字。
 *
 * <p>纯 Java 无 Android 依赖，可直接 JVM 单测。
 */
public final class ContextSizeParser {

  /** 解析失败或未设置时的哨兵值，表示「用默认值」。 */
  public static final int UNSET = 0;

  private ContextSizeParser() {}

  /**
   * 取出模型名里声明的上下文窗口大小。
   *
   * @return token 数；无后缀或格式非法时返回 {@link #UNSET}
   */
  public static int parseFromModelId(String modelId) {
    if (modelId == null) {
      return UNSET;
    }
    int open = modelId.lastIndexOf('[');
    if (open < 0 || !modelId.endsWith("]")) {
      return UNSET;
    }
    return parse(modelId.substring(open + 1, modelId.length() - 1));
  }

  /**
   * 去掉模型名里的上下文后缀，得到发给 API 的真实模型 ID。
   *
   * <p>只在后缀能解析成功时才剥离：如果用户把 {@code foo[bar]} 当作真实模型名
   * （某些网关确实这么命名），剥掉反而会发错名字。
   */
  public static String stripSuffix(String modelId) {
    if (modelId == null) {
      return "";
    }
    if (parseFromModelId(modelId) == UNSET) {
      return modelId;
    }
    return modelId.substring(0, modelId.lastIndexOf('['));
  }

  /**
   * 解析大小字面量。
   *
   * <p>支持 {@code 1000} / {@code 200k} / {@code 1m} 三种写法（大小写不敏感）。
   * 与参考项目（LineCode Pro 的 ContextSizeParser）行为一致。
   */
  public static int parse(String input) {
    if (input == null) {
      return UNSET;
    }
    String trimmed = input.trim();
    if (trimmed.isEmpty()) {
      return UNSET;
    }
    String lower = trimmed.toLowerCase(Locale.US);
    try {
      double value;
      if (lower.endsWith("k")) {
        value = Double.parseDouble(lower.substring(0, lower.length() - 1)) * 1000d;
      } else if (lower.endsWith("m")) {
        value = Double.parseDouble(lower.substring(0, lower.length() - 1)) * 1000000d;
      } else {
        value = Double.parseDouble(lower);
      }
      if (value <= 0d) {
        return UNSET;
      }
      long rounded = Math.round(value);
      if (rounded <= 0L || rounded > Integer.MAX_VALUE) {
        return UNSET;
      }
      return (int) rounded;
    } catch (NumberFormatException e) {
      return UNSET;
    }
  }

  /** 把 token 数格式化成后缀里用的短写法（{@code 1000000} → {@code 1M}）。 */
  public static String format(int size) {
    if (size <= 0) {
      return "";
    }
    if (size >= 1000000 && size % 1000000 == 0) {
      return (size / 1000000) + "M";
    }
    if (size >= 1000 && size % 1000 == 0) {
      return (size / 1000) + "K";
    }
    return String.valueOf(size);
  }
}

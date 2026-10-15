/*
 * This file is part of AndroidCodeStudio.
 *
 * Ported from Aharou (https://github.com/520huxiangli/Aharou),
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

package com.tom.rv2ide.artificial.agent.tool.vd;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「操作屏幕」工具的语义风控与截图 caption（移植自 Aharou 的 {@code ScreenActionSemantics}）。
 *
 * <p>语义风控参考 nanoMuse：点按/输入本身看不出会不会提交，于是让调用方带上手指下/
 * 目标元素的文字（label），命中敏感词即按敏感档处理——这一步要用户逐次确认、不可记忆。
 * caption 把「哪个 App、哪一屏、多大、键盘是否在」拼成一行，随截图一起给模型。
 */
public final class ScreenActionSemantics {

  private ScreenActionSemantics() {}

  /**
   * 命中即视为「付款/转账/发送/删除」这类按下后无法撤销的动作。中文为主，附英文变体，
   * 与 nanoMuse 默认词表对齐；要扩词改这一处即可。
   */
  public static final List<String> SENSITIVE_WORDS;

  static {
    List<String> words = new ArrayList<>();
    words.add("确认支付");
    words.add("立即支付");
    words.add("转账");
    words.add("提交订单");
    words.add("立即购买");
    words.add("付款");
    words.add("发送");
    words.add("删除");
    words.add("退出登录");
    words.add("确认收货");
    words.add("免密支付");
    words.add("pay now");
    words.add("place order");
    words.add("buy now");
    words.add("transfer");
    words.add("send");
    words.add("delete");
    words.add("log out");
    words.add("sign out");
    SENSITIVE_WORDS = java.util.Collections.unmodifiableList(words);
  }

  /** label 命中的敏感词（去重，保持词表顺序）；空列表表示不敏感。 */
  public static List<String> matchSensitive(String label) {
    List<String> hits = new ArrayList<>();
    if (label == null) {
      return hits;
    }
    String hay = label.toLowerCase(Locale.ROOT);
    for (String word : SENSITIVE_WORDS) {
      if (hay.contains(word.toLowerCase(Locale.ROOT)) && !hits.contains(word)) {
        hits.add(word);
      }
    }
    return hits;
  }

  /** 一次带 label 动作的语义评估。 */
  public static final class Assessment {
    public final List<String> sensitiveHits;
    /** 需要摆到用户面前的一句说明；无需说明时为 null。 */
    public final String warning;

    Assessment(List<String> sensitiveHits, String warning) {
      this.sensitiveHits = sensitiveHits;
      this.warning = warning;
    }

    public boolean isSensitive() {
      return !sensitiveHits.isEmpty();
    }
  }

  /**
   * 评估 label：为空时给一条告警（手指下是什么无从得知，不能当无事发生）；命中敏感词时
   * 给出「这个动作退不回来」的说明。
   */
  public static Assessment assess(String label) {
    String text = label == null ? "" : label.trim();
    if (text.isEmpty()) {
      return new Assessment(
          new ArrayList<String>(),
          "这次动作没有提供 label（手指下/目标元素的文字），无从判断它会提交什么；"
              + "请先读到按钮或输入框的文字再操作。");
    }
    List<String> hits = matchSensitive(text);
    if (hits.isEmpty()) {
      return new Assessment(hits, null);
    }
    String shown = text.length() > 60 ? text.substring(0, 60) : text;
    StringBuilder joined = new StringBuilder();
    for (String hit : hits) {
      if (joined.length() > 0) {
        joined.append("、");
      }
      joined.append(hit);
    }
    return new Assessment(
        hits,
        "目标「" + shown + "」命中敏感词：" + joined + "。"
            + "付款、转账、发送、删除这类动作按下后退不回来，已按敏感档处理，本次必须逐次确认且不会被记忆。");
  }

  /**
   * 截图 caption 一行：{@code App 名 (包名) · Activity · 宽×高 · keyboard shown|hidden}。
   * 拿不到的字段直接略去，不留空占位。
   */
  public static String caption(
      String appName, String packageName, String activity, int width, int height, Boolean keyboardShown) {
    List<String> parts = new ArrayList<>(4);
    String label = appLabel(appName, packageName);
    if (label != null) {
      parts.add(label);
    }
    String act = activity == null ? "" : activity.trim();
    if (!act.isEmpty()) {
      parts.add(act);
    }
    if (width > 0 && height > 0) {
      parts.add(width + "×" + height);
    }
    if (keyboardShown != null) {
      parts.add(keyboardShown ? "keyboard shown" : "keyboard hidden");
    }
    StringBuilder sb = new StringBuilder();
    for (String part : parts) {
      if (sb.length() > 0) {
        sb.append(" · ");
      }
      sb.append(part);
    }
    return sb.toString();
  }

  /** {@code 名 (包名)}；只有名/只有包名时用其一；两者相同只留一个；都空返回 null。 */
  private static String appLabel(String appName, String packageName) {
    String name = appName == null ? "" : appName.trim();
    String pkg = packageName == null ? "" : packageName.trim();
    if (!name.isEmpty() && !pkg.isEmpty() && !name.equals(pkg)) {
      return name + " (" + pkg + ")";
    }
    if (!name.isEmpty()) {
      return name;
    }
    return pkg.isEmpty() ? null : pkg;
  }
}

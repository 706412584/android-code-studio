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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 用量与成本统计：按「服务商 + 日」累计**服务端真实 usage**，供设置页展示。
 *
 * <p>来源是 RunContextManager 的 ResponseListener（每次模型响应后回调），
 * 比本地 token 估算可靠。存储用 SharedPreferences 整数字段（量级小、结构简单），
 * 按 provider 分组累计「今日」与「总计」。
 *
 * <p>成本只是**估算**：价格表在 ProviderPresets（未列价格的服务商只展示 token 数），
 * 展示文案必须带「估算」字样，不作为账单依据。
 */
public final class UsageStatsStore {

  private static final String PREFS_NAME = "ai_usage_stats";
  private static final String KEY_PROVIDERS = "providers";

  private final SharedPreferences prefs;

  public UsageStatsStore(Context context) {
    this.prefs =
        context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  /** 记录一次模型响应的真实 usage（input/output token）。循环线程上调用，轻量。 */
  public synchronized void record(String providerId, int inputTokens, int outputTokens) {
    if (providerId == null || providerId.isEmpty() || (inputTokens <= 0 && outputTokens <= 0)) {
      return;
    }
    rememberProvider(providerId);
    String day = dayKey();
    SharedPreferences.Editor editor = prefs.edit();
    putDelta(editor, "in:" + providerId + ":" + day, inputTokens);
    putDelta(editor, "out:" + providerId + ":" + day, outputTokens);
    putDelta(editor, "in:" + providerId + ":total", inputTokens);
    putDelta(editor, "out:" + providerId + ":total", outputTokens);
    editor.apply();
  }

  private void rememberProvider(String providerId) {
    Set<String> providers =
        new LinkedHashSet<>(prefs.getStringSet(KEY_PROVIDERS, Collections.emptySet()));
    if (providers.add(providerId)) {
      prefs.edit().putStringSet(KEY_PROVIDERS, providers).apply();
    }
  }

  private void putDelta(SharedPreferences.Editor editor, String key, int delta) {
    if (delta <= 0) {
      return;
    }
    editor.putInt(key, prefs.getInt(key, 0) + delta);
  }

  /** 一个服务商的用量（今日与总计），成本按 ProviderPresets 价格表估算。 */
  public static final class Usage {
    public final String providerId;
    public final int todayInput;
    public final int todayOutput;
    public final int totalInput;
    public final int totalOutput;

    Usage(String providerId, int todayInput, int todayOutput, int totalInput, int totalOutput) {
      this.providerId = providerId;
      this.todayInput = todayInput;
      this.todayOutput = todayOutput;
      this.totalInput = totalInput;
      this.totalOutput = totalOutput;
    }

    /** 是否有可用价格（没有时 UI 只展示 token 数，不估算成本）。 */
    public boolean hasPrice() {
      return ProviderPresets.inputPricePerMTokens(providerId) > 0
          || ProviderPresets.outputPricePerMTokens(providerId) > 0;
    }

    /** 累计用量的估算成本（USD，按 ProviderPresets 价格表）。 */
    public double estimatedCostUsd() {
      return (totalInput * ProviderPresets.inputPricePerMTokens(providerId)
              + totalOutput * ProviderPresets.outputPricePerMTokens(providerId))
          / 1_000_000.0;
    }
  }

  /** 全部服务商的用量快照（按 provider id 字典序）。 */
  public synchronized List<Usage> snapshot() {
    String day = dayKey();
    List<Usage> result = new ArrayList<>();
    for (String providerId : prefs.getStringSet(KEY_PROVIDERS, Collections.emptySet())) {
      result.add(
          new Usage(
              providerId,
              prefs.getInt("in:" + providerId + ":" + day, 0),
              prefs.getInt("out:" + providerId + ":" + day, 0),
              prefs.getInt("in:" + providerId + ":total", 0),
              prefs.getInt("out:" + providerId + ":total", 0)));
    }
    result.sort((a, b) -> a.providerId.compareTo(b.providerId));
    return result;
  }

  /** 清空全部统计（设置页的「重置」）。 */
  public void reset() {
    prefs.edit().clear().apply();
  }

  private static String dayKey() {
    return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        .format(new java.util.Date());
  }
}

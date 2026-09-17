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

/**
 * token 用量跟踪：把服务端返回的真实 usage 与本地估算结合，决定何时压缩。
 *
 * <p><b>为什么两套数字都要</b>：估算用于事前决策（裁剪必须在发请求前完成），
 * 真实 usage 用于事后校准（估算总有偏差，尤其是不同服务商的分词差异）。
 * 本类记录最近一次真实值，作为下次估算的基准偏移。
 *
 * <p>阈值语义与上游一致：软阈值触发后台压缩，硬阈值强制压缩。
 */
public final class TokenUsageTracker {

  /** 硬压缩触发比例：达到上下文窗口的此比例必须压缩。 */
  public static final double COMPACT_TRIGGER_RATIO = 0.8d;

  /** 软压缩触发比例：达到此比例可在后台准备压缩。 */
  public static final double SOFT_COMPACT_TRIGGER_RATIO = 0.5d;

  private int contextSize;
  private int lastReportedInputTokens;
  private int lastEstimatedTokens;
  private int peakTokens;

  public TokenUsageTracker(int contextSize) {
    this.contextSize = Math.max(0, contextSize);
  }

  public int getContextSize() {
    return contextSize;
  }

  public void setContextSize(int contextSize) {
    this.contextSize = Math.max(0, contextSize);
  }

  /**
   * 记录一次请求：同时记下估算值与（若有）服务端回报的真实值。
   *
   * @param estimated 发请求前的本地估算
   * @param reported 服务端 usage；协议未提供时传 0
   */
  public void record(int estimated, int reported) {
    this.lastEstimatedTokens = Math.max(0, estimated);
    if (reported > 0) {
      this.lastReportedInputTokens = reported;
    }
    this.peakTokens = Math.max(peakTokens, Math.max(this.lastEstimatedTokens, this.lastReportedInputTokens));
  }

  /**
   * 当前用量的最佳估计。
   *
   * <p>有真实 usage 时以它为准——它包含工具定义、系统提示词等本地估算看不到的部分。
   */
  public int currentTokens() {
    return lastReportedInputTokens > 0 ? lastReportedInputTokens : lastEstimatedTokens;
  }

  public int getLastReportedInputTokens() {
    return lastReportedInputTokens;
  }

  public int getLastEstimatedTokens() {
    return lastEstimatedTokens;
  }

  /** 本次会话观测到的峰值用量。 */
  public int getPeakTokens() {
    return peakTokens;
  }

  /** 上下文窗口未配置（为 0）时无法判断，一律返回 false。 */
  public boolean shouldHardCompact() {
    if (contextSize <= 0) {
      return false;
    }
    return currentTokens() >= contextSize * COMPACT_TRIGGER_RATIO;
  }

  public boolean shouldSoftCompact() {
    if (contextSize <= 0) {
      return false;
    }
    return currentTokens() >= contextSize * SOFT_COMPACT_TRIGGER_RATIO;
  }

  /**
   * 还能再容纳多少 token。
   *
   * @return 剩余额度；上下文窗口未配置时返回 {@link Integer#MAX_VALUE}（视为不限制）
   */
  public int remaining() {
    if (contextSize <= 0) {
      return Integer.MAX_VALUE;
    }
    return Math.max(0, contextSize - currentTokens());
  }

  /** 使用率（0~1）；上下文窗口未配置时返回 0。 */
  public double usageRatio() {
    if (contextSize <= 0) {
      return 0d;
    }
    return Math.min(1d, (double) currentTokens() / contextSize);
  }

  /** 新一轮会话开始时重置峰值与真实值。 */
  public void reset() {
    lastReportedInputTokens = 0;
    lastEstimatedTokens = 0;
    peakTokens = 0;
  }
}

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 用量跟踪：真实 usage 与本地估算的取舍，以及软/硬压缩阈值的边界。
 *
 * <p>阈值是"大于等于"语义（{@code >=}），边界值必须落在触发侧——差一个 token
 * 就漏压缩，会直接表现为下一次请求超限。
 */
final class TokenUsageTrackerTest {

  private static final int CONTEXT = 100_000;

  @Test
  void reportedUsageWinsOverEstimate() {
    TokenUsageTracker tracker = new TokenUsageTracker(CONTEXT);
    tracker.record(1_000, 5_000);
    // 服务端数字含工具定义与系统提示词，本地估算看不到这些
    assertEquals(5_000, tracker.currentTokens());
    assertEquals(5_000, tracker.getLastReportedInputTokens());
    assertEquals(1_000, tracker.getLastEstimatedTokens());
  }

  @Test
  void fallsBackToEstimateWhenNoReportedUsage() {
    TokenUsageTracker tracker = new TokenUsageTracker(CONTEXT);
    tracker.record(1_234, 0);
    assertEquals(1_234, tracker.currentTokens());
    assertEquals(0, tracker.getLastReportedInputTokens());
  }

  @Test
  void keepsLastReportedWhenLaterRequestHasNoUsage() {
    TokenUsageTracker tracker = new TokenUsageTracker(CONTEXT);
    tracker.record(1_000, 5_000);
    tracker.record(2_000, 0);
    // 协议偶尔不回 usage，不该把已知的真实值抹成 0
    assertEquals(5_000, tracker.currentTokens());
    assertEquals(2_000, tracker.getLastEstimatedTokens());
  }

  @Test
  void peakTracksMaximumAcrossEstimatesAndReports() {
    TokenUsageTracker tracker = new TokenUsageTracker(CONTEXT);
    tracker.record(1_000, 5_000);
    tracker.record(9_000, 0);
    assertEquals(9_000, tracker.getPeakTokens());
    tracker.record(100, 3_000);
    assertEquals(9_000, tracker.getPeakTokens());
  }

  @Test
  void negativeInputsAreClampedToZero() {
    TokenUsageTracker tracker = new TokenUsageTracker(CONTEXT);
    tracker.record(-5, -5);
    assertEquals(0, tracker.currentTokens());
    assertEquals(0, tracker.getPeakTokens());
  }

  @Test
  void hardCompactTriggersAtExactlyEightyPercent() {
    TokenUsageTracker tracker = new TokenUsageTracker(CONTEXT);
    tracker.record(79_999, 0);
    assertFalse(tracker.shouldHardCompact());
    assertTrue(tracker.shouldSoftCompact());

    tracker.record(80_000, 0);
    assertTrue(tracker.shouldHardCompact(), "边界值 0.8 应落在触发侧");
  }

  @Test
  void softCompactTriggersAtExactlyFiftyPercent() {
    TokenUsageTracker tracker = new TokenUsageTracker(CONTEXT);
    tracker.record(49_999, 0);
    assertFalse(tracker.shouldSoftCompact());

    tracker.record(50_000, 0);
    assertTrue(tracker.shouldSoftCompact(), "边界值 0.5 应落在触发侧");
    assertFalse(tracker.shouldHardCompact());
  }

  @Test
  void unconfiguredContextSizeDisablesCompaction() {
    TokenUsageTracker tracker = new TokenUsageTracker(0);
    tracker.record(10_000_000, 0);
    // 上下文窗口未知时无法判断，一律不压缩——误判会砍掉正常对话
    assertFalse(tracker.shouldHardCompact());
    assertFalse(tracker.shouldSoftCompact());
    assertEquals(0d, tracker.usageRatio());
    assertEquals(Integer.MAX_VALUE, tracker.remaining());
  }

  @Test
  void negativeContextSizeTreatedAsUnconfigured() {
    TokenUsageTracker tracker = new TokenUsageTracker(-100);
    assertEquals(0, tracker.getContextSize());
    assertEquals(Integer.MAX_VALUE, tracker.remaining());
  }

  @Test
  void remainingNeverGoesNegative() {
    TokenUsageTracker tracker = new TokenUsageTracker(CONTEXT);
    tracker.record(150_000, 0);
    assertEquals(0, tracker.remaining());
  }

  @Test
  void remainingSubtractsCurrentUsage() {
    TokenUsageTracker tracker = new TokenUsageTracker(CONTEXT);
    tracker.record(1_000, 30_000);
    assertEquals(70_000, tracker.remaining());
  }

  @Test
  void usageRatioIsClampedToOne() {
    TokenUsageTracker tracker = new TokenUsageTracker(CONTEXT);
    tracker.record(30_000, 0);
    assertEquals(0.3d, tracker.usageRatio(), 1e-9);
    tracker.record(200_000, 0);
    assertEquals(1d, tracker.usageRatio(), 1e-9);
  }

  @Test
  void resetClearsPeakAndBothTokenCounts() {
    TokenUsageTracker tracker = new TokenUsageTracker(CONTEXT);
    tracker.record(30_000, 90_000);
    tracker.reset();
    assertEquals(0, tracker.currentTokens());
    assertEquals(0, tracker.getPeakTokens());
    assertEquals(0, tracker.getLastEstimatedTokens());
    assertEquals(0, tracker.getLastReportedInputTokens());
    assertFalse(tracker.shouldSoftCompact());
  }

  @Test
  void setContextSizeReclampsToNonNegative() {
    TokenUsageTracker tracker = new TokenUsageTracker(CONTEXT);
    tracker.setContextSize(-1);
    assertEquals(0, tracker.getContextSize());
    tracker.setContextSize(8_000);
    assertEquals(8_000, tracker.getContextSize());
  }
}

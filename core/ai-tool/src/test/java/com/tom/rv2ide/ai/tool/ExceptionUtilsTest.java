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

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link ExceptionUtils}：异常描述与中断恢复。
 *
 * <p>describeException 的输出会进入工具错误文案——null/空消息必须回落到类名，
 * 否则模型会看到一句空的「失败：」无从判断。
 * restoreInterrupt 必须只在 InterruptedException 上恢复中断位，否则会误置
 * 无关异常的线程中断标志，污染后续 I/O。
 */
final class ExceptionUtilsTest {

  @Test
  void nullExceptionDescribesAsUnknown() {
    assertEquals("Unknown error", ExceptionUtils.describeException(null));
  }

  @Test
  void messageIsUsedWhenPresentAndTrimmed() {
    assertEquals("磁盘已满", ExceptionUtils.describeException(new RuntimeException("  磁盘已满  ")));
  }

  @Test
  void blankMessageFallsBackToClassName() {
    assertEquals("IllegalStateException",
        ExceptionUtils.describeException(new IllegalStateException()));
    assertEquals("IllegalStateException",
        ExceptionUtils.describeException(new IllegalStateException("   ")));
  }

  @Test
  void anonymousExceptionWithNoSimpleNameFallsBackToUnknown() {
    // 匿名类 getSimpleName() 为空——不能返回空串。
    Exception anonymous = new Exception() {};
    assertEquals("Unknown error", ExceptionUtils.describeException(anonymous));
  }

  @Test
  void restoreInterruptSetsFlagForInterruptedException() throws Exception {
    Thread.interrupted(); // 清干净
    ExceptionUtils.restoreInterrupt(new InterruptedException("stop"));
    assertTrue(Thread.currentThread().isInterrupted(), "中断位应被恢复");
    Thread.interrupted(); // 清理，避免污染后续测试
  }

  @Test
  void restoreInterruptIgnoresOtherExceptions() {
    Thread.interrupted();
    ExceptionUtils.restoreInterrupt(new RuntimeException("boom"));
    assertFalse(Thread.currentThread().isInterrupted(), "无关异常不得置中断位");
  }
}

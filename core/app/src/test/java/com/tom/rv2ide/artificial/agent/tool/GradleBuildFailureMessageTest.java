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

package com.tom.rv2ide.artificial.agent.tool;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult;
import org.junit.Test;

/**
 * 构建失败文案的「可执行性」。
 *
 * <p><b>为什么需要这组测试</b>：实测设备会话里 {@code gradle_build} 4 次调用全部失败，
 * 返回的都是光秃秃的 {@code PROJECT_NOT_INITIALIZED}——模型只知道「失败了」，不知道是
 * 「还没同步完」还是「这不是 Gradle 项目」，于是反复重试同一条构建。错误文案不指向
 * 下一步，就等于把猜的成本转嫁给模型。
 *
 * <p>这里钉住两件事：每个失败原因都有非空文案，且文案里含**可执行的动作词**。
 */
public class GradleBuildFailureMessageTest {

  /** 所有可能的失败原因。新增枚举值时这里会漏掉——由下方完整性测试兜底。 */
  private static final TaskExecutionResult.Failure[] ALL =
      TaskExecutionResult.Failure.values();

  @Test
  public void everyFailureHasANonEmptyMessage() {
    for (TaskExecutionResult.Failure failure : ALL) {
      String message = GradleBuildTool.describeFailure(failure);
      assertNotNull(failure + " 的文案不应为 null", message);
      assertTrue(failure + " 的文案不应为空", message.trim().length() > 0);
    }
  }

  /**
   * 文案必须含「下一步」的动作指引，而不是只复述原因。
   *
   * <p>允许的动作词覆盖了本工具能引导的方向：等待/同步/检查/重试/打开项目等。
   * 只写「构建失败」这类复述不算合格。
   */
  @Test
  public void everyFailurePointsToANextAction() {
    String[] actionWords = {
      "等待", "同步", "检查", "重试", "打开", "确认", "重新", "查看", "换成", "定位"
    };
    for (TaskExecutionResult.Failure failure : ALL) {
      String message = GradleBuildTool.describeFailure(failure);
      boolean hasAction = false;
      for (String word : actionWords) {
        if (message.contains(word)) {
          hasAction = true;
          break;
        }
      }
      assertTrue(
          failure + " 的文案应指向可执行的下一步，实际为: " + message, hasAction);
    }
  }

  /**
   * 最容易误诊的那个：必须说清「等待初始化」且「重试无意义」。
   *
   * <p>实测模型正是在这里反复重试——它把「项目未初始化」理解成了「构建偶发失败」。
   */
  @Test
  public void projectNotInitializedExplainsWaitingAndDiscouragesRetry() {
    String message =
        GradleBuildTool.describeFailure(TaskExecutionResult.Failure.PROJECT_NOT_INITIALIZED);
    assertTrue("应说明需要等待/同步", message.contains("同步") || message.contains("等待"));
    assertTrue("应劝阻无意义的重试，实际: " + message, message.contains("重试"));
  }

  @Test
  public void buildFailedDefersToTheOutputBelow() {
    String message = GradleBuildTool.describeFailure(TaskExecutionResult.Failure.BUILD_FAILED);
    assertTrue("应指向下方输出", message.contains("输出"));
  }

  /** 文案必须彼此不同——全都退化成同一句就等于没翻译。 */
  @Test
  public void messagesAreDistinctPerFailure() {
    for (int i = 0; i < ALL.length; i++) {
      for (int j = i + 1; j < ALL.length; j++) {
        String a = GradleBuildTool.describeFailure(ALL[i]);
        String b = GradleBuildTool.describeFailure(ALL[j]);
        assertNotEquals(
            "不同失败原因不该给出相同文案: " + ALL[i] + " / " + ALL[j], a, b);
      }
    }
  }

  /** 未知原因也要有兜底文案，不能抛异常或返回 null。 */
  @Test
  public void unknownFailureStillGetsAMessage() {
    String message = GradleBuildTool.describeFailure(null);
    assertNotNull("null 不应导致崩溃", message);
    assertTrue(message.length() > 0);
  }
}

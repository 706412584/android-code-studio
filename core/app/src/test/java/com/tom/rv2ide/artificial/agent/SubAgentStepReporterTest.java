/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.tom.rv2ide.ai.agent.AgentEvent;
import com.tom.rv2ide.ai.tool.SubAgentRunner;
import com.tom.rv2ide.ai.tool.api.ToolCall;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * {@link SubAgentStepReporter} 的行为测试。
 *
 * <p><b>为什么钉住它</b>：子 agent 的中间过程刻意不进主对话，这些折叠出的步骤行
 * 是用户运行期间**唯一**能看到的实时信息——拼错了（丢动作名、泄漏增量文本）
 * 用户要么看不懂，要么上下文隔离被破坏。
 */
public final class SubAgentStepReporterTest {

  private final List<String> lines = new ArrayList<>();
  private final SubAgentStepReporter reporter =
      new SubAgentStepReporter(lines::add);

  @Test
  public void toolStartedBecomesAStepLineWithCanonicalNameAndTarget() {
    reporter.onEvent(
        AgentEvent.toolStarted(
            new ToolCall("1", "read_file", "{\"file_path\":\"app/build.gradle.kts\"}")));

    assertEquals(1, lines.size());
    String line = lines.get(0);
    // 别名归一（read_file → file_read），否则状态条会显示模型拼的野生名字。
    assertTrue("应含规范名：" + line, line.contains("file_read"));
    assertTrue("应含步骤序号：" + line, line.contains("步骤 1"));
    assertTrue("应含对象名：" + line, line.contains("build.gradle.kts"));
  }

  @Test
  public void turnStartedReportsThinkingWithTurnIndex() {
    reporter.onEvent(AgentEvent.turnStarted(1));
    reporter.onEvent(AgentEvent.turnStarted(2));

    assertEquals(2, lines.size());
    assertTrue(lines.get(0), lines.get(0).contains("第 1 轮"));
    assertTrue(lines.get(1), lines.get(1).contains("第 2 轮"));
  }

  @Test
  public void textAndReasoningDeltasAreNotForwarded() {
    // 上下文隔离的核心断言：子 agent 的私有输出不得经此通道回传。
    reporter.onEvent(AgentEvent.textDelta("子 agent 正在写的正文"));
    reporter.onEvent(AgentEvent.reasoningDelta("内部推理"));

    assertEquals(0, lines.size());
  }

  @Test
  public void completedSummarizesTurnsAndToolCalls() {
    reporter.onEvent(AgentEvent.toolStarted(new ToolCall("1", "file_read", "{}")));
    reporter.onEvent(AgentEvent.completed("结论"));

    String last = lines.get(lines.size() - 1);
    assertTrue(last, last.contains("完成"));
    assertTrue(last, last.contains("1 次工具调用"));
  }

  @Test
  public void failedCarriesTheReason() {
    reporter.onEvent(AgentEvent.failed("额度耗尽"));

    assertEquals(1, lines.size());
    assertTrue(lines.get(0), lines.get(0).contains("额度耗尽"));
  }

  @Test
  public void sinkExceptionsDoNotPropagate() {
    // 进度是尽力而为的旁路：监听方出错不得让子 agent 运行失败。
    SubAgentStepReporter reporter =
        new SubAgentStepReporter(
            message -> {
              throw new RuntimeException("sink 坏了");
            });

    reporter.onEvent(AgentEvent.turnStarted(1)); // 不应抛异常
  }
}

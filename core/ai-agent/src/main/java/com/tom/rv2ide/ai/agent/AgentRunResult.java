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

package com.tom.rv2ide.ai.agent;

/** 一次 agent 循环的结果。 */
public final class AgentRunResult {

  private final String output;
  private final int toolCallCount;
  private final int turns;
  private final boolean failed;

  public AgentRunResult(String output, int toolCallCount, int turns, boolean failed) {
    this.output = output == null ? "" : output;
    this.toolCallCount = toolCallCount;
    this.turns = turns;
    this.failed = failed;
  }

  /** 最终输出文本；失败时为失败原因。 */
  public String getOutput() {
    return output;
  }

  /** 实际执行的工具调用总数。 */
  public int getToolCallCount() {
    return toolCallCount;
  }

  /** 与模型往返的轮数。 */
  public int getTurns() {
    return turns;
  }

  /** 是否因取消、超预算或错误而终止（而非模型自然结束）。 */
  public boolean isFailed() {
    return failed;
  }

  @Override
  public String toString() {
    return "AgentRunResult{turns=" + turns + ", toolCalls=" + toolCallCount
        + ", failed=" + failed + ", output=" + output.length() + " chars}";
  }
}

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

import com.tom.rv2ide.ai.protocol.AssistantModelMessage;
import com.tom.rv2ide.ai.protocol.ModelMessage;
import com.tom.rv2ide.ai.protocol.ToolModelMessage;
import com.tom.rv2ide.ai.tool.api.ToolCall;
import java.util.List;

/**
 * 把一段对话历史摘要为要点。
 *
 * <p><b>为什么需要压缩而不只是裁剪</b>：裁剪会永久丢失信息——用户早期说过的约束
 * （"别改这个文件"、"用 Kotlin 不用 Java"）被丢掉后，模型会在后续轮次里违反它。
 * 压缩把这段历史换成一段摘要，用少量 token 保住关键约束。
 *
 * <p><b>为什么模型调用是可注入接口</b>：摘要质量依赖真实模型，但"何时压缩、
 * 压缩哪一段、失败怎么办"这些决策逻辑与网络无关。把调用抽象为
 * {@link Summarizer} 后，决策逻辑可 JVM 单测；真实实现由 app 层注入。
 *
 * <p><b>失败必须降级而非抛错</b>：压缩是优化手段，不是主流程。摘要调用失败时
 * 返回 {@code null}，调用方回退到纯裁剪——绝不能因为压缩失败让用户发不出消息。
 */
public final class ContextCompactor {

  /** 摘要提示词。要求保留可执行的约束与结论，而非复述过程。 */
  static final String SUMMARIZE_INSTRUCTION =
      "请把下面这段编码助手对话压缩为要点摘要，用于替代原始历史继续对话。\n"
          + "必须保留：\n"
          + "1. 用户提出的约束与偏好（不要做什么、必须用什么）\n"
          + "2. 已确认的技术结论与决策\n"
          + "3. 已修改的文件及其作用\n"
          + "4. 尚未完成的待办\n"
          + "不要保留：寒暄、失败的尝试细节、重复的工具输出。\n"
          + "直接输出摘要正文，不要加前言。";

  /** 摘要模型调用。实现方负责真正发起请求。 */
  public interface Summarizer {
    /**
     * @param prompt 已拼好的摘要请求
     * @return 摘要正文；失败时返回 {@code null} 或空串
     */
    String summarize(String prompt) throws Exception;
  }

  private ContextCompactor() {}

  /**
   * 压缩一段消息为摘要。
   *
   * @param messages 待压缩的消息（应为完整轮次，不含系统提示词）
   * @param summarizer 摘要模型调用
   * @return 摘要文本；消息为空、摘要为空或调用失败时返回 {@code null}
   */
  public static String compact(List<ModelMessage> messages, Summarizer summarizer) {
    if (messages == null || messages.isEmpty() || summarizer == null) {
      return null;
    }
    String prompt = buildPrompt(messages);
    try {
      String summary = summarizer.summarize(prompt);
      if (summary == null || summary.trim().isEmpty()) {
        return null;
      }
      return summary.trim();
    } catch (Exception e) {
      // 压缩是优化，失败不能让主流程失败——调用方会回退到纯裁剪。
      return null;
    }
  }

  /**
   * 把消息渲染为摘要请求。
   *
   * <p>工具调用与其结果合并展示：单独列出工具结果会丢失"模型当时想做什么"，
   * 而只列调用会丢失结果。压缩要保住的是决策与结论。
   */
  static String buildPrompt(List<ModelMessage> messages) {
    StringBuilder builder = new StringBuilder(SUMMARIZE_INSTRUCTION);
    builder.append("\n\n=== 对话开始 ===\n");
    for (ModelMessage message : messages) {
      builder.append('\n').append(roleLabel(message)).append(": ");
      if (!message.getContent().isEmpty()) {
        builder.append(message.getContent());
      }
      if (message instanceof AssistantModelMessage) {
        for (ToolCall call : ((AssistantModelMessage) message).getToolCalls()) {
          builder.append("\n  [调用工具 ").append(call.getName()).append("] ");
          builder.append(abbreviate(call.getArguments(), 300));
        }
      }
      if (message instanceof ToolModelMessage) {
        ToolModelMessage tool = (ToolModelMessage) message;
        if (!tool.getToolName().isEmpty()) {
          builder.append("（来自 ").append(tool.getToolName()).append("）");
        }
        if (tool.isToolError()) {
          builder.append(" [失败]");
        }
      }
      builder.append('\n');
    }
    builder.append("\n=== 对话结束 ===\n");
    return builder.toString();
  }

  private static String roleLabel(ModelMessage message) {
    if (message instanceof ToolModelMessage) {
      return "工具结果";
    }
    if (message instanceof AssistantModelMessage) {
      return "助手";
    }
    return "用户";
  }

  /** 工具参数可能很长（如整份文件内容），摘要时截断以免请求本身超限。 */
  private static String abbreviate(String text, int limit) {
    if (text == null) {
      return "";
    }
    return text.length() <= limit ? text : text.substring(0, limit) + "…";
  }
}

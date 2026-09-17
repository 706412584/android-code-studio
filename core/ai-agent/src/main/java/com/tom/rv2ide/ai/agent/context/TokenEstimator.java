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
import com.tom.rv2ide.ai.tool.api.ToolInfo;
import java.util.List;
import org.json.JSONException;

/**
 * 本地 token 估算。
 *
 * <p><b>为什么需要估算而非只用服务端 usage</b>：usage 只有在请求发出之后才知道，
 * 而裁剪必须在发请求之前完成。服务端 usage 用于事后校准（见
 * {@link TokenUsageTracker}），估算用于事前决策。
 *
 * <p><b>估算方法</b>：不引入 tokenizer 依赖（体积大、且各模型词表不同）。
 * 采用按字符类别的启发式：
 * <ul>
 *   <li>CJK 字符约 1 token/字（中文常见 1~1.5 字/token，取偏保守的 1）
 *   <li>其余字符约 4 字符/token（英文经验值）
 * </ul>
 * 宁可高估：高估会导致稍早压缩（损失一点上下文），低估则会导致请求超限失败——
 * 后者是硬失败，前者只是效率问题。
 */
public final class TokenEstimator {

  /** 非 CJK 字符的每 token 字符数。 */
  private static final double CHARS_PER_TOKEN = 4.0d;

  /** 每条消息的固定开销（角色标记、分隔符等）。 */
  private static final int PER_MESSAGE_OVERHEAD = 4;

  private TokenEstimator() {}

  /** 估算一段文本的 token 数。 */
  public static int estimate(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    int cjk = 0;
    int other = 0;
    for (int i = 0; i < text.length(); i++) {
      if (isCjk(text.charAt(i))) {
        cjk++;
      } else {
        other++;
      }
    }
    // CJK 按 1 token/字；其余按 4 字符/token 向上取整
    return cjk + (int) Math.ceil(other / CHARS_PER_TOKEN);
  }

  /** 估算一条消息的 token 数，含工具调用与固定开销。 */
  public static int estimate(ModelMessage message) {
    if (message == null) {
      return 0;
    }
    int total = PER_MESSAGE_OVERHEAD;
    total += estimate(message.getContent());
    total += estimate(message.getReasoningContent());
    total += estimate(message.getToolCallId());
    total += estimate(message.getToolName());
    if (message instanceof AssistantModelMessage) {
      for (ToolCall call : message.getToolCalls()) {
        total += estimate(call.getName());
        total += estimate(call.getArguments());
      }
    }
    return total;
  }

  /** 估算一组消息的总 token 数。 */
  public static int estimate(List<ModelMessage> messages) {
    if (messages == null) {
      return 0;
    }
    int total = 0;
    for (ModelMessage message : messages) {
      total += estimate(message);
    }
    return total;
  }

  /**
   * 估算工具定义集合的 token 数（名称 + 描述 + 参数 schema）。
   *
   * <p><b>为什么必须算进来</b>：协议原生工具会随每次请求一起发出，它们占窗口但不在
   * 历史消息里。若预算只扣系统提示词，实际可用历史就比算出来的少——历史刚好卡在预算上时
   * 请求就会超限。这是"估算偏小导致硬失败"的典型来源。
   */
  public static int estimateTools(List<? extends ToolInfo> tools) {
    if (tools == null) {
      return 0;
    }
    int total = 0;
    for (ToolInfo tool : tools) {
      if (tool == null) {
        continue;
      }
      total += estimate(tool.getName());
      total += estimate(tool.getDescription());
      try {
        total += estimate(String.valueOf(tool.getParameters()));
      } catch (JSONException e) {
        // 参数 schema 不可序列化时这部分不计。估算偏小由输出预留兜底，
        // 不值得为它中断一次对话。
      }
    }
    return total;
  }

  /**
   * CJK 与相关表意文字判定。
   *
   * <p>覆盖范围：中日韩统一表意文字（含扩展 A）、中日韩标点、全角字符、
   * 日文假名、韩文谚文。这些字符的信息密度高，按 1 token/字估算。
   */
  private static boolean isCjk(char c) {
    Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
        || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
        || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
        || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
        || block == Character.UnicodeBlock.HIRAGANA
        || block == Character.UnicodeBlock.KATAKANA
        || block == Character.UnicodeBlock.HANGUL_SYLLABLES;
  }

  /** 供测试与调试：判断是否为工具结果消息。 */
  static boolean isToolMessage(ModelMessage message) {
    return message instanceof ToolModelMessage;
  }
}

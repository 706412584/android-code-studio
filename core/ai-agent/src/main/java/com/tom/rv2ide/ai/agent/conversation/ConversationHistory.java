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

package com.tom.rv2ide.ai.agent.conversation;

import com.tom.rv2ide.ai.protocol.AssistantModelMessage;
import com.tom.rv2ide.ai.protocol.ModelMessage;
import com.tom.rv2ide.ai.protocol.ImageInputPayload;
import com.tom.rv2ide.ai.protocol.ToolModelMessage;
import com.tom.rv2ide.ai.protocol.UserModelMessage;
import com.tom.rv2ide.ai.tool.api.ToolArgsCleaner;
import com.tom.rv2ide.ai.tool.api.ToolCall;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;

/**
 * 把会话条目还原为可续接的模型消息列表。
 *
 * <p><b>为什么需要它</b>：{@code AgentSession.run()} 每次都以空消息列表开始，因此
 * 重启后无法续接对话。把历史条目折叠成 {@link ModelMessage} 列表后作为起点传入，
 * 循环就能在既有上下文上继续。
 *
 * <p><b>压缩语义已在此实现</b>：遇到 {@link CompactionEntry} 时，其 {@code upToOrdinal}
 * 之前的条目不再进入历史，改用该条目的摘要作为一条用户消息。P0-2 只需负责产生
 * 压缩条目，读取侧无需改动。
 *
 * <p><b>为什么跳过 meta 与标题条目</b>：它们不参与模型对话，只影响 UI 与摘要。
 * 把它们喂给模型会浪费 token 且可能干扰理解。
 */
public final class ConversationHistory {

  private ConversationHistory() {}

  /**
   * 折叠条目为模型消息。
   *
   * @param entries 会话全部条目（按序）
   * @return 可直接作为 {@code AgentSession} 起点的消息列表；无有效条目时为空列表
   */
  public static List<ModelMessage> fold(List<ConversationLog.EntryLocation> entries) {
    List<ModelMessage> messages = new ArrayList<>();
    if (entries == null) {
      return messages;
    }

    // 第一趟：找出被压缩覆盖的最大序号，并按序收集摘要。
    // 必须与追加分开做——压缩条目是**回溯性**标记，它出现在被覆盖条目之后，
    // 单趟遍历时那些条目早已被追加，无法再撤回。
    int compactedUpTo = -1;
    List<String> summaries = new ArrayList<>();
    for (ConversationLog.EntryLocation location : entries) {
      if (location.getEntry() instanceof CompactionEntry) {
        CompactionEntry compaction = (CompactionEntry) location.getEntry();
        if (compaction.getUpToOrdinal() > compactedUpTo) {
          compactedUpTo = compaction.getUpToOrdinal();
        }
        // 摘要无条件保留：后一次压缩可能建立在前一次之上，丢弃会丢失信息。
        if (!compaction.getSummary().isEmpty()) {
          summaries.add(compaction.getSummary());
        }
      }
    }

    // 第二趟：只追加未被压缩覆盖的对话条目，并保证**工具调用与结果成对**。
    //
    // <p><b>为什么必须在这里清洗</b>：真实会话里出现过「流在参数中途断开，调用以残缺
    // JSON 落盘」的历史（Renderer.cpp 那次）。坏调用原样重发时服务端报
    // {@code 400 Assistant tool call arguments must be valid JSON}，且**之后每一次请求
    // 都失败**、与所切换的服务商无关——坏数据在历史里，用户换服务商、换模型都无效。
    // 另一半是孤儿结果：以工具结果开头发请求时多数服务商会报 400（找不到对应 tool_call）。
    // 折叠是发请求前的最后一道，两者都在这里收口。
    Set<String> resultIds = collectResultIds(entries, compactedUpTo);
    // 上一条助手消息中「参数合法且存在配对结果」的调用 id；结果只与它配对。
    Set<String> lastKeptCallIds = new HashSet<>();
    // 已写出的结果 id：同一 id 的重复结果只保留第一条。
    Set<String> emittedResultIds = new HashSet<>();
    for (ConversationLog.EntryLocation location : entries) {
      ConversationEntry entry = location.getEntry();
      if (entry instanceof CompactionEntry) {
        continue;
      }
      if (location.getOrdinal() <= compactedUpTo) {
        continue;
      }
      if (entry instanceof AssistantMessageEntry) {
        lastKeptCallIds =
            appendAssistantWithSanitizedCalls(
                (AssistantMessageEntry) entry, resultIds, messages);
        continue;
      }
      if (entry instanceof ToolResultEntry) {
        String callId = ((ToolResultEntry) entry).getToolCallId();
        if (!lastKeptCallIds.contains(callId) || !emittedResultIds.add(callId)) {
          continue;
        }
      } else if (entry instanceof UserMessageEntry) {
        // 结果必须紧跟其配对调用；用户消息开启新一轮，之前残留的配对集合作废
        // （正常日志不会出现这种排列，纯防御——宁可丢弃也不发出无配对的 tool 消息）。
        lastKeptCallIds = new HashSet<>();
      }
      append(entry, messages);
    }

    // 摘要放在最前：它是被压缩区间的替身，必须出现在后续对话之前。
    if (!summaries.isEmpty()) {
      messages.add(0, new UserModelMessage(String.join("\n\n", summaries)));
    }
    return messages;
  }

  private static void append(ConversationEntry entry, List<ModelMessage> messages) {
    ModelMessage message = toMessage(entry);
    if (message != null) {
      messages.add(message);
    }
  }

  /** 收集（未被压缩覆盖的）结果条目引用的调用 id。 */
  private static Set<String> collectResultIds(
      List<ConversationLog.EntryLocation> entries, int compactedUpTo) {
    Set<String> ids = new HashSet<>();
    for (ConversationLog.EntryLocation location : entries) {
      if (location.getOrdinal() <= compactedUpTo) {
        continue;
      }
      if (location.getEntry() instanceof ToolResultEntry) {
        ids.add(((ToolResultEntry) location.getEntry()).getToolCallId());
      }
    }
    return ids;
  }

  /**
   * 追加一条助手消息，并清洗其中的工具调用。
   *
   * <p>清洗两条规则（见 {@link #fold} 的注释）：
   * <ol>
   *   <li><b>参数必须是合法 JSON</b>。残缺参数（流中断）原样重发会被服务端
   *       400 拒绝（"arguments must be valid JSON"）。修复不了就丢弃该调用——
   *       宁可少一条历史，也不能让整个会话此后每次都失败。</li>
   *   <li><b>必须有配对结果</b>。严格的服务商要求每个 tool_call 都有对应的
   *       tool 消息应答（"must be followed by tool messages"），没有的丢弃。</li>
   * </ol>
   *
   * @return 保留下来的调用 id，供后续结果条目配对
   */
  private static Set<String> appendAssistantWithSanitizedCalls(
      AssistantMessageEntry assistant, Set<String> resultIds, List<ModelMessage> messages) {
    List<ToolCall> kept = new ArrayList<>();
    Set<String> keptIds = new HashSet<>();
    for (ToolCall call : assistant.getToolCalls()) {
      String repaired = repairArguments(call.getArguments());
      if (repaired == null || !resultIds.contains(call.getId())) {
        continue;
      }
      kept.add(new ToolCall(call.getId(), call.getName(), repaired));
      keptIds.add(call.getId());
    }
    // 清洗后什么都不剩（无正文、无推理、无调用）的消息整条跳过：它本来就只为工具调用
    // 而存在（纯调用轮的 content 常为空），留下一条空 assistant 只会污染上下文。
    if (kept.isEmpty()
        && assistant.getContent().trim().isEmpty()
        && assistant.getReasoningContent().trim().isEmpty()) {
      return keptIds;
    }
    messages.add(
        new AssistantModelMessage(assistant.getContent(), assistant.getReasoningContent(), kept));
    return keptIds;
  }

  /**
   * 把参数修成合法 JSON；修不了返回 null（该调用应被丢弃）。
   *
   * <p>先按原样解析（绝大多数调用一次通过，零开销）。失败时用 {@code ToolArgsCleaner}
   * 修一遍再试——**这与工具执行时的修复是同一套算法**，因此修复后的形态就是当时
   * 实际执行的形态（执行器同样先 clean 再 parse），历史与真实发生的事保持一致。
   * 仍解析失败才判死。空参数归一成 {@code "{}"}（与 {@link ToolCall} 的构造语义一致）。
   */
  private static String repairArguments(String arguments) {
    String raw = arguments == null ? "" : arguments.trim();
    if (raw.isEmpty()) {
      return "{}";
    }
    if (parsesAsJsonObject(raw)) {
      return raw;
    }
    String cleaned = ToolArgsCleaner.clean(raw).trim();
    if (!cleaned.isEmpty() && parsesAsJsonObject(cleaned)) {
      return cleaned;
    }
    return null;
  }

  private static boolean parsesAsJsonObject(String value) {
    try {
      new JSONObject(value);
      return true;
    } catch (org.json.JSONException e) {
      return false;
    }
  }

  /**
   * 把单条条目映射为模型消息；不参与模型对话的条目返回 {@code null}。
   *
   * <p>公开此方法是为了让压缩的预算计算能取到**单条**条目的成本——{@link #fold} 只给
   * 整体列表，而选择压缩边界需要逐条累加。
   */
  public static ModelMessage toMessage(ConversationEntry entry) {
    if (entry instanceof UserMessageEntry) {
      UserMessageEntry user = (UserMessageEntry) entry;
      // meta 条目是系统注入，不进入模型对话
      return user.isMeta() ? null : new UserModelMessage(user.getContent());
    }
    if (entry instanceof AssistantMessageEntry) {
      AssistantMessageEntry assistant = (AssistantMessageEntry) entry;
      return new AssistantModelMessage(
          assistant.getContent(), assistant.getReasoningContent(), assistant.getToolCalls());
    }
    if (entry instanceof ToolResultEntry) {
      ToolResultEntry result = (ToolResultEntry) entry;
      // 带图工具结果：把图片重新编码成 rawInputJson，协议层据此还原 image block。
      // 不这么做的话，历史里的图片在续接会话时会退化成一句文字说明，模型看不到图。
      String imageJson = null;
      if (result.getImageBase64().length() > 0) {
        try {
          imageJson =
              ImageInputPayload.imageResultJson(
                  result.getImageMimeType(), result.getImageBase64());
        } catch (org.json.JSONException ignored) {
          // 编码失败就退回纯文本，不影响对话继续。
        }
      }
      return new ToolModelMessage(
          result.getContent(),
          result.getToolCallId(),
          result.getToolName(),
          result.isError(),
          imageJson,
          // 步骤随 fold 带给 UI：回放时工具卡片要恢复步骤区（子代理过程不进主对话，
          // 这些行是唯一凭据）。协议序列化不读它，见 ToolModelMessage#getSteps。
          result.getSteps());
    }
    // SessionMetaEntry / TitleEntry / CompactionEntry 不参与模型对话
    return null;
  }
}

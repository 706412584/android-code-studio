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
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.tom.rv2ide.ai.agent.AgentEvent;
import com.tom.rv2ide.ai.agent.conversation.AssistantMessageEntry;
import com.tom.rv2ide.ai.agent.conversation.ConversationEntry;
import com.tom.rv2ide.ai.agent.conversation.ConversationHistory;
import com.tom.rv2ide.ai.agent.conversation.ConversationLog;
import com.tom.rv2ide.ai.agent.conversation.ConversationStore;
import com.tom.rv2ide.ai.agent.conversation.ConversationSummary;
import com.tom.rv2ide.ai.agent.conversation.FileConversationStore;
import com.tom.rv2ide.ai.agent.conversation.SessionMetaEntry;
import com.tom.rv2ide.ai.agent.conversation.ToolResultEntry;
import com.tom.rv2ide.ai.agent.conversation.UserMessageEntry;
import com.tom.rv2ide.ai.tool.api.ToolCall;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 落盘监听器的行为——从 {@link AgentOrchestrator} 的内部类提取而来。
 *
 * <p><b>为什么必须单测</b>：这次提取是「逻辑一字不改、只换外部依赖」的重构，而它的
 * 后果全是**静默**的：落盘少了一条，界面回放就缺一段内容；步骤行混桶，并行子代理的
 * 进度就会显示在别人的卡片上。两者都不报错，只有肉眼能在真机上发现。因此这里逐条
 * 钉住持久化的形状，让提取前后的行为可被机械地比对。
 *
 * <p>本测试直接驱动监听器（不经编排器），因为要验的正是「监听器本身写得对不对」。
 */
public class ConversationPersistingListenerTest {

  @Rule public final TemporaryFolder tmp = new TemporaryFolder();

  private ConversationStore store;

  private ConversationStore store() throws IOException {
    if (store == null) {
      store = new FileConversationStore(tmp.newFolder("conversations"));
    }
    return store;
  }

  private String newConversation() throws IOException {
    return store().create("测试会话", "/w", "m", "confirm").getId();
  }

  private ConversationPersistingListener listener(String conversationId, String userRequest) {
    return new ConversationPersistingListener(
        conversationId, userRequest, null, null, store, new Object());
  }

  private List<ConversationEntry> entries(String conversationId) throws IOException {
    List<ConversationEntry> out = new ArrayList<>();
    for (ConversationLog.EntryLocation loc : store().read(conversationId)) {
      out.add(loc.getEntry());
    }
    return out;
  }

  // ── 用户消息在构造时就落盘 ────────────────────────────────────────────

  /**
   * 用户消息在**构造时**落盘（而非运行结束时）。
   *
   * <p>这条决定了「进程被杀后用户输入是否还在」——落盘晚于运行会让一条输入了但
   * 还没轮到的消息直接丢失。
   */
  @Test
  public void userRequestIsPersistedOnConstruction() throws IOException {
    String id = newConversation();
    listener(id, "帮我看下这个 bug");

    List<ConversationEntry> all = entries(id);
    // create 写了 session-meta + custom-title，构造监听器再写一条 user
    assertTrue(all.stream().anyMatch(e -> e instanceof UserMessageEntry));
    UserMessageEntry user =
        (UserMessageEntry)
            all.stream().filter(e -> e instanceof UserMessageEntry).findFirst().orElseThrow();
    assertEquals("帮我看下这个 bug", user.getContent());
    assertFalse("真实用户消息不该被标成 meta", user.isMeta());
  }

  // ── 助手轮次 ──────────────────────────────────────────────────────────

  /** 一轮结束时正文与推理一并落盘，且推理经 delta 累积而来。 */
  @Test
  public void turnFinishedWritesTextAndAccumulatedReasoning() throws IOException {
    String id = newConversation();
    ConversationPersistingListener l = listener(id, "问题");

    l.onEvent(AgentEvent.reasoningDelta("先想"));
    l.onEvent(AgentEvent.reasoningDelta("再想"));
    l.onEvent(AgentEvent.turnFinished("答案", Collections.emptyList()));

    AssistantMessageEntry assistant =
        (AssistantMessageEntry)
            entries(id).stream()
                .filter(e -> e instanceof AssistantMessageEntry)
                .findFirst()
                .orElseThrow();
    assertEquals("答案", assistant.getContent());
    assertEquals("推理要经 delta 累积后一次写入", "先想再想", assistant.getReasoningContent());
  }

  /** TEXT_DELTA 只累积、不落盘（增量太碎）；正文只在轮末写一次。 */
  @Test
  public void textDeltasAreNotPersistedIndividually() throws IOException {
    String id = newConversation();
    ConversationPersistingListener l = listener(id, "问题");

    l.onEvent(AgentEvent.textDelta("一"));
    l.onEvent(AgentEvent.textDelta("二"));
    l.onEvent(AgentEvent.textDelta("三"));

    assertEquals(
        "增量期间不应有任何助手条目落盘", 0, entries(id).stream()
            .filter(e -> e instanceof AssistantMessageEntry)
            .count());

    // 实时态里有累积值，供视图重建时接回（不落盘）。
    assertEquals("一二三", l.snapshot().text);
  }

  /** 轮末清空实时态缓冲：不清的话快照会与磁盘历史重复渲染（气泡出现两遍）。 */
  @Test
  public void buffersAreClearedAfterTurnFinished() throws IOException {
    String id = newConversation();
    ConversationPersistingListener l = listener(id, "问题");

    l.onEvent(AgentEvent.textDelta("正文"));
    l.onEvent(AgentEvent.reasoningDelta("推理"));
    l.onEvent(AgentEvent.turnFinished("正文", Collections.emptyList()));

    assertEquals("", l.snapshot().text);
    assertEquals("", l.snapshot().reasoning);
  }

  // ── 工具结果与步骤分桶 ────────────────────────────────────────────────

  /**
   * 步骤行按调用 id 分桶：并行子代理的进度不能混进别人的卡片。
   *
   * <p>这条是关键回归——共享一个步骤列表时，第二个代理的进度会显示在第一个工具下面，
   * 且另一张卡片永远停在旧轮次。
   */
  @Test
  public void stepLinesAreSplitByToolCallId() throws IOException {
    String id = newConversation();
    ConversationPersistingListener l = listener(id, "问题");

    ToolCall a = new ToolCall("call-a", "agent", "{}");
    ToolCall b = new ToolCall("call-b", "agent", "{}");

    l.onEvent(AgentEvent.toolStarted(a));
    l.onEvent(AgentEvent.toolStarted(b));
    // 交错到达
    l.onEvent(progressEvent("步骤 a1", "call-a"));
    l.onEvent(progressEvent("步骤 b1", "call-b"));
    l.onEvent(progressEvent("步骤 a2", "call-a"));

    l.onEvent(AgentEvent.toolFinished(a, ToolResult.of("call-a", "agent", "结论 A", false)));
    l.onEvent(AgentEvent.toolFinished(b, ToolResult.of("call-b", "agent", "结论 B", false)));

    List<ToolResultEntry> results = new ArrayList<>();
    for (ConversationEntry e : entries(id)) {
      if (e instanceof ToolResultEntry) {
        results.add((ToolResultEntry) e);
      }
    }
    assertEquals(2, results.size());

    ToolResultEntry ra = results.get(0);
    ToolResultEntry rb = results.get(1);
    assertEquals("call-a 只应拿到自己的两条步骤", 2, ra.getSteps().size());
    assertTrue(ra.getSteps().contains("步骤 a1"));
    assertTrue(ra.getSteps().contains("步骤 a2"));
    assertFalse("a 的步骤里不能混进 b 的", ra.getSteps().contains("步骤 b1"));

    assertEquals("call-b 只应拿到自己的一条步骤", 1, rb.getSteps().size());
    assertEquals("步骤 b1", rb.getSteps().get(0));
  }

  /** 在途工具调用被快照带出，供视图重建时接回卡片。 */
  @Test
  public void pendingToolCallsAppearInSnapshot() throws IOException {
    String id = newConversation();
    ConversationPersistingListener l = listener(id, "问题");

    ToolCall a = new ToolCall("call-a", "agent", "{}");
    l.onEvent(AgentEvent.toolStarted(a));
    assertEquals(1, l.snapshot().pendingToolCalls.size());

    l.onEvent(AgentEvent.toolFinished(a, ToolResult.of("call-a", "agent", "ok", false)));
    assertEquals("结束后应从在途表移除", 0, l.snapshot().pendingToolCalls.size());
  }

  // ── 运行中注入 / 跨会话消息 ───────────────────────────────────────────

  /**
   * USER_MESSAGE 事件落盘为普通用户消息。
   *
   * <p>跨会话 send_message 到达的消息正是走这条路径进入收件方历史——不落盘的话，
   * 消息只活在内存里，收件方会话回放时看不到它，历史出现断层。
   */
  @Test
  public void injectedUserMessageIsPersisted() throws IOException {
    String id = newConversation();
    ConversationPersistingListener l = listener(id, "原始问题");

    l.onEvent(AgentEvent.userMessage("[来自另一个会话「甲」的消息]\n看看这个"));

    List<UserMessageEntry> users = new ArrayList<>();
    for (ConversationEntry e : entries(id)) {
      if (e instanceof UserMessageEntry) {
        users.add((UserMessageEntry) e);
      }
    }
    assertEquals("原始问题 + 注入消息", 2, users.size());
    assertEquals("看看这个", users.get(1).getContent().lines().reduce((x, y) -> y).orElse(""));
    assertFalse("注入消息不该被标成 meta（它是一个可见的用户消息）", users.get(1).isMeta());
  }

  // ── 下游转发与降级 ────────────────────────────────────────────────────

  /** 下游监听器收到同一事件（主会话靠它广播给各视图）。 */
  @Test
  public void forwardsEventsToDownstream() throws IOException {
    String id = newConversation();
    List<AgentEvent> seen = new ArrayList<>();
    ConversationPersistingListener l =
        new ConversationPersistingListener(
            id, "问题", seen::add, null, store(), new Object());

    l.onEvent(AgentEvent.textDelta("x"));
    l.onEvent(AgentEvent.turnFinished("x", Collections.emptyList()));

    assertEquals("下游应收到全部事件", 2, seen.size());
  }

  /** 空会话 id（落盘不可用时的降级）不抛异常，也不写任何东西。 */
  @Test
  public void emptyConversationIdDegradesSilently() {
    ConversationPersistingListener l = listener("", "问题");
    // 不该抛：没有会话可写时静默跳过，对话本身仍能继续。
    l.onEvent(AgentEvent.turnFinished("正文", Collections.emptyList()));
    assertNotNull(l.snapshot());
  }

  // ── 子代理会话 ────────────────────────────────────────────────────────

  /**
   * 监听器能写进**子代理**的会话，且该会话带父会话归属。
   *
   * <p>这是第 2 期新增能力的直接验证：子代理此前不落盘，现在与主会话共用同一套
   * 监听器，只是会话 meta 里带了 parentId。
   */
  @Test
  public void writesIntoSubAgentConversation() throws IOException {
    ConversationSummary sub =
        store().createSubAgent("调研", "/w", "m", "confirm", "parent-1", "reviewer");
    String id = sub.getId();

    ConversationPersistingListener l = listener(id, "子代理的任务");
    l.onEvent(AgentEvent.turnFinished("子代理的结论", Collections.emptyList()));

    List<ConversationEntry> all = entries(id);
    SessionMetaEntry meta =
        (SessionMetaEntry)
            all.stream().filter(e -> e instanceof SessionMetaEntry).findFirst().orElseThrow();
    assertEquals("parent-1", meta.getParentId());
    assertEquals("reviewer", meta.getSubagentType());

    assertTrue(
        "子代理会话里应有它的结论",
        all.stream()
            .anyMatch(
                e ->
                    e instanceof AssistantMessageEntry
                        && "子代理的结论".equals(((AssistantMessageEntry) e).getContent())));
  }

  /** 造一条带调用 id 的 PROGRESS 事件。 */
  private static AgentEvent progressEvent(String message, String callId) {
    return AgentEvent.progress(callId, message);
  }

  // ── 延迟提交（拒绝恢复）按 attempt 入队与提交 ─────────────────────────

  /**
   * 关键回归：defer 的轮次**按事件顺序入队**，attempt 收尾按判定提交——
   * 中间的工具调用轮不再被后续轮覆盖，带 toolCalls 的 assistant 与带图 tool_result
   * 都能落盘，且 assistant 在 tool_result 之前（fold 才能正确配对）。
   */
  @Test
  public void deferredTurnsAreQueuedAndCommittedWithToolCalls() throws IOException {
    String id = newConversation();
    ConversationPersistingListener l = listener(id, "问题");
    l.setDeferAssistantPersistence(true);

    // 第一轮：assistant 带 toolCalls + 工具结果带图
    ToolCall call = new ToolCall("call-1", "file_read", "{\"file_path\":\"a.jpg\"}");
    l.onEvent(AgentEvent.turnFinished("先读图", java.util.Collections.singletonList(call)));
    l.onEvent(
        AgentEvent.toolFinished(
            call,
            ToolResult.withImage("file_read", "图来了", "image/jpeg", "QUJD")
                .withCall(call.getId(), call.getName())));

    // 第二轮：又一个 assistant 带 toolCalls（旧实现会被它覆盖）
    ToolCall call2 = new ToolCall("call-2", "file_read", "{\"file_path\":\"b.jpg\"}");
    l.onEvent(AgentEvent.turnFinished("再读一张", java.util.Collections.singletonList(call2)));
    l.onEvent(
        AgentEvent.toolFinished(
            call2,
            ToolResult.withImage("file_read", "第二张", "image/jpeg", "REJT")
                .withCall(call2.getId(), call2.getName())));

    // defer 入队期间：assistant 与 tool_result 都不该提前落盘
    assertEquals(
        "defer 入队期间不该有 assistant 落盘",
        0,
        entries(id).stream().filter(e -> e instanceof AssistantMessageEntry).count());
    assertEquals(
        "defer 入队期间不该有 tool_result 落盘",
        0,
        entries(id).stream().filter(e -> e instanceof ToolResultEntry).count());

    // attempt 收尾：非拒绝 → 全部按事件顺序落盘（带 toolCalls + 图片）
    l.onAttemptFinished(false);

    List<AssistantMessageEntry> assistants = new ArrayList<>();
    List<ToolResultEntry> results = new ArrayList<>();
    for (ConversationEntry e : entries(id)) {
      if (e instanceof AssistantMessageEntry) {
        assistants.add((AssistantMessageEntry) e);
      } else if (e instanceof ToolResultEntry) {
        results.add((ToolResultEntry) e);
      }
    }

    assertEquals("两轮 assistant 都应保留（不再被覆盖）", 2, assistants.size());
    assertEquals("先读图", assistants.get(0).getContent());
    assertEquals("再读一张", assistants.get(1).getContent());
    assertEquals("toolCalls 必须保留", 1, assistants.get(0).getToolCalls().size());
    assertEquals("call-1", assistants.get(0).getToolCalls().get(0).getId());
    assertEquals("call-2", assistants.get(1).getToolCalls().get(0).getId());

    assertEquals("两个 tool_result 都应保留", 2, results.size());
    assertTrue("图片负载必须随 tool_result 落盘", results.get(0).hasImage());

    // 顺序：assistant 必须早于它配对的 tool_result（fold 依赖这个顺序）
    int firstAssistant = -1;
    int firstToolResult = -1;
    List<ConversationEntry> all = entries(id);
    for (int i = 0; i < all.size(); i++) {
      ConversationEntry e = all.get(i);
      if (firstAssistant < 0 && e instanceof AssistantMessageEntry) {
        firstAssistant = i;
      }
      if (firstToolResult < 0 && e instanceof ToolResultEntry) {
        firstToolResult = i;
      }
    }
    assertTrue(
        "assistant 必须先于 tool_result（否则 fold 会把 tool_result 当孤儿丢弃）",
        firstAssistant >= 0 && firstToolResult >= 0 && firstAssistant < firstToolResult);

    // fold 验证：assistant 的 toolCalls 与 tool_result 的图片都必须恢复
    java.util.List<com.tom.rv2ide.ai.protocol.ModelMessage> folded =
        ConversationHistory.fold(store().read(id));
    int assistantWithCalls = 0;
    int toolImages = 0;
    for (com.tom.rv2ide.ai.protocol.ModelMessage m : folded) {
      if ("assistant".equals(m.getRole()) && !m.getToolCalls().isEmpty()) {
        assistantWithCalls++;
      }
      if (m instanceof com.tom.rv2ide.ai.protocol.ToolModelMessage) {
        String raw = m.getRawInputJson();
        if (raw != null && !raw.isEmpty()) {
          toolImages++;
        }
      }
    }
    assertEquals("fold 后两轮 assistant 的 toolCalls 都应保留", 2, assistantWithCalls);
    assertEquals("fold 后两张图片都应水合", 2, toolImages);
  }

  /** 被拒的 attempt：整批入队轮次被丢弃，不落盘。 */
  @Test
  public void refusedAttemptIsDiscarded() throws IOException {
    String id = newConversation();
    ConversationPersistingListener l = listener(id, "问题");
    l.setDeferAssistantPersistence(true);

    l.onEvent(
        AgentEvent.turnFinished(
            "被拒的回复", java.util.Collections.singletonList(new ToolCall("c1", "file_read", "{}"))));
    l.onAttemptFinished(true);

    assertEquals(
        "被拒的轮次不应落盘（拒绝原文不进模型上下文）",
        0,
        entries(id).stream().filter(e -> e instanceof AssistantMessageEntry).count());
  }

  /** 第一次被拒、第二次成功：第一次的轮次被丢弃，第二次的轮次保留。 */
  @Test
  public void refusedAttemptDoesNotLeakIntoNextAttempt() throws IOException {
    String id = newConversation();
    ConversationPersistingListener l = listener(id, "问题");
    l.setDeferAssistantPersistence(true);

    l.onEvent(
        AgentEvent.turnFinished(
            "第一次被拒", java.util.Collections.singletonList(new ToolCall("c1", "file_read", "{}"))));
    l.onAttemptFinished(true);

    l.onEvent(
        AgentEvent.turnFinished(
            "第二次成功", java.util.Collections.singletonList(new ToolCall("c2", "file_read", "{}"))));
    l.onAttemptFinished(false);

    List<AssistantMessageEntry> assistants = new ArrayList<>();
    for (ConversationEntry e : entries(id)) {
      if (e instanceof AssistantMessageEntry) {
        assistants.add((AssistantMessageEntry) e);
      }
    }
    assertEquals("只有第二次的轮次落盘", 1, assistants.size());
    assertEquals("第二次成功", assistants.get(0).getContent());
  }
}

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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.agent.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.protocol.ModelMessage;
import com.tom.rv2ide.ai.tool.api.ToolCall;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ConversationHistory#toDisplayMessages}（界面回放）与 {@link ConversationHistory#fold}
 * （模型清洗）的**行为差异**：回放必须保留完整历史，fold 的清洗不能泄漏到 UI。
 *
 * <p>这是「切换会话后历史丢失」回归的锁：fold 会丢弃的条目，回放必须全部显示。
 */
final class ConversationDisplayMessagesTest {

  private static final long T0 = 1_700_000_000_000L;

  private static ConversationLog logAt(Path dir) {
    return new ConversationLog(dir.resolve("c1.jsonl").toFile());
  }

  @Test
  void displayKeepsRefusedMessagesThatFoldDrops(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir);
    log.append(UserMessageEntry.create(null, T0, "问"));
    log.append(AssistantMessageEntry.createRefused(null, T0 + 1, "我不能帮你", "", List.of()));

    // fold 丢弃被拒回复（只剩 user）；回放必须显示它。
    assertEquals(1, ConversationHistory.fold(log.readAll()).size(), "fold 应丢弃被拒回复");
    List<ModelMessage> display = ConversationHistory.toDisplayMessages(log.readAll());
    assertEquals(2, display.size(), "回放必须保留被拒回复");
    assertEquals("我不能帮你", display.get(1).getContent());
    assertEquals("assistant", display.get(1).getRole());
  }

  @Test
  void displayKeepsEntriesCoveredByCompaction(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir);
    log.append(UserMessageEntry.create(null, T0, "早期问题"));
    log.append(AssistantMessageEntry.create(null, T0 + 1, "早期回答", "", List.of()));
    // 覆盖到 ordinal 1：早期 user/assistant 被 fold 跳过。
    log.append(CompactionEntry.create(null, T0 + 2, "早期摘要", 1));
    log.append(UserMessageEntry.create(null, T0 + 3, "新问题"));

    List<ModelMessage> display = ConversationHistory.toDisplayMessages(log.readAll());
    assertEquals(3, display.size(), "被压缩覆盖的早期条目在回放里也要显示");
    assertEquals("早期问题", display.get(0).getContent());
    assertEquals("早期回答", display.get(1).getContent());
    assertEquals("新问题", display.get(2).getContent());
  }

  @Test
  void displayKeepsAllImagesWhileFoldTrims(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir);
    // 4 组「调用 + 带图结果」：fold 只留最近 3 张，回放全保留。
    for (int i = 0; i < 4; i++) {
      log.append(
          AssistantMessageEntry.create(
              null, T0 + i * 2, "调用" + i, "", List.of(new ToolCall("c" + i, "vscreen", "{}"))));
      log.append(
          ToolResultEntry.create(
              null, T0 + i * 2 + 1, ToolResult.withImage("vscreen", "图" + i, "image/png", "QUJD")));
    }

    List<ModelMessage> display = ConversationHistory.toDisplayMessages(log.readAll());
    long images =
        display.stream()
            .filter(m -> m.getRawInputJson() != null && !m.getRawInputJson().isEmpty())
            .count();
    assertEquals(4, images, "回放不限制图片数量（fold 才限制最近几张）");
  }

  @Test
  void displayKeepsUnpairedToolResult(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir);
    // 孤儿工具结果（没有配对的助手调用）：fold 会丢弃，回放保留。
    log.append(ToolResultEntry.create(null, T0, ToolResult.of("cX", "file_read", "内容", false)));

    List<ModelMessage> display = ConversationHistory.toDisplayMessages(log.readAll());
    assertEquals(1, display.size(), "无配对的结果在回放里也要显示");
    assertEquals("内容", display.get(0).getContent());
  }

  @Test
  void displaySkipsOnlyMetaAndTitleNotDialog(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir);
    log.append(SessionMetaEntry.create(T0, "/w", "m", "confirm"));
    log.append(TitleEntry.customTitle(null, T0 + 1, "标题"));
    log.append(UserMessageEntry.create(null, T0 + 2, "真实消息"));

    // 元信息/标题不是对话内容，回放也不显示；但对话条目照常。
    List<ModelMessage> display = ConversationHistory.toDisplayMessages(log.readAll());
    assertEquals(1, display.size());
    assertEquals("真实消息", display.get(0).getContent());
    assertTrue(display.get(0).getRole().equals("user"));
  }
}

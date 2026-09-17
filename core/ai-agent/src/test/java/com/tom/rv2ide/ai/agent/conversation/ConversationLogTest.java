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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolCall;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 会话日志的持久化契约。
 *
 * <p>重点覆盖四个针对真实故障的机制：残缺尾部、字节偏移、增量水位线、指纹判变。
 * 这些是 append-only 能安全工作的前提——任一失效都会导致"重启后会话损坏"，
 * 而那类问题在真机上很难复现。
 */
final class ConversationLogTest {

  private static final long T0 = 1_700_000_000_000L;

  private static ConversationLog logAt(Path dir, String name) {
    return new ConversationLog(dir.resolve(name).toFile());
  }

  @Test
  void appendsAndReadsBackInOrder(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir, "c1.jsonl");
    log.append(SessionMetaEntry.create(T0, "/workspace", "agnes-2.5-flash", "confirm"));
    log.append(UserMessageEntry.create(null, T0 + 1, "帮我读一下 build.gradle"));
    log.append(AssistantMessageEntry.create(null, T0 + 2, "好的", "", List.of()));

    List<ConversationLog.EntryLocation> entries = log.readAll();
    assertEquals(3, entries.size());
    assertEquals(ConversationEntry.Type.SESSION_META, entries.get(0).getEntry().getType());
    assertEquals(ConversationEntry.Type.USER, entries.get(1).getEntry().getType());
    assertEquals(ConversationEntry.Type.ASSISTANT, entries.get(2).getEntry().getType());
  }

  @Test
  void roundTripsChineseContentExactly(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir, "c1.jsonl");
    String text = "中文内容，含标点：、。「引号」与 emoji 🙂 以及 \\n 转义";
    log.append(UserMessageEntry.create(null, T0, text));

    UserMessageEntry read =
        assertInstanceOf(UserMessageEntry.class, log.readAll().get(0).getEntry());
    assertEquals(text, read.getContent());
  }

  @Test
  void roundTripsToolCallsAndResults(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir, "c1.jsonl");
    List<ToolCall> calls =
        Arrays.asList(
            new ToolCall("call_1", "file_read", "{\"path\":\"a.txt\"}"),
            new ToolCall("call_2", "shell_execute", "{\"command\":\"ls -la\"}"));
    log.append(AssistantMessageEntry.create(null, T0, "我来看看", "思考中", calls));
    log.append(
        ToolResultEntry.create(
            null,
            T0 + 1,
            ToolResult.withReview(
                "call_1", "file_read", "文件内容", false, "diff_9", "pending", "待审查")));

    List<ConversationLog.EntryLocation> entries = log.readAll();
    AssistantMessageEntry assistant =
        assertInstanceOf(AssistantMessageEntry.class, entries.get(0).getEntry());
    assertEquals(2, assistant.getToolCalls().size());
    assertEquals("file_read", assistant.getToolCalls().get(0).getName());
    assertEquals("{\"path\":\"a.txt\"}", assistant.getToolCalls().get(0).getArguments());
    assertEquals("思考中", assistant.getReasoningContent());

    ToolResultEntry result =
        assertInstanceOf(ToolResultEntry.class, entries.get(1).getEntry());
    assertEquals("call_1", result.getToolCallId());
    assertEquals("diff_9", result.getDiffId());
    assertEquals("pending", result.getReviewState());
    assertEquals("待审查", result.getReviewMessage());
    assertFalse(result.isError());
  }

  @Test
  void truncatesIncompleteTrailingLine(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir, "c1.jsonl");
    log.append(UserMessageEntry.create(null, T0, "第一条"));

    // 模拟写入中途崩溃：追加一段没有换行结尾的半行 JSON
    File file = log.getFile();
    try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
      raf.seek(raf.length());
      raf.write("{\"type\":\"user\",\"uuid\":\"half".getBytes(StandardCharsets.UTF_8));
    }

    ConversationLog.ReadResult result = log.readFrom(0);
    // 半行不能被解析成条目，否则会得到一条内容残缺的消息
    assertEquals(1, result.getEntries().size());
    assertTrue(result.getPendingTailBytes() > 0, "应报告残缺尾部字节数");
  }

  @Test
  void appendAfterCrashKeepsBothEntriesReadable(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir, "c1.jsonl");
    log.append(UserMessageEntry.create(null, T0, "崩溃前"));

    File file = log.getFile();
    try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
      raf.seek(raf.length());
      raf.write("{\"type\":\"user\",\"uuid\":\"crash".getBytes(StandardCharsets.UTF_8));
    }

    // 崩溃后继续追加：若不补换行，新条目会与半行粘成一行，两条都读不出来
    log.append(UserMessageEntry.create(null, T0 + 1, "崩溃后"));

    List<ConversationLog.EntryLocation> entries = log.readAll();
    assertEquals(2, entries.size(), "崩溃前与崩溃后的条目都应可读");
    assertEquals(
        "崩溃前", assertInstanceOf(UserMessageEntry.class, entries.get(0).getEntry()).getContent());
    assertEquals(
        "崩溃后", assertInstanceOf(UserMessageEntry.class, entries.get(1).getEntry()).getContent());
  }

  @Test
  void reportsByteOffsetsMatchingFilePositions(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir, "c1.jsonl");
    long firstOffset = log.append(UserMessageEntry.create(null, T0, "abc"));
    long secondOffset = log.append(UserMessageEntry.create(null, T0 + 1, "def"));

    assertEquals(0L, firstOffset);
    assertTrue(secondOffset > 0);

    // 按偏移直接读第二条，跳过第一条
    ConversationLog.ReadResult result = log.readFrom(secondOffset);
    assertEquals(1, result.getEntries().size());
    assertEquals(
        "def", assertInstanceOf(UserMessageEntry.class, result.getEntries().get(0).getEntry()).getContent());
  }

  @Test
  void incrementalReadOnlyReturnsNewEntries(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir, "c1.jsonl");
    log.append(UserMessageEntry.create(null, T0, "一"));
    log.append(UserMessageEntry.create(null, T0 + 1, "二"));

    // 第一次读到水位线
    ConversationLog.ReadResult first = log.readFrom(0);
    assertEquals(2, first.getEntries().size());
    long watermark = first.getNextOffset();

    // 增量：只读新增部分
    log.append(UserMessageEntry.create(null, T0 + 2, "三"));
    ConversationLog.ReadResult second = log.readFrom(watermark);
    assertEquals(1, second.getEntries().size(), "增量读只应返回新增条目");
    assertEquals(
        "三", assertInstanceOf(UserMessageEntry.class, second.getEntries().get(0).getEntry()).getContent());
  }

  @Test
  void fingerprintChangesWhenIndexedRegionIsRewritten(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir, "c1.jsonl");
    log.append(UserMessageEntry.create(null, T0, "原始内容"));
    long watermark = log.length();
    String before = log.fingerprint(watermark);
    assertFalse(before.isEmpty());

    // 追加：已索引区间的指纹不变，水位线仍有效
    log.append(UserMessageEntry.create(null, T0 + 1, "追加内容"));
    assertEquals(before, log.fingerprint(watermark), "纯追加不应改变已索引区间的指纹");

    // 重写已索引区间：指纹必须变，否则会误信旧水位线而漏掉被改内容
    File file = log.getFile();
    Files.write(file.toPath(), "完全不同的内容\n".getBytes(StandardCharsets.UTF_8));
    String after = log.fingerprint(watermark);
    assertNotEquals(before, after, "重写已索引区间必须被指纹检出");
  }

  @Test
  void readFromBeyondLengthIsReportedAsReset(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir, "c1.jsonl");
    log.append(UserMessageEntry.create(null, T0, "内容"));

    // 偏移超出文件长度 = 文件被截断。应报告 nextOffset=0 让调用方整体重建，
    // 而不是返回空列表让调用方以为"没有新内容"。
    ConversationLog.ReadResult result = log.readFrom(999_999);
    assertEquals(0, result.getEntries().size());
    assertEquals(0L, result.getNextOffset());
  }

  @Test
  void skipsMalformedAndUnknownLines(@TempDir Path dir) throws IOException {
    File file = dir.resolve("c1.jsonl").toFile();
    String content =
        "这不是 JSON\n"
            + "{\"type\":\"user\",\"uuid\":\"u1\",\"timestamp\":1,\"content\":\"有效\"}\n"
            + "{\"type\":\"future-type\",\"uuid\":\"u2\",\"timestamp\":2}\n"
            + "{\"noType\":true}\n"
            + "{\"type\":\"user\",\"uuid\":\"u3\",\"timestamp\":3,\"content\":\"也有效\"}\n";
    Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));

    List<ConversationLog.EntryLocation> entries = new ConversationLog(file).readAll();
    // 坏行与未知类型被跳过，有效行照常读出——外部编辑不该让整个会话不可读
    assertEquals(2, entries.size());
    assertEquals(
        "有效", assertInstanceOf(UserMessageEntry.class, entries.get(0).getEntry()).getContent());
    assertEquals(
        "也有效", assertInstanceOf(UserMessageEntry.class, entries.get(1).getEntry()).getContent());
  }

  @Test
  void titleEntriesPreserveBothKinds(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir, "c1.jsonl");
    log.append(UserMessageEntry.create(null, T0, "第一条用户消息"));
    log.append(TitleEntry.aiTitle(null, T0 + 1, "模型生成的标题"));
    log.append(TitleEntry.customTitle(null, T0 + 2, "用户改的标题"));

    List<ConversationLog.EntryLocation> entries = log.readAll();
    assertEquals(
        ConversationEntry.Type.AI_TITLE, entries.get(1).getEntry().getType());
    assertEquals(
        ConversationEntry.Type.CUSTOM_TITLE, entries.get(2).getEntry().getType());
    // 重命名是追加，不是改写：两条标题都在，历史可追溯
    assertEquals(
        "用户改的标题", assertInstanceOf(TitleEntry.class, entries.get(2).getEntry()).getTitle());
  }

  @Test
  void compactionEntryRoundTrips(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir, "c1.jsonl");
    log.append(UserMessageEntry.create(null, T0, "很长的历史"));
    log.append(CompactionEntry.create(null, T0 + 1, "历史摘要", 12));

    CompactionEntry read =
        assertInstanceOf(CompactionEntry.class, log.readAll().get(1).getEntry());
    assertEquals("历史摘要", read.getSummary());
    assertEquals(12, read.getUpToOrdinal());
  }

  @Test
  void metaFlagIsPreserved(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir, "c1.jsonl");
    log.append(new UserMessageEntry(null, null, T0, "系统注入", true));

    UserMessageEntry read =
        assertInstanceOf(UserMessageEntry.class, log.readAll().get(0).getEntry());
    assertTrue(read.isMeta());
  }

  @Test
  void missingFileReadsAsEmpty(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir, "不存在.jsonl");
    assertFalse(log.exists());
    assertEquals(0, log.readAll().size());
    assertEquals(0L, log.length());
  }

  @Test
  void deleteRemovesFile(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir, "c1.jsonl");
    log.append(UserMessageEntry.create(null, T0, "内容"));
    assertTrue(log.exists());

    assertTrue(log.delete());
    assertFalse(log.exists());
  }

  @Test
  void parsesEntryWithoutParentUuid(@TempDir Path dir) throws IOException {
    // 会话首条没有 parentUuid，字段应被省略而不是写成空串
    ConversationLog log = logAt(dir, "c1.jsonl");
    log.append(UserMessageEntry.create(null, T0, "首条"));

    String raw = new String(Files.readAllBytes(log.getFile().toPath()), StandardCharsets.UTF_8);
    assertFalse(raw.contains("\"parentUuid\""), "空 parentUuid 不应写入文件");

    ConversationEntry entry = log.readAll().get(0).getEntry();
    assertEquals("", entry.getParentUuid());
    assertNotNull(entry.getUuid());
    assertFalse(entry.getUuid().isEmpty());
  }

  @Test
  void codecReturnsNullForUnparsableInput() {
    assertNull(ConversationCodec.parse(null));
    assertNull(ConversationCodec.parse(""));
    assertNull(ConversationCodec.parse("   "));
    assertNull(ConversationCodec.parse("{ 不是合法 JSON"));
    assertNull(ConversationCodec.parse("{\"type\":\"unknown\",\"uuid\":\"x\"}"));
  }
}

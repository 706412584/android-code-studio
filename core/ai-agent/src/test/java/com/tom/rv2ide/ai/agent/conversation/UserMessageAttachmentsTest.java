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

package com.tom.rv2ide.ai.agent.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.protocol.AttachmentMeta;
import com.tom.rv2ide.ai.protocol.ModelMessage;
import com.tom.rv2ide.ai.protocol.UserModelMessage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 用户消息的附件元数据：落盘、回读、回放。
 *
 * <p>背景：用户附 3 张图时，气泡里一张缩略图都没有，只看到正文里的路径文本。
 * 修好发送链路后，附件元数据还必须能跨进程存活——否则重启应用回看会话，
 * 缩略图又全没了。
 *
 * <p>这里锁定三条：元数据完整往返、旧日志（无该字段）仍可读、fold 后能带到
 * {@link ModelMessage} 供 UI 回放。
 */
final class UserMessageAttachmentsTest {

  private static final long T0 = 1_700_000_000_000L;

  private static ConversationLog logAt(Path dir, String name) {
    return new ConversationLog(dir.resolve(name).toFile());
  }

  private static List<AttachmentMeta> threeAttachments() {
    return List.of(
        new AttachmentMeta("a.png", "/files/ai/attachments/a.png", "image/png", 1024L, true),
        new AttachmentMeta("b.jpg", "/files/ai/attachments/b.jpg", "image/jpeg", 2048L, true),
        new AttachmentMeta("c.txt", "/files/ai/attachments/c.txt", "text/plain", 12L, false));
  }

  @Test
  void attachmentMetadataRoundTripsThroughLog(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir, "c1.jsonl");
    log.append(
        UserMessageEntry.create(null, T0, "看看这三张", threeAttachments()));

    UserMessageEntry read =
        assertInstanceOf(UserMessageEntry.class, log.readAll().get(0).getEntry());
    List<AttachmentMeta> attachments = read.getAttachments();
    assertEquals(3, attachments.size());
    assertEquals("a.png", attachments.get(0).getFileName());
    assertEquals("/files/ai/attachments/b.jpg", attachments.get(1).getLocalPath());
    assertEquals("image/jpeg", attachments.get(1).getMimeType());
    assertEquals(2048L, attachments.get(1).getSizeBytes());
    assertTrue(attachments.get(2).isImage() == false, "非图片附件的 isImage 必须为 false");
  }

  /**
   * 旧日志兼容：没有 {@code attachments} 字段的行必须照常解析成空列表。
   *
   * <p>会话日志是 append-only 的，用户机器上已经躺着一批修复前的日志。读不了它们
   * 等于把用户的全部历史弄丢。
   */
  @Test
  void legacyEntryWithoutAttachmentsStillParses(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir, "c1.jsonl");
    log.append(UserMessageEntry.create(null, T0, "老消息，没有附件"));

    UserMessageEntry read =
        assertInstanceOf(UserMessageEntry.class, log.readAll().get(0).getEntry());
    assertEquals("老消息，没有附件", read.getContent());
    assertTrue(read.getAttachments().isEmpty());
  }

  /** 无附件时不该写出空数组：日志行保持与旧版本逐字节一致，便于回滚。 */
  @Test
  void entryWithoutAttachmentsOmitsTheField(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir, "c1.jsonl");
    log.append(UserMessageEntry.create(null, T0, "纯文本"));
    String line = log.readAll().isEmpty() ? "" : new String(
        java.nio.file.Files.readAllBytes(dir.resolve("c1.jsonl")),
        java.nio.charset.StandardCharsets.UTF_8);
    assertFalse(line.contains("\"attachments\""), line);
  }

  /** fold 之后附件必须跟着 ModelMessage 走，否则 UI 回放拿不到。 */
  @Test
  void foldCarriesAttachmentsToModelMessage(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir, "c1.jsonl");
    log.append(UserMessageEntry.create(null, T0, "看看这三张", threeAttachments()));

    List<ModelMessage> messages = ConversationHistory.fold(log.readAll());
    ModelMessage first = messages.get(0);
    assertInstanceOf(UserModelMessage.class, first);
    assertEquals(3, first.getAttachments().size());
    assertEquals("a.png", first.getAttachments().get(0).getFileName());
  }

  /** 协议序列化不读附件元数据：带附件的消息正文仍是用户输入本身。 */
  @Test
  void attachmentsDoNotAlterMessageContent(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir, "c1.jsonl");
    log.append(UserMessageEntry.create(null, T0, "看看这三张", threeAttachments()));

    List<ModelMessage> messages = ConversationHistory.fold(log.readAll());
    assertEquals("看看这三张", messages.get(0).getContent());
  }
}

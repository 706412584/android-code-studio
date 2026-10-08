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

package com.tom.rv2ide.artificial.agent.compose;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.tom.rv2ide.artificial.agent.compose.components.independent.ChatAttachmentUtilsKt;
import com.tom.rv2ide.artificial.agent.compose.components.independent.PastedTextBufferKt;
import com.tom.rv2ide.artificial.agent.compose.compat.PendingUploadAttachment;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/**
 * ChatAttachmentUtils 纯逻辑部分的回归测试（移植自 Aharou）。
 *
 * <p>不覆盖 Android 框架路径（Uri / ContentResolver / ImageCompressor 位图编码），
 * 那些依赖 instrumentation；这里锁的是拼装与阈值判定逻辑。
 */
public class ChatAttachmentUtilsTest {

  @Test
  public void formatBytes_matchesAharouTiers() {
    // 与 Aharou 同档位：MB 保留一位小数，KB 取整，B 原样
    assertEquals("1.5 MB", ChatAttachmentUtilsKt.formatBytes(1_572_864L));
    assertEquals("1023 KB", ChatAttachmentUtilsKt.formatBytes(1_047_552L));
    assertEquals("1023 B", ChatAttachmentUtilsKt.formatBytes(1023L));
    assertEquals("42 B", ChatAttachmentUtilsKt.formatBytes(42L));
  }

  // appendAttachmentsToRequest 的两个拼装分支依赖 android.content.Context 取字符串资源，
  // JVM 单测不可用（android.jar 未 mock）；空列表短路同样发生在非空校验之后。
  // 资源拼装语义由真机验证覆盖，这里只锁纯函数。

  @Test
  public void selectedAttachments_emptyRequestYieldsEmpty() {
    // Uri.parse 在 JVM 单测未被 mock（android.jar 桩）；selectedAttachments 只做
    // take(n) 的数量裁剪，空入参锁语义，真实多选裁剪走真机验证。
    assertTrue(ChatAttachmentUtilsKt.selectedAttachments(Collections.emptyList(), 0).isEmpty());
  }

  @Test
  public void hasAttachmentSlots_boundary() {
    assertTrue(ChatAttachmentUtilsKt.hasAttachmentSlots(7));
    assertFalse(ChatAttachmentUtilsKt.hasAttachmentSlots(8));
    assertFalse(ChatAttachmentUtilsKt.hasAttachmentSlots(9));
  }

  @Test
  public void isLongPastedText_englishByWordCount_chineseByCharCount() {
    // 英文为主：1000 个词以内不算长
    StringBuilder englishShort = new StringBuilder();
    for (int i = 0; i < 900; i++) englishShort.append("word ");
    assertFalse(PastedTextBufferKt.isLongPastedText(englishShort.toString()));

    StringBuilder englishLong = new StringBuilder();
    for (int i = 0; i < 1100; i++) englishLong.append("word ");
    assertTrue(PastedTextBufferKt.isLongPastedText(englishLong.toString()));

    // 中日韩：1200 字符阈值
    StringBuilder chinese = new StringBuilder();
    for (int i = 0; i < 1300; i++) chinese.append('字');
    assertTrue(PastedTextBufferKt.isLongPastedText(chinese.toString()));

    StringBuilder chineseShort = new StringBuilder();
    for (int i = 0; i < 1100; i++) chineseShort.append('字');
    assertFalse(PastedTextBufferKt.isLongPastedText(chineseShort.toString()));
  }

  @Test
  public void shouldPasteAsFile_uses15000Threshold() {
    assertFalse(PastedTextBufferKt.shouldPasteAsFile(repeat("x", 15_000)));
    assertTrue(PastedTextBufferKt.shouldPasteAsFile(repeat("x", 15_001)));
  }

  private static String repeat(String s, int n) {
    StringBuilder sb = new StringBuilder(n * s.length());
    for (int i = 0; i < n; i++) sb.append(s);
    return sb.toString();
  }
}

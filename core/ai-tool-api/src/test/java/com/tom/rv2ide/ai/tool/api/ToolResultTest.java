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

package com.tom.rv2ide.ai.tool.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 钉住 {@link ToolResult} 的工厂方法、不可变 copy-on-write 语义、以及超长内容中间截断
 * （这是避免把几十 MB 工具输出塞进模型上下文的关键行为）。
 */
public final class ToolResultTest {

  @Test
  public void successCarriesContentAndNoError() {
    ToolResult result = ToolResult.success("ok");
    assertEquals("ok", result.getContent());
    assertFalse(result.isError());
    assertEquals("", result.getToolName());
    assertEquals("", result.getToolCallId());
  }

  @Test
  public void errorCarriesMessageAndErrorFlag() {
    ToolResult result = ToolResult.error("boom");
    assertEquals("boom", result.getContent());
    assertTrue(result.isError());
  }

  @Test
  public void ofKeepsCallIdentity() {
    ToolResult result = ToolResult.of("c1", "file_read", "body", false);
    assertEquals("c1", result.getToolCallId());
    assertEquals("file_read", result.getToolName());
    assertEquals("body", result.getContent());
    assertEquals("", result.getDiffId());
    assertEquals("", result.getReviewState());
  }

  @Test
  public void nullFieldsAreNormalizedToEmptyStrings() {
    ToolResult result = new ToolResult(null, null, null, false, null, null, null);
    assertEquals("", result.getToolCallId());
    assertEquals("", result.getToolName());
    assertEquals("", result.getContent());
    assertEquals("", result.getDiffId());
    assertEquals("", result.getReviewState());
    assertEquals("", result.getReviewMessage());
    assertEquals("", result.getImageBase64());
    assertEquals("", result.getImageMimeType());
  }

  @Test
  public void withImageExposesPayloadAndMimeType() {
    ToolResult result = ToolResult.withImage("file_read", "look", "image/png", "AAAA");
    assertEquals("image/png", result.getImageMimeType());
    assertEquals("AAAA", result.getImageBase64());
    assertTrue(result.hasImage());
    assertFalse(result.isError());
    assertEquals("look", result.getContent());
  }

  @Test
  public void resultWithoutImageReportsNoImage() {
    assertFalse(ToolResult.success("x").hasImage());
  }

  @Test
  public void withReviewOverloadsPopulateReviewFields() {
    ToolResult a = ToolResult.withReview("out", "c1", "file_edit", "d1", "pending", "review me");
    assertFalse(a.isError());
    assertEquals("d1", a.getDiffId());
    assertEquals("pending", a.getReviewState());
    assertEquals("review me", a.getReviewMessage());

    ToolResult b = ToolResult.withReview("c2", "shell_execute", "err", true, "d2", "rejected", "no");
    assertTrue(b.isError());
    assertEquals("c2", b.getToolCallId());
    assertEquals("shell_execute", b.getToolName());
  }

  @Test
  public void withCallReplacesIdentityButKeepsBodyAndImage() {
    ToolResult original = ToolResult.withImage("file_read", "look", "image/jpeg", "ZZZ");
    ToolResult rebound = original.withCall("c9", "file_read");
    assertEquals("c9", rebound.getToolCallId());
    assertEquals("file_read", rebound.getToolName());
    assertEquals("look", rebound.getContent());
    assertEquals("ZZZ", rebound.getImageBase64());
    // 原对象不被修改
    assertEquals("", original.getToolCallId());
  }

  @Test
  public void withDiffIdReplacesOnlyDiffId() {
    ToolResult original = ToolResult.of("c1", "file_edit", "body", false);
    ToolResult updated = original.withDiffId("diff-7");
    assertEquals("diff-7", updated.getDiffId());
    assertEquals("c1", updated.getToolCallId());
    assertEquals("", original.getDiffId());
  }

  @Test
  public void withReviewReplacesReviewStateOnly() {
    ToolResult updated = ToolResult.success("x").withReview("accepted", "looks good");
    assertEquals("accepted", updated.getReviewState());
    assertEquals("looks good", updated.getReviewMessage());
    assertEquals("x", updated.getContent());
  }

  @Test
  public void truncateContentReturnsNullForNull() {
    assertNull(ToolResult.truncateContent(null));
  }

  @Test
  public void truncateContentLeavesShortContentUntouched() {
    String shortContent = "small";
    assertSame(shortContent, ToolResult.truncateContent(shortContent));
  }

  @Test
  public void truncateContentKeepsExactlyAtLimit() {
    String atLimit = "a".repeat(ToolResult.MAX_TOOL_RESULT_CHARS);
    assertSame(atLimit, ToolResult.truncateContent(atLimit));
  }

  @Test
  public void truncateContentDropsTheMiddleAndReportsCount() {
    String huge = "a".repeat(ToolResult.MAX_TOOL_RESULT_CHARS + 100);
    String truncated = ToolResult.truncateContent(huge);
    int half = ToolResult.MAX_TOOL_RESULT_CHARS / 2;
    assertTrue(truncated.startsWith("a".repeat(half)));
    assertTrue(truncated.endsWith("a".repeat(half)));
    assertTrue(truncated.contains("100 chars truncated"));
    assertTrue(truncated.length() < huge.length());
  }
}

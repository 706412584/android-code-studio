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

package com.tom.rv2ide.artificial.agent.compose;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.tom.rv2ide.artificial.agent.compose.components.independent.ComposerDraft;
import com.tom.rv2ide.artificial.agent.compose.components.independent.ComposerDraftKt;
import com.tom.rv2ide.artificial.agent.compose.components.independent.ComposerReference;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class ComposerDraftTest {

  private static ComposerReference session(String id, String label, int start) {
    return new ComposerReference("session", id, label, "", start, start + label.length() + 1);
  }

  @Test
  public void insertReferenceAtCursorCreatesStructuredRangeAndKeepsLiteralDuplicateDistinct() {
    String text = "@old @thread";
    ComposerReference ref = session("session-1", "thread", 5);

    ComposerDraft result =
        ComposerDraftKt.insertReference(text, Collections.emptyList(), 5, 12, ref);

    assertEquals("@old @thread ", result.getText());
    assertEquals(1, result.getReferences().size());
    assertEquals(5, result.getReferences().get(0).getStart());
    assertEquals("@thread", result.getText().substring(
        result.getReferences().get(0).getStart(), result.getReferences().get(0).getEnd()));
    assertEquals(13, result.getCursor());
  }

  @Test
  public void editBeforeReferenceMovesRangeByUtf16LengthDelta() {
    ComposerDraft initial = new ComposerDraft("A @会话", Collections.singletonList(
        new ComposerReference("session", "id", "会话", "", 2, 5)), 5);

    ComposerDraft edited = ComposerDraftKt.applyTextEdit(initial, "前A @会话");

    assertEquals("前A @会话", edited.getText());
    assertEquals(3, edited.getReferences().get(0).getStart());
    assertEquals(6, edited.getReferences().get(0).getEnd());
  }

  @Test
  public void deletingInsideReferenceRemovesWholeTokenAndIdentity() {
    ComposerDraft initial = new ComposerDraft("问 @会话 后", Collections.singletonList(
        new ComposerReference("session", "id", "会话", "", 2, 5)), 4);

    ComposerDraft edited = ComposerDraftKt.applyTextEdit(initial, "问 @话 后");

    assertEquals("问  后", edited.getText());
    assertTrue(edited.getReferences().isEmpty());
  }

  @Test
  public void replacingReferenceWithTextDoesNotRetainReferenceIdentity() {
    ComposerDraft initial = new ComposerDraft("看 @文档", Collections.singletonList(
        new ComposerReference("file", "/project/README.md", "文档", "/project/README.md", 2, 6)), 6);

    ComposerDraft edited = ComposerDraftKt.applyTextEdit(initial, "看 普通文字");

    assertEquals("看 普通文字", edited.getText());
    assertTrue(edited.getReferences().isEmpty());
  }

  @Test
  public void replacingReferenceKeepsNewTokenAndShiftsFollowingReference() {
    ComposerReference first = session("old-id", "甲", 0);
    ComposerReference following = session("following-id", "后", 3);

    ComposerDraft result = ComposerDraftKt.insertReference("@甲 @后", List.of(first, following),
        0, 2, session("new-id", "新会话", 0));

    assertEquals("@新会话 @后", result.getText());
    List<ComposerReference> live = ComposerDraftKt.liveReferences(result);
    assertEquals(2, live.size());
    assertEquals("new-id", live.get(0).getId());
    assertEquals("following-id", live.get(1).getId());
    assertEquals(5, live.get(1).getStart());
    assertEquals(4, result.getCursor());
  }

  @Test
  public void replacingReferenceWithSameLabelReplacesItsIdentity() {
    ComposerDraft result = ComposerDraftKt.insertReference("@build.gradle.kts ",
        List.of(new ComposerReference("file", "old/build.gradle.kts", "build.gradle.kts",
            "/old/build.gradle.kts", 0, 17)), 0, 17,
        new ComposerReference("file", "new/build.gradle.kts", "build.gradle.kts",
            "/new/build.gradle.kts", 0, 0));

    assertEquals("@build.gradle.kts ", result.getText());
    List<ComposerReference> live = ComposerDraftKt.liveReferences(result);
    assertEquals(1, live.size());
    assertEquals("new/build.gradle.kts", live.get(0).getId());
    assertEquals("/new/build.gradle.kts", live.get(0).getPath());
  }

  @Test
  public void insertingInsideReferenceReplacesWholeIdentity() {
    ComposerDraft result = ComposerDraftKt.insertReference("看 @旧会话 后",
        List.of(session("old-id", "旧会话", 2)), 3, 3, session("new-id", "新", 0));

    assertEquals("看 @新 后", result.getText());
    List<ComposerReference> live = ComposerDraftKt.liveReferences(result);
    assertEquals(1, live.size());
    assertEquals("new-id", live.get(0).getId());
    assertEquals(2, live.get(0).getStart());
    assertEquals(4, result.getCursor());
  }

  @Test
  public void handTypedSameLabelDoesNotAcquireReferenceIdentity() {
    ComposerDraft draft = new ComposerDraft("请看 @会话", Collections.emptyList(), 7);

    assertTrue(ComposerDraftKt.liveReferences(draft).isEmpty());
    assertFalse(ComposerDraftKt.liveReferences(draft).contains("session-id"));
  }

  @Test
  public void staleRangeIsIgnoredInsteadOfBindingToAnotherMatchingToken() {
    ComposerDraft draft = new ComposerDraft("@会话 @会话", Collections.singletonList(
        new ComposerReference("session", "id", "会话", "", 0, 3)), 7);

    List<ComposerReference> live = ComposerDraftKt.liveReferences(draft);

    assertEquals(1, live.size());
    assertEquals(0, live.get(0).getStart());
    assertEquals("id", live.get(0).getId());
  }
}

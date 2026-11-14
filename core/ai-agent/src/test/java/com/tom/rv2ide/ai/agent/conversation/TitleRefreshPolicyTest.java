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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

/** 锁定 {@link TitleRefreshPolicy} 的触发策略——重点是「不锁死」与「用户重命名不覆盖」。 */
public class TitleRefreshPolicyTest {

  private static TitleRefreshPolicy.State state(
      int completedTurns, int lastGeneratedTurn, boolean customTitle, String title, String message) {
    return new TitleRefreshPolicy.State(
        completedTurns, lastGeneratedTurn, customTitle, title, message, new HashSet<>());
  }

  @Test
  public void firstTurnAlwaysGenerates() {
    TitleRefreshPolicy.Decision d =
        TitleRefreshPolicy.evaluate(state(1, 0, false, "", "你好"));
    assertTrue(d.shouldGenerate);
    assertEquals("turn:1", d.key);
  }

  @Test
  public void thirdTurnGeneratesAgain() {
    TitleRefreshPolicy.Decision d =
        TitleRefreshPolicy.evaluate(state(3, 1, false, "旧标题", "继续"));
    assertTrue(d.shouldGenerate);
    assertEquals("turn:3", d.key);
  }

  @Test
  public void secondTurnDoesNotGenerate() {
    // cc-haha 也一样：第 2 轮不生成，避免与首轮紧邻的重复请求。
    TitleRefreshPolicy.Decision d =
        TitleRefreshPolicy.evaluate(state(2, 1, false, "标题", "补充一下"));
    assertFalse(d.shouldGenerate);
  }

  @Test
  public void periodicRefreshEveryEightTurns() {
    TitleRefreshPolicy.Decision d =
        TitleRefreshPolicy.evaluate(state(8, 3, false, "标题", "继续"));
    assertTrue(d.shouldGenerate, "第 8 轮应周期再命名");
    assertEquals("periodic:8", d.key);

    // 第 16 轮同样触发（不锁死）。
    TitleRefreshPolicy.Decision d16 =
        TitleRefreshPolicy.evaluate(state(16, 8, false, "标题", "继续"));
    assertTrue(d16.shouldGenerate);
  }

  @Test
  public void nonPeriodicTurnsDoNotGenerate() {
    for (int turn : new int[] {4, 5, 6, 7, 9, 10, 11}) {
      TitleRefreshPolicy.Decision d =
          TitleRefreshPolicy.evaluate(state(turn, 3, false, "标题", "继续"));
      assertFalse(d.shouldGenerate, "第 " + turn + " 轮不该生成");
    }
  }

  @Test
  public void customTitleBlocksAllTriggers() {
    // 用户重命名后，任何触发点都不能再生成。
    for (int turn : new int[] {1, 3, 8, 16}) {
      TitleRefreshPolicy.Decision d =
          TitleRefreshPolicy.evaluate(state(turn, 0, true, "用户写的", "任意"));
      assertFalse(d.shouldGenerate, "自定义标题时第 " + turn + " 轮不该生成");
    }
  }

  @Test
  public void topicShiftTriggersRefresh() {
    TitleRefreshPolicy.Decision d =
        TitleRefreshPolicy.evaluate(state(5, 1, false, "编译 APK 失败", "帮我写一首关于春天的诗"));
    assertTrue(d.shouldGenerate, "换话题应触发");
    assertEquals("topic:5", d.key);
  }

  @Test
  public void topicShiftRequiresGapFromLastGeneration() {
    // 距上次生成不足 2 轮时不判换话题——避免相邻轮连续触发。
    TitleRefreshPolicy.Decision d =
        TitleRefreshPolicy.evaluate(state(2, 1, false, "编译失败", "写一首诗"));
    assertFalse(d.shouldGenerate);
  }

  @Test
  public void sameTopicDoesNotTrigger() {
    TitleRefreshPolicy.Decision d =
        TitleRefreshPolicy.evaluate(state(5, 1, false, "编译 APK 失败", "编译还是失败，换个方法"));
    assertFalse(d.shouldGenerate, "同话题不该触发");
  }

  @Test
  public void firedKeyPreventsDuplicateGeneration() {
    java.util.Set<String> fired = new HashSet<>();
    fired.add("turn:1");
    TitleRefreshPolicy.State s =
        new TitleRefreshPolicy.State(1, 0, false, "", "你好", fired);
    assertFalse(TitleRefreshPolicy.evaluate(s).shouldGenerate);
  }

  // ---- 换话题判定 ----

  @Test
  public void topicShiftDetectsCompletelyUnrelatedTopics() {
    assertTrue(
        TitleRefreshPolicy.isTopicShift("编译 Android 项目", "给我讲个笑话"));
  }

  @Test
  public void topicShiftRejectsSharedChineseKeywords() {
    assertFalse(
        TitleRefreshPolicy.isTopicShift("编译失败排查", "编译还是报错"),
        "共享「编译」片段即同话题");
  }

  @Test
  public void topicShiftRejectsSharedEnglishKeywords() {
    assertFalse(
        TitleRefreshPolicy.isTopicShift("fix gradle build", "the gradle sync still fails"));
  }

  @Test
  public void topicShiftWithEmptyInputsIsFalse() {
    assertFalse(TitleRefreshPolicy.isTopicShift("", "任意"));
    assertFalse(TitleRefreshPolicy.isTopicShift("标题", ""));
  }
}

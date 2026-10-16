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
 * along with AndroidCodeStudio.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.agent.context.RunContextManager;
import com.tom.rv2ide.ai.agent.context.TokenUsageTracker;
import com.tom.rv2ide.ai.agent.prompt.ChatMode;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 对话模式列举 + 上下文用量回调 + 若干小面积公共入口。
 *
 * <p>用量回调（{@code UsageListener}）此前完全没有测试：它是 UI 上下文圆环的唯一数据源，
 * 而「窗口未配置时不推」这条规则若被破坏，界面会显示一个凭空的占比（分母是 0）。
 */
final class ChatModeAndUsageTest {

  @Test
  void chatModeListIsCompleteAndHasLabels() {
    List<ChatMode> modes = ChatMode.all();
    assertFalse(modes.isEmpty(), "应至少有一种对话模式");
    for (ChatMode mode : modes) {
      assertNotNull(mode.getId());
      assertFalse(mode.getId().isEmpty(), "模式 id 不能为空：" + mode);
      assertNotNull(mode.getLabel(), "模式应有展示名：" + mode);
      assertFalse(mode.getLabel().isEmpty(), "模式展示名不能为空：" + mode);
      assertNotNull(mode.getDescription(), "模式应有说明：" + mode);
    }
    // 默认模式必须在列表里，否则 UI 的初始选项会指向一个不存在的项。
    assertTrue(modes.contains(ChatMode.DEFAULT), "DEFAULT 必须属于 all()");
    assertEquals(modes.size(), ChatMode.allIds().size(), "allIds 应与 all 一一对应");
  }

  @Test
  void usageListenerReceivesTokensOnlyWhenWindowConfigured() {
    List<String> calls = new ArrayList<>();
    // 窗口配置为 1000：裁剪与记录用量时应回调。
    TokenUsageTracker tracker = new TokenUsageTracker(1000);
    RunContextManager manager =
        new RunContextManager(tracker, 100, (used, size) -> calls.add(used + "/" + size));

    manager.fit(List.of(new com.tom.rv2ide.ai.protocol.UserModelMessage("hi")), 0);

    assertFalse(calls.isEmpty(), "配置了窗口时应推送用量");
    assertTrue(calls.get(0).endsWith("/1000"), "推送的窗口大小应为配置值：" + calls.get(0));
  }

  @Test
  void usageListenerIsNotCalledWhenWindowUnset() {
    List<String> calls = new ArrayList<>();
    // 未配置窗口（0）→ 不该推送：没有分母就不该编一个比例出来（分母为 0 的比例是假数据）。
    RunContextManager manager =
        new RunContextManager(new TokenUsageTracker(0), 100, (used, size) -> calls.add("x"));

    manager.fit(List.of(new com.tom.rv2ide.ai.protocol.UserModelMessage("hi")), 0);

    assertTrue(calls.isEmpty(), "未配置窗口时不应推送用量");
  }

  @Test
  void agentRunResultExposesAllFieldsAndToString() {
    AgentRunResult result = new AgentRunResult("输出", 3, 5, true);
    assertEquals("输出", result.getOutput());
    assertEquals(3, result.getToolCallCount());
    assertEquals(5, result.getTurns());
    assertTrue(result.isFailed());
    assertNotNull(result.toString());
    assertFalse(result.toString().isEmpty(), "toString 应有内容，便于日志排查");
  }

  @Test
  void projectRulesPathsAreStable(@TempDir java.nio.file.Path root) {
    // 这两个路径工厂即使文件不存在也要返回，供设置界面「创建规则文件」用。
    File base = root.toFile();
    assertEquals(new File(base, "CLAUDE.md"), ProjectRulesLoader.claudeMd(base));
    assertEquals(new File(base, "AGENTS.md"), ProjectRulesLoader.agentsMd(base));
    assertFalse(ProjectRulesLoader.claudeMd(base).exists(), "文件本不存在，路径仍应可算出");
  }
}

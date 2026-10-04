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

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 危险工具的运行内授权必须**按会话隔离**。
 *
 * <p>背景（reviewer M5）：整个进程只有一份工具设置实例，被所有并行会话共享。若运行内授权
 * 是一个全局集合，会话 A 的 {@code beginRun} 会清掉 B 已批准的规则（B 重复弹窗），而 A
 * 批准后 B 命中同一 ruleKey 会**不弹窗直接放行**——在 A 项目批准的危险命令在 B 项目静默执行。
 *
 * <p>这些断言钉死隔离语义：把实现改回全局集合，本测试会失败。
 */
final class RunApprovedRuleStoreTest {

  private static final String SHELL_GIT = ToolPermissionRule.keyFor("shell_execute", "{\"command\":\"git push\"}");

  @Test
  void approvalInOneConversationDoesNotLeakToAnother() {
    RunApprovedRuleStore store = new RunApprovedRuleStore();

    store.approve("conv-a", SHELL_GIT);

    assertTrue(store.contains("conv-a", SHELL_GIT), "A 会话应看到自己的批准");
    assertFalse(store.contains("conv-b", SHELL_GIT), "A 会话的批准不得泄漏到 B 会话");
  }

  @Test
  void beginRunInOneConversationDoesNotClearAnother() {
    RunApprovedRuleStore store = new RunApprovedRuleStore();
    store.approve("conv-a", SHELL_GIT);
    store.approve("conv-b", SHELL_GIT);

    // A 开始新一轮运行：只清 A。
    store.clear("conv-a");

    assertFalse(store.contains("conv-a", SHELL_GIT), "A 自己的记忆应被清空");
    assertTrue(store.contains("conv-b", SHELL_GIT), "清 A 不得连带清掉 B 的批准");
  }

  @Test
  void endOfRunClearsOnlyThatConversation() {
    RunApprovedRuleStore store = new RunApprovedRuleStore();
    store.approve("conv-a", SHELL_GIT);
    store.approve("conv-b", SHELL_GIT);

    store.clear("conv-a");

    assertTrue(store.contains("conv-b", SHELL_GIT), "A 运行结束不得丢弃 B 运行中的授权");
  }

  @Test
  void blankConversationUsesItsOwnBucketAndDoesNotLeak() {
    RunApprovedRuleStore store = new RunApprovedRuleStore();

    store.approve(null, SHELL_GIT);

    assertTrue(store.contains(null, SHELL_GIT), "无会话调用方应命中无会话桶");
    assertTrue(store.contains("", SHELL_GIT), "null 与空串归入同一无会话桶");
    assertFalse(store.contains("conv-a", SHELL_GIT), "无会话桶不得泄漏到具名会话");
  }

  @Test
  void emptyRuleKeyIsNeverStored() {
    RunApprovedRuleStore store = new RunApprovedRuleStore();

    store.approve("conv-a", "");
    store.approve("conv-a", null);

    assertFalse(store.contains("conv-a", ""), "空规则键不应被记录");
  }

  @Test
  void clearAllWipesEveryConversation() {
    RunApprovedRuleStore store = new RunApprovedRuleStore();
    store.approve("conv-a", SHELL_GIT);
    store.approve("conv-b", SHELL_GIT);

    store.clearAll();

    assertFalse(store.contains("conv-a", SHELL_GIT));
    assertFalse(store.contains("conv-b", SHELL_GIT));
  }
}

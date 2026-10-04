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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 「本次运行内已批准的危险工具规则」的按会话存储。
 *
 * <p><b>为什么需要按会话隔离</b>：整个进程只有一份工具设置实例，被所有并行会话共享。
 * 若运行内授权是一个全局集合，就会出现两种串扰：
 * <ul>
 *   <li>会话 A 开始新运行时清空集合，把会话 B 运行中已批准的规则一并清掉 → B 重复弹窗；
 *   <li>A 批准某条规则后，B 命中同一 ruleKey 会**不弹窗直接放行** → 在 A 项目批准的危险
 *       命令在 B 项目静默执行。
 * </ul>
 * 分桶后，运行内授权严格限定在产生它的会话内。
 *
 * <p><b>为什么放在纯 Java 模块</b>：这是安全相关逻辑，必须能被 JVM 单测钉死
 * （见 {@code RunApprovedRuleStoreTest}）。放在 app 层的 Android 类里就测不了。
 *
 * <p>线程安全：所有读写都在同一把锁内，与 {@code AgentToolSettings} 的确认串行化锁配合
 * （危险工具的并发调用需要排队，否则会弹出重叠对话框）。
 */
public final class RunApprovedRuleStore {

  /** 会话 id → 该会话已批准的规则键。空串桶用于无会话调用方。 */
  private final Map<String, Set<String>> byConversation = new LinkedHashMap<>();

  private final Object lock = new Object();

  /** 会话 id → 桶键；null/空串归入统一的无会话桶。 */
  public static String bucketKey(String conversationId) {
    return conversationId == null ? "" : conversationId;
  }

  /** 该会话是否已批准过这条规则。 */
  public boolean contains(String conversationId, String ruleKey) {
    if (ruleKey == null || ruleKey.isEmpty()) {
      return false;
    }
    synchronized (lock) {
      Set<String> approved = byConversation.get(bucketKey(conversationId));
      return approved != null && approved.contains(ruleKey);
    }
  }

  /** 记录该会话批准了一条规则。 */
  public void approve(String conversationId, String ruleKey) {
    if (ruleKey == null || ruleKey.isEmpty()) {
      return;
    }
    synchronized (lock) {
      byConversation
          .computeIfAbsent(bucketKey(conversationId), k -> new LinkedHashSet<>())
          .add(ruleKey);
    }
  }

  /** 开始新运行时清空**该会话**的记忆，不影响其它会话。 */
  public void clear(String conversationId) {
    synchronized (lock) {
      byConversation.remove(bucketKey(conversationId));
    }
  }

  /** 清空所有会话的记忆（设置页「重置授权」）。 */
  public void clearAll() {
    synchronized (lock) {
      byConversation.clear();
    }
  }
}

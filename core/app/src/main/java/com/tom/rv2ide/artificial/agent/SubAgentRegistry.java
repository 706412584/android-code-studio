/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent;

import com.tom.rv2ide.ai.agent.AgentSession;
import com.tom.rv2ide.ai.protocol.ModelCancellationToken;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行中的子 agent 注册表：名字 → 它的会话与运行实例。
 *
 * <p><b>解决什么问题</b>：子 agent 此前是一次匿名同步调用——父 agent 派出去、等结论、
 * 期间无从干预，也无从知道「它现在到什么程度了」。有了名字与会话，父 agent（或用户）
 * 才能对它做两件此前做不到的事：
 * <ul>
 *   <li><b>追加消息</b>：{@code send_message} 的 {@code agent:&lt;name&gt;} 目标。向正在跑的
 *       子 agent 投递一句补充约束，比等它跑完再重来便宜得多。
 *   <li><b>唤醒</b>：它已停止时，从它的会话（见 {@link SubAgentRunnerImpl} 落的独立会话）
 *       续跑，把新消息当作下一次 prompt。
 * </ul>
 *
 * <p><b>为什么是进程内单例式的共享对象</b>：注册发生在 {@code SubAgentRunnerImpl}（运行
 * 子代理的那一层），查询发生在 {@code AgentOrchestrator} 的 send_message 路由里——两者
 * 不在同一个调用栈上。要在它们之间传递「现在有哪些子代理在跑」，只能靠一个共享的注册表。
 * 它是进程内状态：进程重启后子代理自然也没了，与它的生命周期一致，无需持久化。
 *
 * <p><b>线程安全</b>：注册/注销发生在子代理运行线程，查询发生在父 agent 或 UI 线程。
 * 用 {@link ConcurrentHashMap}，且每次查询返回快照副本。
 */
public final class SubAgentRegistry {

  /** 一个已注册（正在跑或刚跑完）的子 agent。 */
  public static final class Entry {
    private final String name;
    private final String conversationId;
    private final String parentId;
    private final String subagentType;
    private final AgentSession session;
    private final ModelCancellationToken cancellation;
    private volatile boolean running;

    Entry(
        String name,
        String conversationId,
        String parentId,
        String subagentType,
        AgentSession session,
        ModelCancellationToken cancellation,
        boolean running) {
      this.name = name == null ? "" : name;
      this.conversationId = conversationId == null ? "" : conversationId;
      this.parentId = parentId == null ? "" : parentId;
      this.subagentType = subagentType == null ? "" : subagentType;
      this.session = session;
      this.cancellation = cancellation;
      this.running = running;
    }

    public String getName() {
      return name;
    }

    /** 子 agent 自己的会话 id；空串表示建会话失败（降级为仅内存运行）。 */
    public String getConversationId() {
      return conversationId;
    }

    public String getParentId() {
      return parentId;
    }

    public String getSubagentType() {
      return subagentType;
    }

    /** 正在跑的 AgentSession；用于向它投递消息。停止后为 null。 */
    public AgentSession getSession() {
      return session;
    }

    public boolean isRunning() {
      return running;
    }

    /** 是否还能向它注入消息（需要 session 且仍在跑）。 */
    boolean canInject() {
      return running && session != null;
    }
  }

  /**
   * 键是 **(父会话 id, 名字)**，不是单个名字。
   *
   * <p><b>为什么必须带上父会话</b>：名字是会话作用域的——每个父会话各自可能有一个叫
   * {@code reviewer} 的子代理。只用名字做键的话，两个会话的同名子代理会互相覆盖，
   * 后果是「后派出的那个把先派出的顶掉」，先那个在自己会话里从此找不到
   * （发消息报「没有叫 reviewer 的子代理」，而它明明还在跑）。
   */
  private final Map<String, Entry> byKey = new ConcurrentHashMap<>();

  /** 组合键；用不会出现在会话 id 里的分隔符避免歧义。 */
  private static String key(String parentId, String name) {
    return (parentId == null ? "" : parentId) + "\u0000" + (name == null ? "" : name);
  }

  /**
   * 登记一个正在跑的子 agent。
   *
   * <p>同会话同名覆盖：父 agent 可能在同一个会话里反复启用同一个名字的角色（如连续两次
   * 「让 reviewer 看看」）。覆盖让最新那次成为可寻址对象，旧的那次已经结束、
   * 它的会话仍在磁盘上（可回看），但不再是注入目标——这正是想要的语义。
   */
  public void register(
      String name,
      String conversationId,
      String parentId,
      String subagentType,
      AgentSession session,
      ModelCancellationToken cancellation) {
    if (name == null || name.isEmpty()) {
      return;
    }
    byKey.put(
        key(parentId, name),
        new Entry(name, conversationId, parentId, subagentType, session, cancellation, true));
  }

  /** 标记某个子 agent 已结束（保留条目，使 send_message 能走「唤醒」路径）。 */
  public void markStopped(String parentId, String name) {
    Entry entry = byKey.get(key(parentId, name));
    if (entry != null) {
      entry.running = false;
    }
  }

  /** 彻底移除。 */
  public void unregister(String parentId, String name) {
    byKey.remove(key(parentId, name));
  }

  /**
   * 按 (父会话, 名字) 查找；不存在返回 null。
   *
   * <p>父会话为空串时退化为「全局按名查找」——那只会出现在没有会话上下文的调用
   * （测试、无主运行）。生产路径（send_message）里父会话 id 恒非空。
   */
  public Entry find(String parentId, String name) {
    if (name == null || name.isEmpty()) {
      return null;
    }
    if (parentId != null && !parentId.isEmpty()) {
      return byKey.get(key(parentId, name));
    }
    for (Entry entry : byKey.values()) {
      if (name.equals(entry.getName())) {
        return entry;
      }
    }
    return null;
  }

  /**
   * 全部已登记的子 agent（快照副本）。
   *
   * @param parentId 只列这个父会话的子代理；null 或空串表示列全部
   */
  public List<Entry> list(String parentId) {
    List<Entry> out = new ArrayList<>();
    for (Entry entry : byKey.values()) {
      if (parentId == null || parentId.isEmpty() || parentId.equals(entry.getParentId())) {
        out.add(entry);
      }
    }
    return out;
  }
}

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

package com.tom.rv2ide.ai.tool;

import java.util.List;

/**
 * 终端会话读取端口：把内置终端（Termux）的会话屏幕内容暴露给 AI 工具层。
 *
 * <p><b>为什么需要它</b>：{@code shell_execute} 每次起一个**独立进程**，看不见用户在
 * 终端里手动跑的东西。实测场景：用户在终端里跑了个报错的命令让 AI 看，AI 只能让他
 * 「把输出贴过来」——而终端内容就在进程里，只是没有通路。
 *
 * <p><b>为什么只读</b>：向用户终端注入命令是有副作用的操作（可能打断他正在跑的东西），
 * 且用户对「AI 在我终端里打字」的心理预期很差。本期只做读取；写入留待明确需求。
 *
 * <p><b>为什么用端口</b>：本模块是纯 JVM、零 Android 依赖的（见
 * {@code core/ai-tool/build.gradle.kts}），而读终端要 Termux 的 emulator 类型与
 * 全局 shell 管理器。因此这里只定义中性契约，由 app 层适配器实现。
 */
public interface TerminalPort {

  /** 一个终端会话的只读快照。 */
  final class Session {
    private final int index;
    private final String title;
    private final boolean running;
    private final String cwd;
    private final String transcript;

    public Session(int index, String title, boolean running, String cwd, String transcript) {
      this.index = index;
      this.title = title == null ? "" : title;
      this.running = running;
      this.cwd = cwd == null ? "" : cwd;
      this.transcript = transcript == null ? "" : transcript;
    }

    /** 会话序号（0 起），供模型按序号指定要读哪个。 */
    public int getIndex() {
      return index;
    }

    /** 会话标题（Termux 通常显示为当前命令或 shell 名）。 */
    public String getTitle() {
      return title;
    }

    /** 前台进程是否仍在运行。 */
    public boolean isRunning() {
      return running;
    }

    /** 会话当前工作目录；未知时为空串。 */
    public String getCwd() {
      return cwd;
    }

    /** 屏幕内容（含滚动缓冲的纯文本）。 */
    public String getTranscript() {
      return transcript;
    }
  }

  /**
   * 列出当前所有终端会话。
   *
   * <p>无会话时返回空列表（不是 null）——调用方据此区分「终端没开」与「读不到」。
   */
  List<Session> sessions();

  /** 端口当前是否可用（Termux 组件是否就绪）。 */
  boolean isAvailable();

  /** 端口不可用的原因；可用时返回空串。 */
  String unavailableReason();
}

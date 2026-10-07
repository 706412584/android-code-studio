/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from LineCode Pro (https://github.com/LangLang03/LineCodePro),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
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

package com.tom.rv2ide.ai.tool;

import com.tom.rv2ide.ai.tool.api.ImageDataProvider;
import java.util.Collections;
import java.util.List;

/**
 * 单次工具执行所需的上下文。
 *
 * <p><b>与上游的差异</b>：LineCode Pro 的 {@code ToolContext} 有 16 个字段，混合了工作区信息、
 * 7 个数据层仓库、子 agent 运行器、字符串解析器等，导致工具层与 Room、SSH、UI 强耦合。
 *
 * <p>这里只保留工具执行真正需要的 5 项。被移除的能力及替代方式：
 * <ul>
 *   <li>子 agent 相关（{@code AgentRunner} / {@code AgentResultStore}）— 本移植不含子 agent
 *   <li>SSH 相关（{@code SshFileTreeStore}）— 由 shell 后端抽象替代
 *   <li>{@code TodoStateStore} / {@code LearningContextStore} — 不在本移植范围
 *   <li>{@code StringResolver} — 工具不再依赖 Android 字符串资源，错误文案改用常量
 * </ul>
 *
 * <p>本类不引用任何 Android 类型，因此工具模块可在 JVM 上单元测试。
 */
public final class ToolContext {

  /** 工作区根目录的绝对路径。工具只能在此目录（及其 {@link #getExtraWriteRoots()}）内操作。 */
  private final String homePath;

  /** 额外允许写入的根目录，用于技能目录等白名单场景。 */
  private final List<String> extraWriteRoots;

  /** 为 true 时跳过 {@link com.tom.rv2ide.ai.tool.builtin.FileToolPathPolicy} 的工作区限制。 */
  private final boolean bypassPathProtection;

  /** 当前 tool call 的 id，用于把执行进度与结果关联回对话。 */
  private final String toolCallId;

  /**
   * 产生本次工具调用的会话 id。
   *
   * <p><b>为什么工具上下文需要它</b>：危险工具的一次性授权（「本次运行内允许」）按会话隔离，
   * 否则并行会话之间会互相放行——在 A 项目批准的危险命令会在 B 项目静默执行。
   * 空串表示无会话（单测、后台一次性调用），此时退化为不按会话隔离。
   */
  private final String conversationId;

  /** 执行过程中的进度回调，可为 null。 */
  private final ProgressListener progressListener;

  private final ToolSettingsPort settings;

  /**
   * 图片解码/缩放实现；由 app 层注入，未注入时为 null。
   *
   * <p>工具模块是纯 Java、零 Android 依赖的 java-library，不能直接引用
   * {@code android.graphics}。读取图片时把缩放这一步委托给该端口。
   */
  private final ImageDataProvider imageDataProvider;

  private ToolContext(Builder builder) {
    this.homePath = builder.homePath == null ? "" : builder.homePath;
    this.extraWriteRoots =
        builder.extraWriteRoots == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(builder.extraWriteRoots);
    this.bypassPathProtection = builder.bypassPathProtection;
    this.toolCallId = builder.toolCallId == null ? "" : builder.toolCallId;
    this.conversationId = builder.conversationId == null ? "" : builder.conversationId;
    this.progressListener = builder.progressListener;
    this.settings = builder.settings == null ? ToolSettingsPort.defaults() : builder.settings;
    this.imageDataProvider = builder.imageDataProvider;
  }

  public String getHomePath() {
    return homePath;
  }

  public List<String> getExtraWriteRoots() {
    return extraWriteRoots;
  }

  public boolean isBypassPathProtection() {
    return bypassPathProtection;
  }

  public String getToolCallId() {
    return toolCallId;
  }

  /** 产生本次工具调用的会话 id；空串表示无会话。 */
  public String getConversationId() {
    return conversationId;
  }

  public ProgressListener getProgressListener() {
    return progressListener;
  }

  public ToolSettingsPort getSettings() {
    return settings;
  }

  /** 图片解码/缩放实现；可能为 null（未注入时读图退化为不缩放）。 */
  public ImageDataProvider getImageDataProvider() {
    return imageDataProvider;
  }

  /**
   * 返回一个仅替换 {@code toolCallId} 的副本。
   *
   * <p>执行器在派发到具体工具前用当前调用的 id 覆盖上下文，使工具内部的进度与错误
   * 能关联回正确的调用，而调用方传入的其它字段（工作区、权限设置等）保持不变。
   */
  public ToolContext withToolCallId(String newToolCallId) {
    return builder()
        .homePath(this.homePath)
        .extraWriteRoots(this.extraWriteRoots)
        .bypassPathProtection(this.bypassPathProtection)
        .toolCallId(newToolCallId)
        .conversationId(this.conversationId)
        .progressListener(this.progressListener)
        .settings(this.settings)
        .imageDataProvider(this.imageDataProvider)
        .build();
  }

  /**
   * 返回一个仅替换 {@code progressListener} 的副本。
   *
   * <p>调用方（编排层）在事件流建好后用它接线进度端口——构建顺序决定进度监听器
   * 无法在 builder 链上一次性给出（事件流依赖持久化监听器，后者又依赖本上下文之外
   * 的会话信息）。其它字段原样保留。
   */
  public ToolContext withProgressListener(ProgressListener newProgressListener) {
    return builder()
        .homePath(this.homePath)
        .extraWriteRoots(this.extraWriteRoots)
        .bypassPathProtection(this.bypassPathProtection)
        .toolCallId(this.toolCallId)
        .conversationId(this.conversationId)
        .progressListener(newProgressListener)
        .settings(this.settings)
        .imageDataProvider(this.imageDataProvider)
        .build();
  }

  /** 报告工具执行进度。无监听者时静默忽略。 */
  public void reportProgress(String message) {
    ProgressListener listener = this.progressListener;
    if (listener != null) {
      listener.onProgress(message);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  /** 工具执行进度的接收者。 */
  public interface ProgressListener {
    void onProgress(String message);
  }

  public static final class Builder {
    private String homePath;
    private List<String> extraWriteRoots;
    private boolean bypassPathProtection;
    private String toolCallId;
    private String conversationId;
    private ProgressListener progressListener;
    private ToolSettingsPort settings;
    private ImageDataProvider imageDataProvider;

    public Builder homePath(String value) {
      this.homePath = value;
      return this;
    }

    public Builder extraWriteRoots(List<String> value) {
      this.extraWriteRoots = value;
      return this;
    }

    public Builder bypassPathProtection(boolean value) {
      this.bypassPathProtection = value;
      return this;
    }

    public Builder toolCallId(String value) {
      this.toolCallId = value;
      return this;
    }

    /** 产生本次调用的会话 id，用于危险工具授权的按会话隔离。 */
    public Builder conversationId(String value) {
      this.conversationId = value;
      return this;
    }

    public Builder progressListener(ProgressListener value) {
      this.progressListener = value;
      return this;
    }

    public Builder settings(ToolSettingsPort value) {
      this.settings = value;
      return this;
    }

    public Builder imageDataProvider(ImageDataProvider value) {
      this.imageDataProvider = value;
      return this;
    }

    public ToolContext build() {
      return new ToolContext(this);
    }
  }
}

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

  /** 执行过程中的进度回调，可为 null。 */
  private final ProgressListener progressListener;

  private final ToolSettingsPort settings;

  private ToolContext(Builder builder) {
    this.homePath = builder.homePath == null ? "" : builder.homePath;
    this.extraWriteRoots =
        builder.extraWriteRoots == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(builder.extraWriteRoots);
    this.bypassPathProtection = builder.bypassPathProtection;
    this.toolCallId = builder.toolCallId == null ? "" : builder.toolCallId;
    this.progressListener = builder.progressListener;
    this.settings = builder.settings == null ? ToolSettingsPort.defaults() : builder.settings;
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

  public ProgressListener getProgressListener() {
    return progressListener;
  }

  public ToolSettingsPort getSettings() {
    return settings;
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
        .progressListener(this.progressListener)
        .settings(this.settings)
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
    private ProgressListener progressListener;
    private ToolSettingsPort settings;

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

    public Builder progressListener(ProgressListener value) {
      this.progressListener = value;
      return this;
    }

    public Builder settings(ToolSettingsPort value) {
      this.settings = value;
      return this;
    }

    public ToolContext build() {
      return new ToolContext(this);
    }
  }
}

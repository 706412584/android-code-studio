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

import java.util.Collections;
import java.util.List;

/**
 * 编辑器会话端口：把 IDE 编辑器的实时状态与操作暴露给 AI 工具层。
 *
 * <p><b>为什么用中性类型</b>：本模块是纯 JVM、零 Android 依赖的（见
 * core/ai-tool/build.gradle.kts），而编辑器状态由 app 层的
 * {@code IEditorHandler} / {@code CodeEditorView} 提供，它们引用 View 与 sora-editor。
 * 因此这里只定义纯数据的 {@link Snapshot} / {@link OpenResult} / {@link SaveReport}，
 * 由 app 层的适配器完成读取与转换（同 {@link DiagnosticsPort} 的模式）。
 *
 * <p><b>这是 ACS 相对通用编码 agent 的独有能力</b>：通用 agent 无法知道「用户此刻在看哪个文件、
 * 光标在哪、有没有未保存改动」，也无法把用户带到某个代码位置——它只能假设文件内容与磁盘一致。
 * 而编辑器缓冲与磁盘的差异（未保存修改）恰恰是「构建用了旧内容」这类问题的根源。
 *
 * <p><b>三个动作共享一个端口而不是拆成三个</b>：读状态 / 打开定位 / 保存全部 的能力来源
 * 是同一个「编辑器会话」，可用性判定（有没有存活的编辑器 Activity）与失败文案也必须一致。
 * 拆开会让这段判定逻辑与主线程切换代码重复三份，正是本仓库反复踩过的「两处各写一份必然漂移」。
 *
 * <p><b>行列基准：本接口内一律 0 基</b>（与 IDE 内部 sora-editor / LSP 一致，见
 * {@link DiagnosticsPort.Item} 的约定）。工具层负责 1 基展示与 1 基入参的转换。
 */
public interface EditorSessionPort {

  /** 当前是否存在可用的编辑器会话。 */
  boolean isAvailable();

  /**
   * 会话不可用的原因；可用时返回空串。
   *
   * <p>必须给出可执行的说明（「请先在编辑器中打开项目」），而不是泛泛的失败。
   */
  String unavailableReason();

  /**
   * 读取编辑器当前状态。
   *
   * <p>调用方应先查 {@link #isAvailable()} 做快速失败；本方法内部仍须容错——
   * 从判定到调用之间 Activity 可能被销毁，此时返回 {@link Snapshot#unavailable}。
   */
  Snapshot snapshot();

  /**
   * 打开文件并定位/选中。
   *
   * @param absolutePath 目标文件绝对路径（调用方已做工作区校验）
   * @param startLine 起始行（0 基）
   * @param startColumn 起始列（0 基）
   * @param endLine 结束行（0 基）；与 start 相同且 endColumn==startColumn 时为光标点定位
   * @param endColumn 结束列（0 基）
   */
  OpenResult openAt(
      String absolutePath, int startLine, int startColumn, int endLine, int endColumn);

  /**
   * 保存全部编辑器缓冲到磁盘。
   *
   * <p><b>实现必须在后台线程被调用并驱动</b>：底层是 suspend 的保存实现，内部会
   * {@code withContext(Dispatchers.Main)}；若调用方在主线程上阻塞等待（runBlocking），
   * 主线程被占住 → 内部的主线程任务永远排不上 → 死锁。因此本方法在检测到主线程调用时
   * 应直接返回失败，而不是冒险执行。
   *
   * <p><b>磁盘上有更新的外部改动且编辑器有未保存修改的文件必须跳过</b>：那种情况下
   * 「编辑器赢」是错误方向，会把外部改动静默覆盖。跳过的文件在结果中如实列出。
   *
   * @param timeoutMs 整个保存过程的时间上限；超时后返回已保存/已跳过的部分结果并标记失败原因
   */
  SaveReport saveAll(long timeoutMs);

  /** 编辑器状态快照。所有行列 0 基。 */
  final class Snapshot {
    private final String unavailableReason;
    private final String currentFilePath; // 绝对路径；空串 = 编辑器在但没有打开任何文件
    private final int cursorLine;
    private final int cursorColumn;
    private final int selectionStartLine;
    private final int selectionStartColumn;
    private final int selectionEndLine;
    private final int selectionEndColumn;
    private final List<OpenFile> openedFiles;
    private final boolean filesModified;

    private Snapshot(
        String unavailableReason,
        String currentFilePath,
        int cursorLine, int cursorColumn,
        int selectionStartLine, int selectionStartColumn,
        int selectionEndLine, int selectionEndColumn,
        List<OpenFile> openedFiles,
        boolean filesModified) {
      this.unavailableReason = unavailableReason == null ? "" : unavailableReason;
      this.currentFilePath = currentFilePath == null ? "" : currentFilePath;
      this.cursorLine = cursorLine;
      this.cursorColumn = cursorColumn;
      this.selectionStartLine = selectionStartLine;
      this.selectionStartColumn = selectionStartColumn;
      this.selectionEndLine = selectionEndLine;
      this.selectionEndColumn = selectionEndColumn;
      this.openedFiles =
          openedFiles == null ? Collections.<OpenFile>emptyList() : Collections.unmodifiableList(openedFiles);
      this.filesModified = filesModified;
    }

    /** 会话不可用（Activity 已销毁 / 不存在）。 */
    public static Snapshot unavailable(String reason) {
      return new Snapshot(reason, "", -1, -1, -1, -1, -1, -1,
          Collections.<OpenFile>emptyList(), false);
    }

    /**
     * 会话可用。
     *
     * @param currentFilePath 绝对路径；空串表示没有当前文件
     * @param cursorLine 0 基；无当前文件时传 -1
     * @param selectionStartLine 0 基；无选区时四个 selection* 全传 -1
     */
    public static Snapshot of(
        String currentFilePath,
        int cursorLine, int cursorColumn,
        int selectionStartLine, int selectionStartColumn,
        int selectionEndLine, int selectionEndColumn,
        List<OpenFile> openedFiles,
        boolean filesModified) {
      return new Snapshot(null, currentFilePath, cursorLine, cursorColumn,
          selectionStartLine, selectionStartColumn, selectionEndLine, selectionEndColumn,
          openedFiles, filesModified);
    }

    public boolean isUnavailable() {
      return !unavailableReason.isEmpty();
    }

    public String getUnavailableReason() {
      return unavailableReason;
    }

    public String getCurrentFilePath() {
      return currentFilePath;
    }

    public boolean hasCurrentFile() {
      return !currentFilePath.isEmpty();
    }

    public int getCursorLine() {
      return cursorLine;
    }

    public int getCursorColumn() {
      return cursorColumn;
    }

    public boolean hasSelection() {
      return selectionStartLine >= 0;
    }

    public int getSelectionStartLine() {
      return selectionStartLine;
    }

    public int getSelectionStartColumn() {
      return selectionStartColumn;
    }

    public int getSelectionEndLine() {
      return selectionEndLine;
    }

    public int getSelectionEndColumn() {
      return selectionEndColumn;
    }

    public List<OpenFile> getOpenedFiles() {
      return openedFiles;
    }

    public boolean isFilesModified() {
      return filesModified;
    }
  }

  /** 一个已打开的文件。 */
  final class OpenFile {
    private final String absolutePath;
    private final boolean modified;
    private final boolean current;

    public OpenFile(String absolutePath, boolean modified, boolean current) {
      this.absolutePath = absolutePath == null ? "" : absolutePath;
      this.modified = modified;
      this.current = current;
    }

    public String getAbsolutePath() {
      return absolutePath;
    }

    /** 编辑器缓冲与磁盘不一致（有未保存修改）。 */
    public boolean isModified() {
      return modified;
    }

    /** 是否为当前显示的文件。 */
    public boolean isCurrent() {
      return current;
    }
  }

  /** openAt 的结果。 */
  final class OpenResult {
    private final String failureReason;
    private final boolean opened;
    private final boolean editorVisible;

    private OpenResult(String failureReason, boolean opened, boolean editorVisible) {
      this.failureReason = failureReason == null ? "" : failureReason;
      this.opened = opened;
      this.editorVisible = editorVisible;
    }

    public static OpenResult failed(String reason) {
      return new OpenResult(reason, false, false);
    }

    /**
     * @param editorVisible 编辑器 Activity 是否在前台（RESUMED）。false 表示跳转已提交，
     *     但用户此刻看不到——结果文案应提示「切回项目界面即可看到」。
     */
    public static OpenResult opened(boolean editorVisible) {
      return new OpenResult(null, true, editorVisible);
    }

    public boolean isFailed() {
      return !failureReason.isEmpty();
    }

    public String getFailureReason() {
      return failureReason;
    }

    public boolean isOpened() {
      return opened;
    }

    public boolean isEditorVisible() {
      return editorVisible;
    }
  }

  /** saveAll 的结果。 */
  final class SaveReport {
    private final String failureReason;
    private final List<String> savedPaths;
    private final List<String> skippedConflictPaths;

    private SaveReport(String failureReason, List<String> savedPaths,
        List<String> skippedConflictPaths) {
      this.failureReason = failureReason == null ? "" : failureReason;
      this.savedPaths =
          savedPaths == null ? Collections.<String>emptyList() : Collections.unmodifiableList(savedPaths);
      this.skippedConflictPaths =
          skippedConflictPaths == null
              ? Collections.<String>emptyList()
              : Collections.unmodifiableList(skippedConflictPaths);
    }

    public static SaveReport failed(String reason) {
      return new SaveReport(reason, Collections.<String>emptyList(), Collections.<String>emptyList());
    }

    /**
     * @param savedPaths 实际写盘的文件（绝对路径）
     * @param skippedConflictPaths 未保存的文件：磁盘存在更新的外部改动而跳过，或保存未生效/无法确认
     */
    public static SaveReport of(List<String> savedPaths, List<String> skippedConflictPaths) {
      return new SaveReport(null, savedPaths, skippedConflictPaths);
    }

    public boolean isFailed() {
      return !failureReason.isEmpty();
    }

    public String getFailureReason() {
      return failureReason;
    }

    public List<String> getSavedPaths() {
      return savedPaths;
    }

    public List<String> getSkippedConflictPaths() {
      return skippedConflictPaths;
    }
  }
}

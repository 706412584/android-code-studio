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

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 编辑器工具（{@link EditorStateTool} / {@link FileOpenTool} / {@link SaveAllTool}）的
 * 共用格式化纯函数。
 *
 * <p>抽出来的理由与 {@link DiagnosticsMessages} 相同：三工具面向同一个编辑器会话，
 * 输出措辞必须一致（同一份状态不能有两种说法），且格式化是纯函数，可在 JVM 上直接单测。
 *
 * <p><b>行列基准</b>：端口给的是一律 0 基（与 IDE 内部 sora-editor / LSP 一致），
 * 本类负责转 1 基展示——与 file_read / grep 的行号一致，模型不需要记两套基准。
 */
final class EditorStateMessages {

  private EditorStateMessages() {}

  /** 把编辑器快照格式化成模型可读的文本（行列 1 基）。 */
  static String format(ToolContext context, EditorSessionPort.Snapshot snap) {
    StringBuilder sb = new StringBuilder();
    if (snap.hasCurrentFile()) {
      sb.append("当前文件：")
          .append(display(context, new File(snap.getCurrentFilePath())))
          .append('\n');
      if (snap.getCursorLine() >= 0) {
        sb.append("光标：第 ")
            .append(snap.getCursorLine() + 1)
            .append(" 行，第 ")
            .append(snap.getCursorColumn() + 1)
            .append(" 列（1 基）")
            .append('\n');
      }
      if (snap.hasSelection()) {
        sb.append("选中：第 ")
            .append(snap.getSelectionStartLine() + 1)
            .append(" 行第 ")
            .append(snap.getSelectionStartColumn() + 1)
            .append(" 列 → 第 ")
            .append(snap.getSelectionEndLine() + 1)
            .append(" 行第 ")
            .append(snap.getSelectionEndColumn() + 1)
            .append(" 列")
            .append('\n');
      }
    } else {
      sb.append("当前文件：（无——编辑器已打开但没有文件）\n");
    }
    sb.append("未保存修改：").append(snap.isFilesModified() ? "是" : "无").append('\n');

    List<EditorSessionPort.OpenFile> files = snap.getOpenedFiles();
    if (files.isEmpty()) {
      sb.append("\n已打开文件：无");
      return sb.toString();
    }

    sb.append("\n已打开文件（").append(files.size()).append(" 个）：\n");
    int index = 1;
    for (EditorSessionPort.OpenFile file : files) {
      sb.append("  ")
          .append(index++)
          .append(". ")
          .append(display(context, new File(file.getAbsolutePath())));
      // 标注当前文件与未保存修改；两者都没有时不加括号，保持行内简洁。
      boolean current = file.isCurrent();
      boolean modified = file.isModified();
      if (current && modified) {
        sb.append("（当前文件，未保存修改）");
      } else if (current) {
        sb.append("（当前文件）");
      } else if (modified) {
        sb.append("（未保存修改）");
      }
      sb.append('\n');
    }
    return stripTrailingNewline(sb);
  }

  /**
   * 把保存结果格式化成模型可读的文本。
   *
   * <p>跳过的冲突文件必须单独成组并说明原因——只说「保存了 N 个」会让模型以为
   * 全部落盘，而实际上有文件被刻意留下未保存（避免覆盖外部改动）。
   */
  static String formatSaveReport(ToolContext context, EditorSessionPort.SaveReport report) {
    List<String> saved = report.getSavedPaths();
    List<String> skipped = report.getSkippedConflictPaths();
    if (saved.isEmpty() && skipped.isEmpty()) {
      return "没有需要保存的文件（编辑器内容与磁盘一致）。";
    }

    StringBuilder sb = new StringBuilder();
    if (!saved.isEmpty()) {
      sb.append("已保存 ").append(saved.size()).append(" 个文件：\n");
      for (String path : saved) {
        sb.append("  - ").append(display(context, new File(path))).append('\n');
      }
    }
    if (!skipped.isEmpty()) {
      if (sb.length() > 0) {
        sb.append('\n');
      }
      sb.append("以下 ")
          .append(skipped.size())
          .append(" 个文件因磁盘存在更新的外部改动被跳过（未保存，请确认后手动处理）：\n");
      for (String path : skipped) {
        sb.append("  - ").append(display(context, new File(path))).append('\n');
      }
    }
    return stripTrailingNewline(sb);
  }

  /**
   * 展示用路径：工作区内转相对路径；转换失败（IO）时回退绝对路径。
   *
   * <p>与 {@link DiagnosticsTool} 的处理一致——路径转换失败不该让整个工具失败。
   */
  static String display(ToolContext context, File file) {
    try {
      return FileToolPathPolicy.displayPath(context.getHomePath(), file);
    } catch (IOException e) {
      return file.getAbsolutePath();
    }
  }

  private static String stripTrailingNewline(StringBuilder sb) {
    int end = sb.length();
    while (end > 0 && sb.charAt(end - 1) == '\n') {
      end--;
    }
    return sb.substring(0, end);
  }
}

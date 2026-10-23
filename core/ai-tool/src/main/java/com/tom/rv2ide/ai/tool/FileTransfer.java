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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 文件/目录的移动与复制。
 *
 * <p><b>为什么单独一个类</b>：{@code file_move} 与 {@code file_copy} 只有「成功后是否
 * 删除源」一步不同，其余（路径策略、覆盖判定、跨文件系统回退、目录递归）逐行相同。
 * 写两份的结果是改一处漏一处——而这里漏掉的那一处是**删除源文件**，代价是用户丢数据。
 *
 * <p><b>为什么不能直接用 {@code File.renameTo}</b>：它跨文件系统会失败（Android 上
 * 内部存储与外部存储就是两个文件系统），且失败时不告诉你原因（只返回 false）。
 * 因此移动先试 rename，失败再退回「复制 + 删除源」。
 *
 * <p><b>符号链接</b>：只处理普通文件与目录。遇到符号链接按「指向的目标」复制内容，
 * 不复制链接本身——AI 工具不应在用户工作区里制造链接，那会让后续的路径策略判断
 * 出现「canonical 路径在 workspace 外」的误判。
 */
final class FileTransfer {

  /** 递归复制时的条目上限：防止误把巨大的 build/ 目录整个复制过去。 */
  private static final int MAX_ENTRIES = 20_000;

  private FileTransfer() {}

  /** 一次转移的结果：成功时 error 为 null。 */
  static final class Outcome {
    final String error;
    final int entries;

    private Outcome(String error, int entries) {
      this.error = error;
      this.entries = entries;
    }

    static Outcome ok(int entries) {
      return new Outcome(null, entries);
    }

    static Outcome fail(String error) {
      return new Outcome(error, 0);
    }

    boolean isSuccess() {
      return error == null;
    }
  }

  /**
   * 移动（重命名）。跨文件系统时退化为复制 + 删除源。
   *
   * @param overwrite 目标已存在时是否替换
   */
  static Outcome move(File source, File target, boolean overwrite) {
    Outcome check = validate(source, target, overwrite);
    if (check != null) {
      return check;
    }
    // 同一文件系统内的 rename 是原子的，也不会产生半成品
    if (source.renameTo(target)) {
      return Outcome.ok(1);
    }
    // 跨文件系统（或目标父目录刚建好但 rename 仍失败）：复制后再删源。
    // 复制失败时源文件保持原样——这正是「先复制后删除」而不是「先删除后复制」的理由。
    Outcome copied = copy(source, target, overwrite);
    if (!copied.isSuccess()) {
      return copied;
    }
    if (!deleteRecursive(source)) {
      return Outcome.fail("copied to " + target.getPath() + " but failed to remove source "
          + source.getPath() + "; remove it manually to avoid duplicates");
    }
    return copied;
  }

  /** 复制（目录递归）。 */
  static Outcome copy(File source, File target, boolean overwrite) {
    Outcome check = validate(source, target, overwrite);
    if (check != null) {
      return check;
    }
    try {
      int entries = copyRecursive(source, target, new int[] {0});
      return Outcome.ok(entries);
    } catch (IOException e) {
      // 复制到一半失败：清掉目标，避免留下一个看起来完整、实际缺文件的目录
      deleteRecursive(target);
      return Outcome.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
  }

  /**
   * 前置校验。返回 null 表示可以继续。
   *
   * <p>顺序有意为之：源不存在 → 同路径 → 移进自己 → 目标已存在。每一步的文案都指向
   * 下一步动作，模型据此能自己修正，而不是反复试错。
   */
  private static Outcome validate(File source, File target, boolean overwrite) {
    if (!source.exists()) {
      return Outcome.fail(ToolMessages.format(ToolMessages.FILE_MOVE_SOURCE_NOT_FOUND, source.getPath()));
    }
    if (source.getAbsolutePath().equals(target.getAbsolutePath())) {
      return Outcome.fail(ToolMessages.format(ToolMessages.FILE_MOVE_SAME_PATH, source.getPath()));
    }
    // 把目录移进自己的子目录会无限递归（复制）或直接失败（rename），提前拦住
    String sourcePath = source.getAbsolutePath() + File.separator;
    if (target.getAbsolutePath().startsWith(sourcePath)) {
      return Outcome.fail(ToolMessages.format(
          ToolMessages.FILE_MOVE_INTO_SELF, source.getPath(), target.getPath()));
    }
    if (target.exists()) {
      if (!overwrite) {
        return Outcome.fail(ToolMessages.format(ToolMessages.FILE_MOVE_TARGET_EXISTS, target.getPath()));
      }
      // 目标是非空目录时 deleteRecursively 会删掉里面的所有东西——这是用户没同意的范围
      if (target.isDirectory() && !source.isDirectory()) {
        return Outcome.fail(ToolMessages.format(
            ToolMessages.FILE_MOVE_TARGET_IS_DIRECTORY, target.getPath()));
      }
      if (!deleteRecursive(target)) {
        return Outcome.fail("failed to remove existing target " + target.getPath());
      }
    }
    return null;
  }

  /** 递归复制；返回复制的条目数。 */
  private static int copyRecursive(File source, File target, int[] counter) throws IOException {
    if (++counter[0] > MAX_ENTRIES) {
      throw new IOException("too many entries (>" + MAX_ENTRIES
          + "); refusing to copy a directory this large");
    }
    if (source.isDirectory()) {
      if (!target.exists() && !target.mkdirs()) {
        throw new IOException("cannot create directory " + target.getPath());
      }
      File[] children = source.listFiles();
      if (children == null) {
        throw new IOException("cannot list directory " + source.getPath());
      }
      for (File child : children) {
        copyRecursive(child, new File(target, child.getName()), counter);
      }
      return counter[0];
    }
    File parent = target.getParentFile();
    if (parent != null && !parent.exists() && !parent.mkdirs()) {
      throw new IOException("cannot create directory " + parent.getPath());
    }
    InputStream input = new FileInputStream(source);
    try {
      OutputStream output = new FileOutputStream(target, false);
      try {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
          output.write(buffer, 0, read);
        }
      } finally {
        output.close();
      }
    } finally {
      input.close();
    }
    return counter[0];
  }

  /** 递归删除；返回是否删干净。 */
  static boolean deleteRecursive(File file) {
    if (!file.exists()) {
      return true;
    }
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) {
        for (File child : children) {
          if (!deleteRecursive(child)) {
            return false;
          }
        }
      }
    }
    return file.delete();
  }
}

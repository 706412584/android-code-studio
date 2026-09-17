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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 按 diff 记录恢复文件内容。
 *
 * <p><b>为什么单独一个类</b>：回滚要做两件性质不同的事——算出该恢复成什么（存储的职责）
 * 与真正动文件（文件系统的职责）。合在一起会让每个 {@link DiffStore} 实现都重复一遍
 * 路径校验与写文件逻辑，只要有一个实现漏了校验，回滚就成了绕过工作区边界的写入通道。
 * 因此存储只负责 {@code findById} 与 {@code markReverted}，文件操作集中在这里。
 *
 * <p><b>路径必须重新校验</b>：记录里的路径是写入时算出来的，但用户可能在两次操作之间
 * 改变了工作区（切项目），或手工编辑了会话日志。恢复前用
 * {@link FileToolPathPolicy#resolve(ToolContext, String)} 再走一遍策略，
 * 拒绝任何落在当前工作区之外的目标。
 */
public final class DiffReverter {

  private final DiffStore store;

  public DiffReverter(DiffStore store) {
    this.store = store;
  }

  /**
   * 回滚一条改动：把文件恢复到记录中的旧内容。
   *
   * <p>执行顺序是刻意的——**先写文件，成功后才标记状态**。反过来的话，写入失败会留下
   * 「状态说已回滚、文件其实没变」的记录，用户看到内容还在却无法再次回滚。
   *
   * @param diffId 记录 id
   * @param context 用于路径校验；为 null 时拒绝执行（无法确认目标是否仍在工作区内）
   * @return 回滚结果
   */
  public RevertResult revert(String diffId, ToolContext context) {
    if (store == null) {
      return RevertResult.failed("未配置 diff 存储，无法回滚。");
    }
    if (diffId == null || diffId.isEmpty()) {
      return RevertResult.failed("diff id 为空。");
    }

    DiffRecord record = store.findById(diffId);
    if (record == null) {
      // 记录不存在通常意味着进程重启后用的是内存存储。这条提示要具体，
      // 否则用户只会看到「失败」而不知道是存储没持久化。
      return RevertResult.failed("找不到该改动记录（可能已被清理，或存储未持久化）。");
    }
    if (record.isReverted()) {
      return RevertResult.failed("该改动已经回滚过了。");
    }
    if (context == null) {
      return RevertResult.failed("缺少工作区上下文，无法安全回滚。");
    }

    File target;
    try {
      target = FileToolPathPolicy.resolve(context, record.getFilePath());
    } catch (IOException e) {
      return RevertResult.failed("回滚目标不在当前工作区内：" + ExceptionUtils.describeException(e));
    }

    try {
      if (record.shouldDeleteOnRevert()) {
        // 改动前文件不存在 → 回滚的正确动作是删掉它。写入空内容会留下一个空文件，
        // 与「改动前不存在」并不等价（例如构建脚本会因此认为资源存在）。
        if (target.exists() && !target.delete()) {
          return RevertResult.failed("无法删除回滚目标：" + target.getPath());
        }
      } else {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
          return RevertResult.failed("无法创建回滚目标的父目录：" + parent.getPath());
        }
        byte[] bytes = record.getOldContent().getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(target, false)) {
          output.write(bytes);
        }
      }
    } catch (Exception e) {
      ExceptionUtils.restoreInterrupt(e);
      return RevertResult.failed("回滚写入失败：" + ExceptionUtils.describeException(e));
    }

    DiffRecord updated = store.markReverted(diffId);
    return RevertResult.ok(target.getPath(), updated == null ? record.asReverted() : updated);
  }

  /**
   * 回滚某个文件最近一次改动。
   *
   * <p>用于「撤销上一次写入」这类不关心具体是哪条记录的操作。
   */
  public RevertResult revertLatestFor(String filePath, ToolContext context) {
    if (store == null) {
      return RevertResult.failed("未配置 diff 存储，无法回滚。");
    }
    DiffRecord latest = store.latestFor(filePath);
    if (latest == null) {
      return RevertResult.failed("该文件没有可回滚的改动记录。");
    }
    return revert(latest.getId(), context);
  }

  /** 回滚结果。 */
  public static final class RevertResult {

    private final boolean success;
    private final String message;
    private final DiffRecord record;

    private RevertResult(boolean success, String message, DiffRecord record) {
      this.success = success;
      this.message = message == null ? "" : message;
      this.record = record;
    }

    static RevertResult ok(String filePath, DiffRecord record) {
      return new RevertResult(true, "已恢复：" + filePath, record);
    }

    static RevertResult failed(String message) {
      return new RevertResult(false, message, null);
    }

    public boolean isSuccess() {
      return success;
    }

    /** 面向用户的结果说明；失败时是原因，成功时是恢复的文件路径。 */
    public String getMessage() {
      return message;
    }

    /** 回滚后的记录；失败时为 null。 */
    public DiffRecord getRecord() {
      return record;
    }
  }
}

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

/**
 * 一次文件写入的前后内容快照，用于向用户展示改动并提供回滚。
 *
 * <p><b>与上游的差异</b>：LineCode Pro 的 {@code DiffRecord} 位于 {@code data} 模块，
 * 与 Room 实体耦合。这里保留同样的字段语义，但去掉持久化细节——存储由
 * {@link DiffStore} 的实现方（app 层）负责。
 */
public final class DiffRecord {

  private final String id;
  private final String filePath;
  private final String oldContent;
  private final String newContent;
  private final boolean oldExists;
  private final long timestamp;

  public DiffRecord(
      String id,
      String filePath,
      String oldContent,
      String newContent,
      boolean oldExists,
      long timestamp) {
    this.id = id == null ? "" : id;
    this.filePath = filePath == null ? "" : filePath;
    this.oldContent = oldContent == null ? "" : oldContent;
    this.newContent = newContent == null ? "" : newContent;
    this.oldExists = oldExists;
    this.timestamp = timestamp;
  }

  public String getId() {
    return id;
  }

  public String getFilePath() {
    return filePath;
  }

  public String getOldContent() {
    return oldContent;
  }

  public String getNewContent() {
    return newContent;
  }

  /** 写入前文件是否已存在。false 表示这是一次新建。 */
  public boolean isOldExists() {
    return oldExists;
  }

  public long getTimestamp() {
    return timestamp;
  }
}

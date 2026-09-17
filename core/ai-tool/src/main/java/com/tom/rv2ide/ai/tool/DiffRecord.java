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
 * {@link DiffStore} 的实现方负责。
 *
 * <p><b>审查状态与回滚标记是可变语义、不可变实现</b>：记录本身一经写入就不再改动
 * （回滚需要精确的 oldContent，被覆盖就失去意义），状态变化通过
 * {@link #withReview} / {@link #asReverted} 产生新实例。这样存储层可以安全地缓存与
 * 共享记录，调用方也不会意外改到别人持有的快照。
 */
public final class DiffRecord {

  /** 审查状态：尚未处理。 */
  public static final String REVIEW_PENDING = "pending";

  /** 审查状态：用户已接受该改动。 */
  public static final String REVIEW_ACCEPTED = "accepted";

  /** 审查状态：用户已拒绝（拒绝即回滚）。 */
  public static final String REVIEW_REJECTED = "rejected";

  private final String id;
  private final String filePath;
  private final String oldContent;
  private final String newContent;
  private final boolean oldExists;
  private final long timestamp;
  private final boolean reverted;
  private final String reviewState;
  private final String reviewMessage;

  public DiffRecord(
      String id,
      String filePath,
      String oldContent,
      String newContent,
      boolean oldExists,
      long timestamp) {
    this(id, filePath, oldContent, newContent, oldExists, timestamp, false, REVIEW_PENDING, "");
  }

  public DiffRecord(
      String id,
      String filePath,
      String oldContent,
      String newContent,
      boolean oldExists,
      long timestamp,
      boolean reverted,
      String reviewState,
      String reviewMessage) {
    this.id = id == null ? "" : id;
    this.filePath = filePath == null ? "" : filePath;
    this.oldContent = oldContent == null ? "" : oldContent;
    this.newContent = newContent == null ? "" : newContent;
    this.oldExists = oldExists;
    this.timestamp = timestamp;
    this.reverted = reverted;
    this.reviewState = reviewState == null || reviewState.isEmpty() ? REVIEW_PENDING : reviewState;
    this.reviewMessage = reviewMessage == null ? "" : reviewMessage;
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

  /** 该改动是否已被回滚。已回滚的记录再次回滚是无效操作。 */
  public boolean isReverted() {
    return reverted;
  }

  /** 审查状态：{@link #REVIEW_PENDING} / {@link #REVIEW_ACCEPTED} / {@link #REVIEW_REJECTED}。 */
  public String getReviewState() {
    return reviewState;
  }

  /** 审查备注，例如拒绝原因。可为空。 */
  public String getReviewMessage() {
    return reviewMessage;
  }

  /** 派生一个带新审查状态的记录。 */
  public DiffRecord withReview(String nextReviewState, String nextReviewMessage) {
    return new DiffRecord(
        id,
        filePath,
        oldContent,
        newContent,
        oldExists,
        timestamp,
        reverted,
        nextReviewState,
        nextReviewMessage);
  }

  /**
   * 派生一个「已回滚」的记录。
   *
   * <p>回滚同时把审查状态置为 {@link #REVIEW_REJECTED}：回滚的语义就是用户拒绝了这次改动，
   * 两处状态若不一致，UI 会显示「已接受但内容已还原」这种自相矛盾的结果。
   */
  public DiffRecord asReverted() {
    return new DiffRecord(
        id,
        filePath,
        oldContent,
        newContent,
        oldExists,
        timestamp,
        true,
        REVIEW_REJECTED,
        reviewMessage);
  }

  /**
   * 回滚时是否应当删除该文件。
   *
   * <p>写入前文件不存在（{@link #isOldExists()} 为 false），回滚的正确动作是删掉它，
   * 而不是写入空内容——后者会留下一个空文件，与「改动前不存在」并不等价。
   */
  public boolean shouldDeleteOnRevert() {
    return !oldExists;
  }
}

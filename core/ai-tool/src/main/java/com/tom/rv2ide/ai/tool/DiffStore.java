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
 * 差异记录的存储接口：记录改动、查询历史链、回滚标记、审查标记。
 *
 * <p><b>回滚的实现位置</b>：本接口只负责「找出该恢复成什么」与「标记状态」，
 * 真正的文件写入由 {@link DiffReverter} 完成。这样存储实现（内存 / 文件 / 数据库）
 * 不必各自重复一遍路径校验与写文件逻辑，也不会因为某个实现漏了校验而绕过安全边界。
 *
 * <p>实现方需保证线程安全：工具可能并发执行，回滚也可能与新的写入并发。
 */
public interface DiffStore {

  /**
   * 记录一次文件写入。
   *
   * @param filePath 目标文件的绝对路径
   * @param oldContent 写入前的内容；文件不存在时传空串
   * @param newContent 写入后的内容
   * @param oldExists 写入前文件是否存在
   * @return 生成的记录，供调用方回填到工具结果中
   */
  DiffRecord recordDiff(String filePath, String oldContent, String newContent, boolean oldExists);

  /**
   * 查询某个文件的历史改动链，按时间正序。
   *
   * <p>默认返回空列表，使仅需「记录但不查询」的实现无需覆写。
   */
  default List<DiffRecord> getDiffChain(String filePath) {
    return Collections.emptyList();
  }

  /**
   * 按 id 查一条记录。
   *
   * <p>回滚与审查都从 id 出发（工具结果里带的是 diffId），因此这是回滚路径的入口。
   * 默认返回 null，使不支持的实现方行为明确（调用方须判空）。
   */
  default DiffRecord findById(String diffId) {
    return null;
  }

  /** 全部记录，按时间正序。供审查列表与「回滚本次运行的全部改动」使用。 */
  default List<DiffRecord> getAll() {
    return Collections.emptyList();
  }

  /**
   * 标记某条记录已回滚。
   *
   * <p>只改状态、不动文件——文件恢复由 {@link DiffReverter} 负责。分开的理由是
   * 状态更新是存储的职责、文件操作是文件系统的职责；混在一起会让「文件写失败但状态已改」
   * 这种不一致无法表达。
   *
   * @return 更新后的记录；id 不存在时返回 null
   */
  default DiffRecord markReverted(String diffId) {
    return null;
  }

  /**
   * 设置审查状态。
   *
   * @param reviewState 见 {@link DiffRecord#REVIEW_PENDING} 等常量
   * @return 更新后的记录；id 不存在时返回 null
   */
  default DiffRecord setReview(String diffId, String reviewState, String reviewMessage) {
    return null;
  }

  /** 某个文件最近一条改动；无记录时返回 null。用于「撤销上一次写入」。 */
  default DiffRecord latestFor(String filePath) {
    List<DiffRecord> chain = getDiffChain(filePath);
    return chain.isEmpty() ? null : chain.get(chain.size() - 1);
  }
}

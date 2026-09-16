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
 * 差异记录的持久化接口。
 *
 * <p><b>与上游的差异</b>：LineCode Pro 的 {@code DiffStore} 还包含回滚
 * （{@code revertDiff} / {@code markReverted}）与审查标记（{@code setReview}），
 * 并依赖 {@code data} 模块的 {@code DiffRepository.RevertResult} 类型。
 *
 * <p>本移植只保留工具执行路径真正需要的两个方法：写入前查历史链、写入后记录。
 * 回滚与审查由 app 层在 {@link DiffRecord} 之上自行实现（例如接入 IDE 已有的
 * 撤销/版本控制能力），避免工具模块依赖具体的数据层实现。
 *
 * <p>实现方需保证线程安全：工具可能并发执行。
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
}

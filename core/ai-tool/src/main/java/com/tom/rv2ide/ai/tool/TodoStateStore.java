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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 待办列表的存储接口。
 *
 * <p><b>为什么待办要持久化</b>：长任务的待办列表是模型「记住自己做到哪一步」的依据。
 * 若只放在内存里，进程被系统回收后模型就失去了进度，下次运行会从头再来一遍——
 * 用户看到的是重复劳动。落盘后新运行能读到「上次做到第 3 步」，从中续接。
 *
 * <p>本类不引用 Android 类型，因此工具模块可在 JVM 上单元测试。
 */
public interface TodoStateStore {

  /** 当前待办列表，按添加顺序。无待办时返回空列表。 */
  List<TodoItem> getItems();

  /** 覆盖整个列表。 */
  void setItems(List<TodoItem> items);

  /** 清空。 */
  void clear();

  /**
   * 渲染为注入系统提示词的文本块。
   *
   * <p>返回空串表示无待办，调用方不应把它拼进提示词（避免留下一段无意义的空标题）。
   */
  default String renderForPrompt() {
    List<TodoItem> items = getItems();
    if (items == null || items.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (TodoItem item : items) {
      sb.append(item.toLine()).append('\n');
    }
    return sb.toString().trim();
  }

  /** 内存实现；进程内有效。 */
  static TodoStateStore inMemory() {
    return new TodoStateStore() {

      private final List<TodoItem> items = new ArrayList<>();

      @Override
      public synchronized List<TodoItem> getItems() {
        return new ArrayList<>(items);
      }

      @Override
      public synchronized void setItems(List<TodoItem> next) {
        items.clear();
        if (next != null) {
          items.addAll(next);
        }
      }

      @Override
      public synchronized void clear() {
        items.clear();
      }
    };
  }

  /** 空实现：不记录任何待办。 */
  static TodoStateStore none() {
    return new TodoStateStore() {

      @Override
      public List<TodoItem> getItems() {
        return Collections.emptyList();
      }

      @Override
      public void setItems(List<TodoItem> items) {
        // 不记录。
      }

      @Override
      public void clear() {
        // 不记录。
      }
    };
  }
}

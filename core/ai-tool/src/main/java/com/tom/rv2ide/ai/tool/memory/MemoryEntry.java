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

package com.tom.rv2ide.ai.tool.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一条长期记忆。
 *
 * <p>不可变：记忆会被检索、排序并跨请求复用，可变对象会让「谁改了这条」无从追查。
 */
public final class MemoryEntry {

  private final String id;
  private final String text;
  private final List<String> tags;
  private final long createdAt;

  public MemoryEntry(String id, String text, List<String> tags, long createdAt) {
    this.id = id == null ? "" : id;
    this.text = text == null ? "" : text;
    this.tags =
        tags == null
            ? Collections.<String>emptyList()
            : Collections.unmodifiableList(new ArrayList<>(tags));
    this.createdAt = createdAt;
  }

  /** 稳定标识，用于删除与去重。 */
  public String getId() {
    return id;
  }

  /** 记忆正文。 */
  public String getText() {
    return text;
  }

  /** 标签，参与检索匹配。 */
  public List<String> getTags() {
    return tags;
  }

  public long getCreatedAt() {
    return createdAt;
  }

  /** 供检索与展示的单行文本：正文 + 标签。 */
  public String toLine() {
    if (tags.isEmpty()) {
      return text;
    }
    return text + "  [" + String.join(", ", tags) + "]";
  }

  public boolean isBlank() {
    return text.trim().isEmpty();
  }
}

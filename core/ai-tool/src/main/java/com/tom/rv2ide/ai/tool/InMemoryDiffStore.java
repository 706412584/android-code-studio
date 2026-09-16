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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 内存版 {@link DiffStore}：按文件路径保留改动历史链，进程内有效。
 *
 * <p>用于尚未接入持久化存储的阶段。实现线程安全（工具可能并发执行）。
 */
public final class InMemoryDiffStore implements DiffStore {

  private final Map<String, List<DiffRecord>> chains = new HashMap<>();

  @Override
  public synchronized DiffRecord recordDiff(
      String filePath, String oldContent, String newContent, boolean oldExists) {
    DiffRecord record =
        new DiffRecord(
            UUID.randomUUID().toString(),
            filePath,
            oldContent,
            newContent,
            oldExists,
            System.currentTimeMillis());
    chains.computeIfAbsent(filePath, k -> new ArrayList<>()).add(record);
    return record;
  }

  @Override
  public synchronized List<DiffRecord> getDiffChain(String filePath) {
    List<DiffRecord> chain = chains.get(filePath);
    return chain == null
        ? new ArrayList<>()
        : new ArrayList<>(chain);
  }
}

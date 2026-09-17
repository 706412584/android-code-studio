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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 内存版 {@link DiffStore}：按文件路径保留改动历史链，进程内有效。
 *
 * <p>实现线程安全（工具可能并发执行，回滚也可能与新的写入并发）。
 *
 * <p><b>局限：进程重启后无法回滚。</b>记录只活在内存里，用户下次打开应用就找不到
 * 之前的改动。这是当前唯一实现的已知短板；需要跨会话回滚时应改为文件或数据库存储
 * （{@link DiffStore} 接口已按存储无关设计，替换实现即可）。
 *
 * <p>记录同时按 id 与按路径索引，**共用同一批对象**。因为 {@link DiffRecord} 不可变，
 * 状态变化会产生新实例，所以每次更新必须同时替换两处索引，否则会出现
 * 「按 id 查到旧状态、按路径查到新状态」这种不一致。
 */
public final class InMemoryDiffStore implements DiffStore {

  private final Map<String, List<DiffRecord>> chains = new HashMap<>();

  /** id → 记录；用 LinkedHashMap 保证 {@link #getAll()} 的时间顺序稳定。 */
  private final Map<String, DiffRecord> byId = new LinkedHashMap<>();

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
    byId.put(record.getId(), record);
    return record;
  }

  @Override
  public synchronized List<DiffRecord> getDiffChain(String filePath) {
    List<DiffRecord> chain = chains.get(filePath);
    return chain == null ? new ArrayList<>() : new ArrayList<>(chain);
  }

  @Override
  public synchronized DiffRecord findById(String diffId) {
    return diffId == null ? null : byId.get(diffId);
  }

  @Override
  public synchronized List<DiffRecord> getAll() {
    return new ArrayList<>(byId.values());
  }

  @Override
  public synchronized DiffRecord markReverted(String diffId) {
    DiffRecord current = findById(diffId);
    if (current == null) {
      return null;
    }
    return replace(current.asReverted());
  }

  @Override
  public synchronized DiffRecord setReview(
      String diffId, String reviewState, String reviewMessage) {
    DiffRecord current = findById(diffId);
    if (current == null) {
      return null;
    }
    return replace(current.withReview(reviewState, reviewMessage));
  }

  /**
   * 用新实例替换旧记录，两处索引同步更新。
   *
   * <p>路径链里按位置替换而不是删除重建：链的顺序即改动顺序，重建会打乱它，
   * 使 {@link #latestFor} 返回错误的「最近一次改动」。
   */
  private DiffRecord replace(DiffRecord updated) {
    byId.put(updated.getId(), updated);
    List<DiffRecord> chain = chains.get(updated.getFilePath());
    if (chain != null) {
      for (int i = 0; i < chain.size(); i++) {
        if (updated.getId().equals(chain.get(i).getId())) {
          chain.set(i, updated);
          break;
        }
      }
    }
    return updated;
  }
}

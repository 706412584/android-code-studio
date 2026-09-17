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

package com.tom.rv2ide.ai.agent.conversation;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 上下文压缩标记。
 *
 * <p><b>当前只定义与读写，不在 P0-1 实现压缩</b>。它存在的意义是让 P0-2 的实现代价最小：
 * 压缩时只需追加一条本条目并让读取端跳过 {@code [0, upToOrdinal]} 区间的消息，
 * <b>无需改写任何历史条目</b>。若不留这个位置，P0-2 就只能改写历史文件——
 * 那会破坏 append-only 与崩溃安全。
 *
 * <p>{@code parentUuid} 指向被压缩区间的最后一条，使链在压缩处保持连续。
 */
public final class CompactionEntry extends ConversationEntry {

  static final String FIELD_SUMMARY = "summary";
  static final String FIELD_UP_TO_ORDINAL = "upToOrdinal";

  private final String summary;
  private final int upToOrdinal;

  public CompactionEntry(
      String uuid, String parentUuid, long timestamp, String summary, int upToOrdinal) {
    super(uuid, parentUuid, timestamp);
    this.summary = summary == null ? "" : summary;
    this.upToOrdinal = upToOrdinal;
  }

  public static CompactionEntry create(String parentUuid, long timestamp, String summary, int upToOrdinal) {
    return new CompactionEntry(null, parentUuid, timestamp, summary, upToOrdinal);
  }

  @Override
  public Type getType() {
    return Type.COMPACTION;
  }

  public String getSummary() {
    return summary;
  }

  /** @return 被本条压缩覆盖的条目序号上界（含）。 */
  public int getUpToOrdinal() {
    return upToOrdinal;
  }

  @Override
  protected void writeFields(JSONObject json) throws JSONException {
    json.put(FIELD_SUMMARY, summary);
    json.put(FIELD_UP_TO_ORDINAL, upToOrdinal);
  }
}

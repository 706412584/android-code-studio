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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.tool;

import java.util.ArrayList;
import java.util.List;

/**
 * 最小 resources.arsc 解析器：把二进制资源表读成「资源 id + 类型/名 + 值」的扁平列表。
 *
 * <p>用于「按资源 id 或名字查一个值」——apk_browse 只能读原始字节，看不到 arsc 内部；
 * jadx/apktool 太重。这里按 ResTable 的 chunk 结构（全局字符串池 → 包 → 类型 → 条目）
 * 顺序读取，纯 Java 8、零依赖。
 *
 * <p>不解析复杂条目（bag/map，如 style、array、attr 的枚举）的 map 明细，统一标
 * {@code <complex>}；不区分资源限定符（不同 density/locale 的同名条目会各出现一次）。
 */
final class ArscParser {

  /** 一条资源记录。 */
  static final class Entry {
    final long id;
    final String typeName;
    final String keyName;
    final String value;
    final boolean complex;
    /** Res_value 的 dataType（仅非 complex 有效）。 */
    final int valueType;
    /** Res_value 的 data；dataType==0x03 时是全局字符串池下标。 */
    final int valueData;

    Entry(
        long id,
        String typeName,
        String keyName,
        String value,
        boolean complex,
        int valueType,
        int valueData) {
      this.id = id;
      this.typeName = typeName;
      this.keyName = keyName;
      this.value = value;
      this.complex = complex;
      this.valueType = valueType;
      this.valueData = valueData;
    }

    /** {@code 0x7f010001} 形式的资源 id。 */
    String idHex() {
      return "0x" + Long.toHexString(id);
    }
  }

  private final List<Entry> entries = new ArrayList<>();
  private byte[] raw;
  private StringPool globalPool;

  private ArscParser() {}

  /** 是否为 resources.arsc：ResTable 头（type=0x0002）。 */
  static boolean looksLikeArsc(byte[] data) {
    return data != null && data.length > 12 && ParseUtil.u16(data, 0) == 0x0002;
  }

  /**
   * 解析资源表；畸形或非 arsc 返回 null。
   *
   * <p>只解析到条目级就够用；解析失败的单个 chunk 会被跳过，尽量给出部分结果。
   */
  static ArscParser open(byte[] data) {
    if (!looksLikeArsc(data)) {
      return null;
    }
    ArscParser parser = new ArscParser();
    parser.raw = data;
    try {
      parser.parse(data);
    } catch (RuntimeException e) {
      // 返回已解析出的部分内容（若有）。
      if (parser.entries.isEmpty()) {
        return null;
      }
    }
    return parser;
  }

  List<Entry> entries() {
    return entries;
  }

  private void parse(byte[] d) {
    int headerSize = ParseUtil.u16(d, 2);
    int totalSize = ParseUtil.u32(d, 4);
    int end = Math.min(totalSize <= 0 ? d.length : totalSize, d.length);

    StringPool global = null;
    int offset = Math.max(headerSize, 12);
    while (offset + 8 <= end) {
      int type = ParseUtil.u16(d, offset);
      int chunkSize = ParseUtil.u32(d, offset + 4);
      if (chunkSize < 8 || offset + chunkSize > end) {
        break;
      }
      if (type == 0x0001 && global == null) {
        global = StringPool.read(d, offset);
      } else if (type == 0x0200) {
        parsePackage(d, offset, chunkSize, global);
      }
      offset += chunkSize;
    }
    this.globalPool = global;
  }

  /** 原始 resources.arsc 字节（编辑时用于原地改写）。 */
  byte[] raw() {
    return raw;
  }

  /** 全局值字符串池是否为 UTF-8 编码。 */
  boolean globalIsUtf8() {
    return globalPool != null && globalPool.isUtf8();
  }

  /** 全局值字符串池中第 index 个字符串的字节区间 [start, end)；失败返回 null。 */
  int[] globalSlotRange(int index) {
    return globalPool == null ? null : globalPool.slotRange(index);
  }

  private void parsePackage(byte[] d, int chunkStart, int chunkSize, StringPool global) {
    int headerSize = ParseUtil.u16(d, chunkStart + 2);
    long packageId = ParseUtil.u32(d, chunkStart + 8) & 0xffffffffL;
    int typeStringsOff = ParseUtil.u32(d, chunkStart + 268);
    int keyStringsOff = ParseUtil.u32(d, chunkStart + 276);
    StringPool typeStrings = StringPool.read(d, chunkStart + typeStringsOff);
    StringPool keyStrings = StringPool.read(d, chunkStart + keyStringsOff);

    // ResTable_package 头长度随版本不同（284 旧版无 typeIdOffset / 288 新版），
    // 必须用实际 headerSize 定位子 chunk，不能硬取 288。
    int offset = chunkStart + Math.max(headerSize, 8);
    int end = chunkStart + chunkSize;
    while (offset + 8 <= end) {
      int type = ParseUtil.u16(d, offset);
      int size = ParseUtil.u32(d, offset + 4);
      if (size < 8 || offset + size > end) {
        break;
      }
      if (type == 0x0201) {
        parseType(d, offset, size, packageId, typeStrings, keyStrings, global);
      }
      offset += size;
    }
  }

  private void parseType(
      byte[] d,
      int chunkStart,
      int chunkSize,
      long packageId,
      StringPool typeStrings,
      StringPool keyStrings,
      StringPool global) {
    int headerSize = ParseUtil.u16(d, chunkStart + 2);
    int typeId = ParseUtil.u8(d, chunkStart + 8);
    int entryCount = ParseUtil.u32(d, chunkStart + 12);
    int entriesStart = ParseUtil.u32(d, chunkStart + 16);
    String typeName = typeStrings == null ? "" : typeStrings.get(typeId - 1);

    int offsetsBase = chunkStart + headerSize;
    int entriesBase = chunkStart + entriesStart;
    int limit = chunkStart + chunkSize;
    for (int i = 0; i < entryCount; i++) {
      int offPos = offsetsBase + i * 4;
      if (offPos + 4 > limit) {
        break;
      }
      int entryOffset = ParseUtil.u32(d, offPos);
      if (entryOffset == 0xFFFFFFFF) {
        continue;
      }
      int entryPos = entriesBase + entryOffset;
      if (entryPos + 8 > d.length) {
        continue;
      }
      int entrySize = ParseUtil.u16(d, entryPos);
      int flags = ParseUtil.u16(d, entryPos + 2);
      long id = (packageId << 24) | ((long) typeId << 16) | i;
      if ((flags & 0x0008) != 0) { // FLAG_COMPACT（Android 12+ aapt2 紧凑条目）
        // 紧凑条目没有独立的 size/key/Res_value，按普通条目读会错位。这里明确标出，
        // 不给错误数据（真正展开需要额外的紧凑格式支持）。
        entries.add(new Entry(id, typeName, "<compact>", "<compact entry，未展开>", false, 0, 0));
        continue;
      }
      int keyIdx = ParseUtil.u32(d, entryPos + 4);
      String keyName = keyStrings == null ? "" : keyStrings.get(keyIdx);

      if ((flags & 0x0001) != 0) { // FLAG_COMPLEX
        entries.add(new Entry(id, typeName, keyName, "<complex>", true, 0, 0));
        continue;
      }
      int valuePos = entryPos + entrySize;
      if (valuePos + 8 > d.length) {
        continue;
      }
      int dataType = ParseUtil.u8(d, valuePos + 3);
      int data = ParseUtil.u32(d, valuePos + 4);
      entries.add(
          new Entry(
              id, typeName, keyName, formatValue(dataType, data, global), false, dataType, data));
    }
  }

  private static String formatValue(int dataType, int data, StringPool global) {
    switch (dataType) {
      case 0x00:
        return "<null>";
      case 0x01:
        return String.format("@0x%08x", data & 0xffffffffL);
      case 0x02:
        return String.format("@attr/0x%08x", data & 0xffffffffL);
      case 0x03:
        return global == null ? "<string#" + data + ">" : global.get(data);
      case 0x04:
        return String.valueOf(Float.intBitsToFloat(data));
      case 0x10:
        return String.valueOf(data);
      case 0x11:
        return "0x" + Integer.toHexString(data);
      case 0x12:
        return data != 0 ? "true" : "false";
      case 0x1c:
      case 0x1d:
      case 0x1e:
      case 0x1f:
        return String.format("#%08x", data);
      case 0x05:
      case 0x06:
        return "<dimension/fraction 0x" + Integer.toHexString(data) + ">";
      default:
        return "0x" + Integer.toHexString(data);
    }
  }

  /** ResStringPool chunk：支持 UTF-8 与 UTF-16 两种编码。 */
  private static final class StringPool {
    private final byte[] d;
    private final int stringsStart;
    private final int offsetsBase;
    private final int count;
    private final boolean utf8;

    private StringPool(
        byte[] d, int stringsStart, int offsetsBase, int count, boolean utf8) {
      this.d = d;
      this.stringsStart = stringsStart;
      this.offsetsBase = offsetsBase;
      this.count = count;
      this.utf8 = utf8;
    }

    static StringPool read(byte[] d, int chunkStart) {
      if (chunkStart + 28 > d.length || ParseUtil.u16(d, chunkStart) != 0x0001) {
        return null;
      }
      int headerSize = ParseUtil.u16(d, chunkStart + 2);
      int count = ParseUtil.u32(d, chunkStart + 8);
      int flags = ParseUtil.u32(d, chunkStart + 16);
      int stringsStart = ParseUtil.u32(d, chunkStart + 20);
      boolean utf8 = (flags & 0x100) != 0;
      return new StringPool(
          d, chunkStart + stringsStart, chunkStart + headerSize, count, utf8);
    }

    String get(int index) {
      if (index < 0 || index >= count) {
        return "";
      }
      int offPos = offsetsBase + index * 4;
      if (offPos + 4 > d.length) {
        return "";
      }
      int pos = stringsStart + ParseUtil.u32(d, offPos);
      try {
        return utf8 ? decodeUtf8(pos) : decodeUtf16(pos);
      } catch (RuntimeException e) {
        return "";
      }
    }

    boolean isUtf8() {
      return utf8;
    }

    /**
     * 返回第 index 个字符串在整个 arsc 字节里的完整 slot 区间 [start, end)，
     * 含长度前缀与结尾 0。编辑时按整 slot 覆盖，长度不变则其余字符串偏移不受影响。
     */
    int[] slotRange(int index) {
      if (index < 0 || index >= count) {
        return null;
      }
      int offPos = offsetsBase + index * 4;
      if (offPos + 4 > d.length) {
        return null;
      }
      int pos = stringsStart + ParseUtil.u32(d, offPos);
      if (utf8) {
        int[] p = {pos};
        readLen8(p); // 字符数
        int byteLen = readLen8(p); // UTF-8 字节数
        int end = p[0] + byteLen + 1; // +结尾 0
        return end <= d.length ? new int[] {pos, end} : null;
      }
      int p = pos;
      int len = ParseUtil.u16(d, p);
      p += 2;
      if ((len & 0x8000) != 0) {
        int b = ParseUtil.u16(d, p);
        p += 2;
        len = ((len & 0x7fff) << 16) | b;
      }
      int end = p + len * 2 + 2; // 每个 UTF-16 单元 2 字节 + 结尾 0x0000
      return end <= d.length ? new int[] {pos, end} : null;
    }

    private String decodeUtf8(int pos) {
      int[] p = {pos};
      readLen8(p); // 字符数，忽略
      int byteLen = readLen8(p);
      int start = p[0];
      return new String(d, start, byteLen, java.nio.charset.StandardCharsets.UTF_8);
    }

    private int readLen8(int[] p) {
      int a = d[p[0]++] & 0xff;
      if ((a & 0x80) != 0) {
        int b = d[p[0]++] & 0xff;
        return ((a & 0x7f) << 8) | b;
      }
      return a;
    }

    private String decodeUtf16(int pos) {
      int p = pos;
      int len = ParseUtil.u16(d, p);
      p += 2;
      if ((len & 0x8000) != 0) {
        int b = ParseUtil.u16(d, p);
        p += 2;
        len = ((len & 0x7fff) << 16) | b;
      }
      char[] chars = new char[len];
      for (int i = 0; i < len; i++) {
        chars[i] = (char) ParseUtil.u16(d, p + i * 2);
      }
      return new String(chars);
    }
  }
}

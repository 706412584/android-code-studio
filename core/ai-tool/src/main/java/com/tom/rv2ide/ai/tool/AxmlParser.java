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
 * AXML（Android 二进制 XML）解析器：直接解析 APK 里 AndroidManifest.xml 的字节，
 * 提取包名、版本、权限与四大组件——**不解压、不依赖 aapt / PackageManager**。
 *
 * <p><b>为什么自己写</b>：MT 管理器式「不解压读包信息」的关键就是直接读 ZIP 里的
 * manifest 并解析 AXML。aapt 是桌面工具（设备跑不了），apktool 要全量解包（慢），
 * 而这里纯 Java 直接解析字节，可在 JVM 上单测。
 *
 * <p>格式（小端）：ResXMLTree 头 → StringPool（字符串表）→ 可选 ResMap →
 * 一串 XML 节点（startElement/endElement）。只提取需要的字段，不重建完整 XML。
 */
final class AxmlParser {

  static final class Manifest {
    String packageName = "";
    String versionName = "";
    Integer versionCode;
    Integer minSdk;
    Integer targetSdk;
    final List<String> permissions = new ArrayList<>();
    final List<String> activities = new ArrayList<>();
    final List<String> services = new ArrayList<>();
    final List<String> receivers = new ArrayList<>();
    final List<String> providers = new ArrayList<>();
  }

  private AxmlParser() {}

  private static int u16(byte[] d, int off) {
    return (d[off] & 0xff) | ((d[off + 1] & 0xff) << 8);
  }

  private static int u32(byte[] d, int off) {
    return (d[off] & 0xff)
        | ((d[off + 1] & 0xff) << 8)
        | ((d[off + 2] & 0xff) << 16)
        | ((d[off + 3] & 0xff) << 24);
  }

  /** 解析 AXML 字节为 {@link Manifest}；解析失败返回 null。 */
  static Manifest parse(byte[] data) {
    if (data == null || data.length < 8 || u32(data, 0) != 0x00080003) {
      return null;
    }
    try {
      Manifest manifest = new Manifest();
      // 跳过 ResXMLTree 头（8 字节）。
      int offset = 8;
      StringPool pool = null;
      while (offset + 8 <= data.length) {
        int type = u16(data, offset);
        int chunkSize = u32(data, offset + 4);
        if (chunkSize < 8 || offset + chunkSize > data.length) {
          break;
        }
        if (type == 0x0001) { // StringPool
          pool = StringPool.read(data, offset);
        } else if (type == 0x0102 && pool != null) { // StartElement
          readStartElement(data, offset, pool, manifest);
        }
        offset += chunkSize;
      }
      return manifest;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** 读取一个 startElement 节点，按元素名填充 manifest。 */
  private static void readStartElement(byte[] d, int off, StringPool pool, Manifest m) {
    // 布局：header(8) + lineNumber(4) + comment(4) + ns(4) + name(4)
    //       + attributeStart(2)+attributeSize(2)+attributeCount(2)+idIndex(2)+classIndex(2)+styleIndex(2)
    int nameIdx = u32(d, off + 20);
    String element = pool.string(nameIdx);
    int attrCount = u16(d, off + 28);
    int attrStart = off + 36;
    java.util.Map<String, Object> attrs = new java.util.HashMap<>();
    for (int i = 0; i < attrCount; i++) {
      int p = attrStart + i * 20;
      if (p + 20 > d.length) {
        break;
      }
      String attrName = pool.string(u32(d, p + 4));
      // 属性布局（每项 20 字节）：ns(4)+name(4)+rawValue(4)+typedValue.size(2)+res0(1)+dataType(1)+data(4)
      int rawIdx = u32(d, p + 8); // rawValue 在 p+8（不是 p+12）
      int dataType = d[p + 15] & 0xff; // dataType 在 p+15（1 字节，不是 u16(p+14)）
      int data = u32(d, p + 16);
      Object value;
      if (dataType == 0x03 && rawIdx != 0xffffffff) { // string
        value = pool.string(rawIdx);
      } else if (dataType == 0x10) { // int
        value = data;
      } else if (dataType == 0x12) { // bool
        value = data != 0;
      } else if (rawIdx != 0xffffffff) {
        value = pool.string(rawIdx);
      } else {
        value = data;
      }
      attrs.put(attrName, value);
    }

    switch (element) {
      case "manifest":
        m.packageName = str(attrs.get("package"));
        m.versionName = str(attrs.get("versionName"));
        m.versionCode = intOf(attrs.get("versionCode"));
        break;
      case "uses-sdk":
        m.minSdk = intOf(attrs.get("minSdkVersion"));
        m.targetSdk = intOf(attrs.get("targetSdkVersion"));
        break;
      case "uses-permission":
        m.permissions.add(str(attrs.get("name")));
        break;
      case "activity":
        m.activities.add(str(attrs.get("name")));
        break;
      case "service":
        m.services.add(str(attrs.get("name")));
        break;
      case "receiver":
        m.receivers.add(str(attrs.get("name")));
        break;
      case "provider":
        m.providers.add(str(attrs.get("name")));
        break;
      default:
        break;
    }
  }

  private static String str(Object o) {
    return o == null ? "" : o.toString();
  }

  private static Integer intOf(Object o) {
    if (o instanceof Integer) {
      return (Integer) o;
    }
    if (o instanceof String) {
      try {
        return Integer.parseInt((String) o);
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  /** StringPool chunk：字符串表（支持 UTF-8 与 UTF-16）。 */
  private static final class StringPool {
    private final byte[] data;
    private final int[] offsets;
    private final int stringsStart;
    private final boolean utf8;

    private StringPool(byte[] data, int[] offsets, int stringsStart, boolean utf8) {
      this.data = data;
      this.offsets = offsets;
      this.stringsStart = stringsStart;
      this.utf8 = utf8;
    }

    static StringPool read(byte[] d, int off) {
      int stringCount = u32(d, off + 8);
      int flags = u32(d, off + 16);
      int stringsStart = u32(d, off + 20);
      boolean utf8 = (flags & 0x100) != 0; // UTF8_FLAG = 1<<8
      int[] offsets = new int[stringCount];
      for (int i = 0; i < stringCount; i++) {
        offsets[i] = u32(d, off + 28 + i * 4);
      }
      return new StringPool(d, offsets, off + stringsStart, utf8);
    }

    String string(int index) {
      if (index < 0 || index >= offsets.length) {
        return "";
      }
      int p = stringsStart + offsets[index];
      if (utf8) {
        // UTF-8：先 UTF-16 长度（变长），再 UTF-8 字节长度（变长），然后内容。
        int[] l16 = readLen8(p);
        int[] l8 = readLen8(p + l16[1]);
        int start = p + l16[1] + l8[1];
        return new String(data, start, l8[0], java.nio.charset.StandardCharsets.UTF_8);
      }
      // UTF-16：长度（2 字节，可能 4 字节高位扩展），内容 UTF-16LE。
      int len = u16(data, p);
      int start = p + 2;
      if ((len & 0x8000) != 0) {
        len = ((len & 0x7fff) << 16) | u16(data, p + 2);
        start = p + 4;
      }
      return new String(data, start, len * 2, java.nio.charset.StandardCharsets.UTF_16LE);
    }

    /** 读 UTF-8 变长长度，返回 {值, 占用字节数(1 或 2)}。 */
    private int[] readLen8(int off) {
      int first = data[off] & 0xff;
      if ((first & 0x80) != 0) {
        int second = data[off + 1] & 0xff;
        return new int[] {((first & 0x7f) << 8) | second, 2};
      }
      return new int[] {first, 1};
    }
  }
}

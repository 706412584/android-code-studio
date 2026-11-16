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

/**
 * 通用 AXML（编译后的二进制 XML）解码器：把 res/layout、res/xml、AndroidManifest 等
 * 任意 AXML 还原成人类可读的 XML 文本。
 *
 * <p>与 {@link AxmlParser} 的分工：那个只抽 manifest 的关键字段（包名/权限/组件），
 * 本类做完整的元素树 + 属性渲染，用于「读一眼某个布局长什么样」。
 *
 * <p>局限：framework 属性的资源 id（{@code 0x0101xxxx}）在没有 android R 映射表时
 * 无法还原成 {@code android:layout_width} 这类名字，此时属性名退化为
 * {@code attr#<池内名或下标>}；属性值能正常给出（字符串/引用/数值）。
 */
final class AxmlDecoder {

  private static final int TYPE_STRING_POOL = 0x0001;
  private static final int TYPE_START_ELEMENT = 0x0102;
  private static final int TYPE_END_ELEMENT = 0x0103;
  private static final int TYPE_CDATA = 0x0104;

  private final byte[] d;
  private Pool pool;

  private AxmlDecoder(byte[] data) {
    this.d = data;
  }

  /** 解码 AXML；失败返回 null。 */
  static String decode(byte[] data) {
    if (data == null || data.length < 8 || ParseUtil.u16(data, 0) != 0x0003) {
      return null;
    }
    try {
      AxmlDecoder decoder = new AxmlDecoder(data);
      return decoder.render();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private String render() {
    StringBuilder sb = new StringBuilder();
    int headerSize = ParseUtil.u16(d, 2);
    int total = ParseUtil.u32(d, 4);
    int end = Math.min(total <= 0 ? d.length : total, d.length);

    int depth = 0;
    // 待定的空白元素（start 后面紧跟同名 end 时折叠成 <tag/>）。
    String pendingName = null;
    String pendingAttrs = null;

    int off = Math.max(headerSize, 8);
    while (off + 8 <= end) {
      int type = ParseUtil.u16(d, off);
      int chunkSize = ParseUtil.u32(d, off + 4);
      if (chunkSize < 8 || off + chunkSize > end) {
        break;
      }
      if (type == TYPE_STRING_POOL && pool == null) {
        pool = Pool.read(d, off);
      } else if (type == TYPE_START_ELEMENT) {
        flushPending(sb, depth, pendingName, pendingAttrs);
        pendingName = elementName(off);
        pendingAttrs = attributes(off);
      } else if (type == TYPE_END_ELEMENT) {
        String name = elementName(off);
        if (pendingName != null && pendingName.equals(name)) {
          // 空白元素。
          indent(sb, depth).append('<').append(pendingName).append(pendingAttrs).append("/>\n");
          pendingName = null;
          pendingAttrs = null;
        } else {
          flushPending(sb, depth, pendingName, pendingAttrs);
          if (depth > 0) {
            depth--;
          }
          indent(sb, depth).append("</").append(name).append(">\n");
        }
      } else if (type == TYPE_CDATA) {
        flushPending(sb, depth, pendingName, pendingAttrs);
        int dataIdx = ParseUtil.u32(d, off + 16);
        String text = pool == null ? "" : pool.get(dataIdx);
        if (!text.trim().isEmpty()) {
          indent(sb, depth).append(escape(text)).append('\n');
        }
      }
      off += chunkSize;
    }
    flushPending(sb, depth, pendingName, pendingAttrs);
    return sb.toString();
  }

  private void flushPending(StringBuilder sb, int depth, String name, String attrs) {
    if (name != null) {
      indent(sb, depth).append('<').append(name).append(attrs).append(">\n");
    }
  }

  private StringBuilder indent(StringBuilder sb, int depth) {
    for (int i = 0; i < depth; i++) {
      sb.append("  ");
    }
    return sb;
  }

  /** StartElement / EndElement 的元素名：header(16) 后是 ns(u32) + name(u32)。 */
  private String elementName(int chunkStart) {
    int nameIdx = ParseUtil.u32(d, chunkStart + 20);
    String name = pool == null ? "" : pool.get(nameIdx);
    return name.isEmpty() ? "?" : name;
  }

  /** 渲染 StartElement 的属性串（形如 {@code  a="1" b="2"}）。 */
  private String attributes(int chunkStart) {
    int headerSize = ParseUtil.u16(d, chunkStart + 2);
    int attrStart = ParseUtil.u16(d, chunkStart + 24);
    int attrSize = ParseUtil.u16(d, chunkStart + 26);
    int attrCount = ParseUtil.u16(d, chunkStart + 28);
    int base = chunkStart + headerSize + attrStart;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < attrCount; i++) {
      int ap = base + i * attrSize;
      int nsIdx = ParseUtil.u32(d, ap);
      int nameIdx = ParseUtil.u32(d, ap + 4);
      int rawIdx = ParseUtil.u32(d, ap + 8);
      int dataType = ParseUtil.u8(d, ap + 15);
      int data = ParseUtil.u32(d, ap + 16);

      String name = pool == null ? "" : pool.get(nameIdx);
      if (name.isEmpty()) {
        name = "attr#" + Integer.toHexString(nameIdx);
      }
      if (nsIdx != 0xFFFFFFFF && isAndroidNs(nsIdx)) {
        name = "android:" + name;
      }
      sb.append(' ').append(name).append("=\"");
      if (rawIdx != 0xFFFFFFFF && pool != null) {
        sb.append(escape(pool.get(rawIdx)));
      } else {
        sb.append(escape(typedValue(dataType, data)));
      }
      sb.append('"');
    }
    return sb.toString();
  }

  private boolean isAndroidNs(int nsIdx) {
    if (pool == null) {
      return false;
    }
    String ns = pool.get(nsIdx);
    return ns != null && ns.contains("android");
  }

  private String typedValue(int dataType, int data) {
    switch (dataType) {
      case 0x03:
        return pool == null ? "" : pool.get(data);
      case 0x01:
        return String.format("@0x%08x", data & 0xffffffffL);
      case 0x12:
        return data != 0 ? "true" : "false";
      case 0x10:
        return String.valueOf(data);
      case 0x11:
        return "0x" + Integer.toHexString(data);
      case 0x04:
        return String.valueOf(Float.intBitsToFloat(data));
      case 0x1c:
      case 0x1d:
      case 0x1e:
      case 0x1f:
        return String.format("#%08x", data);
      default:
        return "0x" + Integer.toHexString(data);
    }
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }

  /** AXML 字符串池（UTF-8 / UTF-16）。 */
  private static final class Pool {
    private final byte[] d;
    private final int offsetsBase;
    private final int stringsStart;
    private final int count;
    private final boolean utf8;

    private Pool(byte[] d, int offsetsBase, int stringsStart, int count, boolean utf8) {
      this.d = d;
      this.offsetsBase = offsetsBase;
      this.stringsStart = stringsStart;
      this.count = count;
      this.utf8 = utf8;
    }

    static Pool read(byte[] d, int chunkStart) {
      if (ParseUtil.u16(d, chunkStart) != TYPE_STRING_POOL) {
        return null;
      }
      int headerSize = ParseUtil.u16(d, chunkStart + 2);
      int count = ParseUtil.u32(d, chunkStart + 8);
      int flags = ParseUtil.u32(d, chunkStart + 16);
      int stringsStart = ParseUtil.u32(d, chunkStart + 20);
      boolean utf8 = (flags & 0x100) != 0;
      return new Pool(d, chunkStart + headerSize, chunkStart + stringsStart, count, utf8);
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

    private String decodeUtf8(int pos) {
      int[] p = {pos};
      readLen8(p);
      int byteLen = readLen8(p);
      return new String(d, p[0], byteLen, java.nio.charset.StandardCharsets.UTF_8);
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

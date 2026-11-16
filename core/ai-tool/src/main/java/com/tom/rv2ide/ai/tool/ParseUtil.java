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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 二进制解析用的小工具：小端整数、无符号 LEB128、十六进制、流读取。
 *
 * <p>纯 Java 8、无依赖。DEX / resources.arsc / APK 签名块的解析都要反复做这几件事，
 * 抽出来避免三处各写一遍。所有读取都不做越界检查的「顺手防护」——调用方在 chunk 边界内
 * 自行保证；解析器在顶层用 try/catch 兜住畸形文件，返回「解析失败」而不是抛到模型面前。
 */
final class ParseUtil {

  private ParseUtil() {}

  static int u8(byte[] d, int off) {
    return d[off] & 0xff;
  }

  static int u16(byte[] d, int off) {
    return (d[off] & 0xff) | ((d[off + 1] & 0xff) << 8);
  }

  static int u32(byte[] d, int off) {
    return (d[off] & 0xff)
        | ((d[off + 1] & 0xff) << 8)
        | ((d[off + 2] & 0xff) << 16)
        | ((d[off + 3] & 0xff) << 24);
  }

  static long u64(byte[] d, int off) {
    long lo = u32(d, off) & 0xffffffffL;
    long hi = u32(d, off + 4) & 0xffffffffL;
    return lo | (hi << 32);
  }

  /**
   * 读无符号 LEB128。返回值放在 {@code cursor[0]}（读取后的新位置）。
   *
   * @param d      数据
   * @param cursor 单元素数组：进来是起始下标，出去是读完后的下标
   * @return 解出的非负整数
   */
  static int uleb128(byte[] d, int[] cursor) {
    int result = 0;
    int shift = 0;
    int pos = cursor[0];
    while (true) {
      int b = d[pos++] & 0xff;
      result |= (b & 0x7f) << shift;
      if ((b & 0x80) == 0) {
        break;
      }
      shift += 7;
      if (shift > 28) {
        // 防止畸形数据无限循环。
        break;
      }
    }
    cursor[0] = pos;
    return result;
  }

  /** 转小写十六进制字符串。 */
  static String hex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xf, 16));
      sb.append(Character.forDigit(b & 0xf, 16));
    }
    return sb.toString();
  }

  /** 带冒号分隔的大写十六进制指纹（证书 SHA-1/SHA-256 的常见展示形式）。 */
  static String hexColon(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 3);
    for (int i = 0; i < bytes.length; i++) {
      if (i > 0) {
        sb.append(':');
      }
      sb.append(Character.toUpperCase(Character.forDigit((bytes[i] >> 4) & 0xf, 16)));
      sb.append(Character.toUpperCase(Character.forDigit(bytes[i] & 0xf, 16)));
    }
    return sb.toString();
  }

  /** 读满整个流（Java 8 无 {@code readAllBytes}）。 */
  static byte[] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) != -1) {
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  /**
   * 从字节数组中按 UTF-8 解码一段：
   * {@code d[start, end)} 中遇到 {@code 0} 截止（常规 C 字符串）。
   */
  static String utf8z(byte[] d, int start, int end) {
    int p = start;
    while (p < end && d[p] != 0) {
      p++;
    }
    return new String(d, start, p - start, java.nio.charset.StandardCharsets.UTF_8);
  }

  /**
   * 按 DEX 的 **MUTF-8** 解码一段（遇到 0 截止）。
   *
   * <p>与标准 UTF-8 的差异：NUL 编码为 {@code 0xC0 0x80}，增补平面字符（emoji 等）用
   * 一对 3 字节序列表示代理对，而不是 4 字节。直接用标准 UTF-8 解码会把非 BMP 字符
   * 解成 U+FFFD。这里逐段还原成 Java 的 UTF-16 String。
   */
  static String mutf8(byte[] d, int start, int end) {
    StringBuilder sb = new StringBuilder();
    int p = start;
    while (p < end) {
      int a = d[p] & 0xff;
      if (a == 0) {
        break;
      }
      if (a < 0x80) {
        sb.append((char) a);
        p++;
      } else if ((a & 0xe0) == 0xc0) {
        if (p + 1 >= end) {
          break;
        }
        int b = d[p + 1] & 0xff;
        sb.append((char) (((a & 0x1f) << 6) | (b & 0x3f)));
        p += 2;
      } else if ((a & 0xf0) == 0xe0) {
        if (p + 2 >= end) {
          break;
        }
        int b = d[p + 1] & 0xff;
        int c = d[p + 2] & 0xff;
        sb.append((char) (((a & 0x0f) << 12) | ((b & 0x3f) << 6) | (c & 0x3f)));
        p += 3;
      } else {
        sb.append('?');
        p++;
      }
    }
    return sb.toString();
  }
}

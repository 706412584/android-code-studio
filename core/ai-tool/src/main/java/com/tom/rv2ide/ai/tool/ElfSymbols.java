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

import java.util.HashSet;
import java.util.Set;

/**
 * 最小 ELF 符号表读取器：解析 .so 的 .dynsym / .symtab，分出「定义」与「未定义（导入）」符号。
 *
 * <p>用于 native 符号的图书馆级 xref：给定符号名，判断哪些 .so **导出**它、哪些 .so
 * **引用/导入**它。真正的函数级调用点（谁调用了谁）需要反汇编，非本类职责。
 *
 * <p>只处理小端 ELF；ELF32 与 ELF64 都支持。纯 Java 8、零依赖。
 */
final class ElfSymbols {

  static final class Result {
    final Set<String> defined = new HashSet<>();
    final Set<String> undefined = new HashSet<>();
  }

  private ElfSymbols() {}

  /** 解析 ELF 的符号；非 ELF 返回 null。 */
  static Result read(byte[] d) {
    if (d == null || d.length < 52) {
      return null;
    }
    if ((d[0] & 0xff) != 0x7f || d[1] != 'E' || d[2] != 'L' || d[3] != 'F') {
      return null;
    }
    int eiClass = d[4] & 0xff; // 1=32, 2=64
    int eiData = d[5] & 0xff; // 1=LE
    if (eiData != 1) {
      return null;
    }
    boolean is64 = eiClass == 2;
    try {
      Result result = new Result();
      int shoff = is64 ? (int) ParseUtil.u64(d, 0x28) : ParseUtil.u32(d, 0x20);
      int shentsize = ParseUtil.u16(d, is64 ? 0x3a : 0x2e);
      int shnum = ParseUtil.u16(d, is64 ? 0x3c : 0x30);
      int shstrndx = ParseUtil.u16(d, is64 ? 0x3e : 0x32);
      if (shoff <= 0 || shnum <= 0 || shentsize <= 0) {
        return null;
      }
      int shstrOff = sectionOffset(d, shoff, shentsize, shstrndx, is64);
      int shstrSize = sectionSize(d, shoff, shentsize, shstrndx, is64);

      for (int i = 0; i < shnum; i++) {
        int sh = shoff + i * shentsize;
        int type = ParseUtil.u32(d, sh + 4);
        if (type != 2 && type != 11) { // SHT_SYMTAB / SHT_DYNSYM
          continue;
        }
        int symOff = (int) (is64 ? ParseUtil.u64(d, sh + 24) : ParseUtil.u32(d, sh + 16));
        int symSize = (int) (is64 ? ParseUtil.u64(d, sh + 32) : ParseUtil.u32(d, sh + 20));
        int link = ParseUtil.u32(d, is64 ? sh + 40 : sh + 24);
        int entsize = (int) (is64 ? ParseUtil.u64(d, sh + 56) : ParseUtil.u32(d, sh + 36));
        if (entsize <= 0) {
          entsize = is64 ? 24 : 16;
        }
        int strOff = sectionOffset(d, shoff, shentsize, link, is64);
        int strSize = sectionSize(d, shoff, shentsize, link, is64);
        int count = symSize / entsize;
        for (int s = 1; s < count; s++) { // 跳过空符号
          int base = symOff + s * entsize;
          if (base + entsize > d.length) {
            break;
          }
          int nameOff = ParseUtil.u32(d, base);
          int shndx = ParseUtil.u16(d, base + (is64 ? 6 : 14));
          String name = cstr(d, strOff + nameOff, strOff + strSize);
          if (name.isEmpty()) {
            continue;
          }
          if (shndx == 0) {
            result.undefined.add(name);
          } else {
            result.defined.add(name);
          }
        }
      }
      return result;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static int sectionOffset(byte[] d, int shoff, int shentsize, int index, boolean is64) {
    if (index < 0) {
      return 0;
    }
    int sh = shoff + index * shentsize;
    return (int) (is64 ? ParseUtil.u64(d, sh + 24) : ParseUtil.u32(d, sh + 16));
  }

  private static int sectionSize(byte[] d, int shoff, int shentsize, int index, boolean is64) {
    if (index < 0) {
      return 0;
    }
    int sh = shoff + index * shentsize;
    return (int) (is64 ? ParseUtil.u64(d, sh + 32) : ParseUtil.u32(d, sh + 20));
  }

  private static String cstr(byte[] d, int start, int end) {
    if (start < 0 || start >= d.length) {
      return "";
    }
    int e = Math.min(end <= start ? d.length : end, d.length);
    return ParseUtil.utf8z(d, start, e);
  }
}

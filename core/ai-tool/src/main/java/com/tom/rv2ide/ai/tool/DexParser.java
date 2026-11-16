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
 * 最小 DEX 解析器：只做「列类 / 读一个类的字段与方法摘要」，用于不跑 jadx 全量反编译
 * 就能按类名快速探查 APK。
 *
 * <p><b>为什么自己写而不引 smali/dexlib2</b>：本项目 `core/app` 走 `--offline` 构建，
 * dexlib2 不在本地依赖缓存里，加它会让离线构建直接失败；而这里只需要读
 * string/type/field/method/proto/class_def 这几张固定表，不涉及指令流，
 * 自己解析（约两百行）比引入一整个 smali 工具链更可控，也保持纯 Java 8、零依赖。
 *
 * <p>不解析 code 指令、注解、debug 信息——那些交给 jadx。本类只回答
 * 「有哪些类 / 这个类继承谁、有哪些字段和方法、签名是什么」。
 */
final class DexParser {

  private final byte[] d;

  private final int stringIdsSize;
  private final int stringIdsOff;
  private final int typeIdsSize;
  private final int typeIdsOff;
  private final int protoIdsSize;
  private final int protoIdsOff;
  private final int fieldIdsSize;
  private final int fieldIdsOff;
  private final int methodIdsSize;
  private final int methodIdsOff;
  private final int classDefsSize;
  private final int classDefsOff;

  private String[] stringCache;

  /** 一个类成员的摘要（字段或方法）。 */
  static final class Member {
    final String name;
    /** 字段类型或方法签名（Java 可读形式）。 */
    final String type;
    final int accessFlags;

    Member(String name, String type, int accessFlags) {
      this.name = name;
      this.type = type;
      this.accessFlags = accessFlags;
    }
  }

  /** 一个类的结构摘要。 */
  static final class ClassInfo {
    final String javaName;
    final String superName;
    final String sourceFile;
    final int accessFlags;
    final List<String> interfaces = new ArrayList<>();
    final List<Member> fields = new ArrayList<>();
    final List<Member> methods = new ArrayList<>();
    boolean isInterface;

    ClassInfo(String javaName, String superName, String sourceFile, int accessFlags) {
      this.javaName = javaName;
      this.superName = superName;
      this.sourceFile = sourceFile;
      this.accessFlags = accessFlags;
    }
  }

  private DexParser(byte[] data) {
    this.d = data;
    // header 固定 0x70 字节，偏移见 DEX 规范。
    this.stringIdsSize = ParseUtil.u32(d, 56);
    this.stringIdsOff = ParseUtil.u32(d, 60);
    this.typeIdsSize = ParseUtil.u32(d, 64);
    this.typeIdsOff = ParseUtil.u32(d, 68);
    this.protoIdsSize = ParseUtil.u32(d, 72);
    this.protoIdsOff = ParseUtil.u32(d, 76);
    this.fieldIdsSize = ParseUtil.u32(d, 80);
    this.fieldIdsOff = ParseUtil.u32(d, 84);
    this.methodIdsSize = ParseUtil.u32(d, 88);
    this.methodIdsOff = ParseUtil.u32(d, 92);
    this.classDefsSize = ParseUtil.u32(d, 96);
    this.classDefsOff = ParseUtil.u32(d, 100);
  }

  /** 是否为 DEX 文件：magic 以 "dex\n" 开头。 */
  static boolean looksLikeDex(byte[] data) {
    return data != null
        && data.length > 0x70
        && data[0] == 'd'
        && data[1] == 'e'
        && data[2] == 'x'
        && data[3] == '\n';
  }

  /** 构造解析器；非 DEX 返回 null。 */
  static DexParser open(byte[] data) {
    if (!looksLikeDex(data)) {
      return null;
    }
    try {
      DexParser parser = new DexParser(data);
      // 基本健全性检查，避免把畸形数据当 DEX。
      if (parser.stringIdsOff < 0
          || parser.stringIdsOff + parser.stringIdsSize * 4L > data.length
          || parser.classDefsOff + parser.classDefsSize * 32L > data.length) {
        return null;
      }
      return parser;
    } catch (RuntimeException e) {
      return null;
    }
  }

  int classCount() {
    return classDefsSize;
  }

  /** 第 i 个 class_def 的类描述符（如 {@code Lcom/foo/Bar;}）。 */
  String classDescriptor(int i) {
    int classIdx = ParseUtil.u32(d, classDefsOff + i * 32);
    return descriptor(classIdx);
  }

  /** 遍历所有类描述符。 */
  List<String> classDescriptors() {
    List<String> out = new ArrayList<>(classDefsSize);
    for (int i = 0; i < classDefsSize; i++) {
      try {
        out.add(classDescriptor(i));
      } catch (RuntimeException ignored) {
        // 畸形条目跳过。
      }
    }
    return out;
  }

  /** 字符串表中所有字符串（用于包内搜索的 dex 字符串目标）。 */
  List<String> allStrings() {
    List<String> out = new ArrayList<>(stringIdsSize);
    for (int i = 0; i < stringIdsSize; i++) {
      try {
        out.add(string(i));
      } catch (RuntimeException ignored) {
        // 跳过畸形字符串。
      }
    }
    return out;
  }

  String string(int idx) {
    if (idx < 0 || idx >= stringIdsSize) {
      return "";
    }
    if (stringCache == null) {
      stringCache = new String[stringIdsSize];
    }
    String cached = stringCache[idx];
    if (cached != null) {
      return cached;
    }
    int off = ParseUtil.u32(d, stringIdsOff + idx * 4);
    int[] cursor = {off};
    ParseUtil.uleb128(d, cursor); // 跳过 utf16 长度前缀
    String value = ParseUtil.mutf8(d, cursor[0], d.length);
    stringCache[idx] = value;
    return value;
  }

  /** type_id → 类型描述符（{@code I}、{@code Ljava/lang/String;} 等）。 */
  String descriptor(int typeIdx) {
    if (typeIdx < 0 || typeIdx >= typeIdsSize) {
      return "";
    }
    return string(ParseUtil.u32(d, typeIdsOff + typeIdx * 4));
  }

  /** 按 Java 名（{@code com.foo.Bar}）或描述符（{@code Lcom/foo/Bar;}）查找类。 */
  ClassInfo classInfo(String nameOrDescriptor) {
    if (nameOrDescriptor == null || nameOrDescriptor.isEmpty()) {
      return null;
    }
    String wanted = nameOrDescriptor.trim();
    String wantedDescriptor =
        (wanted.startsWith("L") && wanted.endsWith(";"))
            ? wanted
            : "L" + wanted.replace('.', '/') + ";";
    for (int i = 0; i < classDefsSize; i++) {
      try {
        int classIdx = ParseUtil.u32(d, classDefsOff + i * 32);
        String desc = descriptor(classIdx);
        if (wantedDescriptor.equals(desc) || javaName(desc).equals(wanted)) {
          return readClassDef(i);
        }
      } catch (RuntimeException ignored) {
        // 继续找下一个。
      }
    }
    return null;
  }

  private ClassInfo readClassDef(int i) {
    int base = classDefsOff + i * 32;
    int classIdx = ParseUtil.u32(d, base);
    int accessFlags = ParseUtil.u32(d, base + 4);
    int superclassIdx = ParseUtil.u32(d, base + 8);
    int interfacesOff = ParseUtil.u32(d, base + 12);
    int sourceFileIdx = ParseUtil.u32(d, base + 16);
    int classDataOff = ParseUtil.u32(d, base + 24);

    String javaName = javaName(descriptor(classIdx));
    // 只有 0xFFFFFFFF 表示「无父类」；0 是合法的 type 下标，不能当哨兵。
    String superName =
        (superclassIdx == 0xFFFFFFFF) ? "" : javaName(descriptor(superclassIdx));
    String sourceFile = sourceFileIdx == 0xFFFFFFFF ? "" : string(sourceFileIdx);
    ClassInfo info = new ClassInfo(javaName, superName, sourceFile, accessFlags);
    info.isInterface = (accessFlags & 0x0200) != 0; // ACC_INTERFACE

    if (interfacesOff != 0) {
      int count = ParseUtil.u32(d, interfacesOff);
      for (int k = 0; k < count; k++) {
        int typeIdx = ParseUtil.u16(d, interfacesOff + 4 + k * 2);
        info.interfaces.add(javaName(descriptor(typeIdx)));
      }
    }

    if (classDataOff != 0) {
      readClassData(classDataOff, info);
    }
    return info;
  }

  private void readClassData(int off, ClassInfo info) {
    int[] cursor = {off};
    int staticFields = ParseUtil.uleb128(d, cursor);
    int instanceFields = ParseUtil.uleb128(d, cursor);
    int directMethods = ParseUtil.uleb128(d, cursor);
    int virtualMethods = ParseUtil.uleb128(d, cursor);
    readFields(cursor, staticFields, true, info);
    readFields(cursor, instanceFields, false, info);
    readMethods(cursor, directMethods, true, info);
    readMethods(cursor, virtualMethods, false, info);
  }

  private void readFields(int[] cursor, int count, boolean isStatic, ClassInfo info) {
    int fieldIdx = 0;
    for (int k = 0; k < count; k++) {
      fieldIdx += ParseUtil.uleb128(d, cursor);
      int accessFlags = ParseUtil.uleb128(d, cursor);
      if (fieldIdx >= 0 && fieldIdx < fieldIdsSize) {
        int fieldBase = fieldIdsOff + fieldIdx * 8;
        int typeIdx = ParseUtil.u16(d, fieldBase + 2);
        int nameIdx = ParseUtil.u32(d, fieldBase + 4);
        info.fields.add(
            new Member(string(nameIdx), javaType(descriptor(typeIdx)),
                isStatic ? (accessFlags | 0x0008) : accessFlags));
      }
    }
  }

  private void readMethods(int[] cursor, int count, boolean direct, ClassInfo info) {
    int methodIdx = 0;
    for (int k = 0; k < count; k++) {
      methodIdx += ParseUtil.uleb128(d, cursor);
      int accessFlags = ParseUtil.uleb128(d, cursor);
      ParseUtil.uleb128(d, cursor); // code_off，本工具不读指令
      if (methodIdx >= 0 && methodIdx < methodIdsSize) {
        int methodBase = methodIdsOff + methodIdx * 8;
        int protoIdx = ParseUtil.u16(d, methodBase + 2);
        int nameIdx = ParseUtil.u32(d, methodBase + 4);
        // 注意：direct 方法并不等于静态方法（还含 private 与构造器），不能据此补 static 位。
        info.methods.add(new Member(string(nameIdx), methodSignature(protoIdx), accessFlags));
      }
    }
  }

  private String methodSignature(int protoIdx) {
    if (protoIdx < 0 || protoIdx >= protoIdsSize) {
      return "()";
    }
    int base = protoIdsOff + protoIdx * 12;
    int returnTypeIdx = ParseUtil.u32(d, base + 4);
    int parametersOff = ParseUtil.u32(d, base + 8);
    StringBuilder sb = new StringBuilder("(");
    if (parametersOff != 0) {
      int count = ParseUtil.u32(d, parametersOff);
      for (int k = 0; k < count; k++) {
        int typeIdx = ParseUtil.u16(d, parametersOff + 4 + k * 2);
        sb.append(javaType(descriptor(typeIdx)));
      }
    }
    sb.append("):").append(javaType(descriptor(returnTypeIdx)));
    return sb.toString();
  }

  /**
   * 扫描所有方法体，找出引用了「匹配 query 的方法/字段」的调用点（xref）。
   *
   * <p><b>为什么需要指令宽度表</b>：要遍历 code_item 的指令流提取 invoke 与 field 操作数，
   * 必须知道每条指令占几个 16 位单元，否则会错位、给出假引用。这里是 DEX 规范里各
   * opcode 的宽度（单位：code unit），未列出的按 1。
   */
  private static final int[] INSN_WIDTH = new int[256];

  static {
    java.util.Arrays.fill(INSN_WIDTH, 1);
    for (int i = 0x32; i <= 0x3d; i++) {
      INSN_WIDTH[i] = 2; // if-eq..if-lez
    }
    for (int i = 0x44; i <= 0x51; i++) {
      INSN_WIDTH[i] = 2; // aget/aput
    }
    for (int i = 0x52; i <= 0x6d; i++) {
      INSN_WIDTH[i] = 2; // iget/iput/sget/sput
    }
    for (int i = 0x90; i <= 0xaf; i++) {
      INSN_WIDTH[i] = 2; // int/long/float/double 二元运算
    }
    for (int i = 0xd0; i <= 0xe2; i++) {
      INSN_WIDTH[i] = 2; // lit16 / lit8
    }
    int[] w2 = {0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c, 0x1f, 0x20,
        0x22, 0x23, 0x29, 0x2d, 0x2e, 0x2f, 0x30, 0x31, 0xfe, 0xff};
    for (int v : w2) {
      INSN_WIDTH[v] = 2;
    }
    int[] w3 = {0x03, 0x06, 0x09, 0x14, 0x17, 0x1b, 0x24, 0x25, 0x26, 0x2a, 0x2b, 0x2c,
        0x6e, 0x6f, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78, 0xfc, 0xfd};
    for (int v : w3) {
      INSN_WIDTH[v] = 3;
    }
    INSN_WIDTH[0xfa] = 4; // invoke-polymorphic
    INSN_WIDTH[0xfb] = 4; // invoke-polymorphic/range
    INSN_WIDTH[0x18] = 5; // const-wide
  }

  /**
   * 反向引用扫描：把引用了「方法/字段引用串包含 query」的调用点收集到 out。
   *
   * @param wantMethods 是否匹配方法引用（invoke-*）
   * @param wantFields  是否匹配字段引用（iget/iput/sget/sput）
   */
  void xref(
      String query,
      boolean caseSensitive,
      boolean wantMethods,
      boolean wantFields,
      List<String> out,
      int limit) {
    if (query == null || query.isEmpty()) {
      return;
    }
    String needle = caseSensitive ? query : query.toLowerCase(java.util.Locale.ROOT);
    for (int i = 0; i < classDefsSize && out.size() < limit; i++) {
      int classDataOff = ParseUtil.u32(d, classDefsOff + i * 32 + 24);
      if (classDataOff == 0) {
        continue;
      }
      try {
        scanClassData(classDataOff, needle, caseSensitive, wantMethods, wantFields, out, limit);
      } catch (RuntimeException ignored) {
        // 跳过畸形类。
      }
    }
  }

  private void scanClassData(
      int off,
      String needle,
      boolean cs,
      boolean wantM,
      boolean wantF,
      List<String> out,
      int limit) {
    int[] cur = {off};
    int staticFields = ParseUtil.uleb128(d, cur);
    int instanceFields = ParseUtil.uleb128(d, cur);
    int directMethods = ParseUtil.uleb128(d, cur);
    int virtualMethods = ParseUtil.uleb128(d, cur);
    for (int k = 0; k < staticFields + instanceFields; k++) {
      ParseUtil.uleb128(d, cur); // field_idx_diff
      ParseUtil.uleb128(d, cur); // access_flags
    }
    scanMethodBodies(cur, directMethods, needle, cs, wantM, wantF, out, limit);
    scanMethodBodies(cur, virtualMethods, needle, cs, wantM, wantF, out, limit);
  }

  private void scanMethodBodies(
      int[] cur,
      int count,
      String needle,
      boolean cs,
      boolean wantM,
      boolean wantF,
      List<String> out,
      int limit) {
    int methodIdx = 0;
    for (int k = 0; k < count && out.size() < limit; k++) {
      methodIdx += ParseUtil.uleb128(d, cur);
      ParseUtil.uleb128(d, cur); // access_flags
      int codeOff = ParseUtil.uleb128(d, cur);
      if (codeOff != 0) {
        scanCode(codeOff, methodIdx, needle, cs, wantM, wantF, out, limit);
      }
    }
  }

  private void scanCode(
      int codeOff,
      int callerMethodIdx,
      String needle,
      boolean cs,
      boolean wantM,
      boolean wantF,
      List<String> out,
      int limit) {
    if (codeOff + 16 > d.length) {
      return;
    }
    long declared = ParseUtil.u32(d, codeOff + 12) & 0xffffffffL;
    int base = codeOff + 16;
    long avail = (d.length - base) / 2L;
    int insnsSize = (int) Math.min(declared, avail);
    int pc = 0;
    while (pc < insnsSize && out.size() < limit) {
      int op = ParseUtil.u16(d, base + pc * 2) & 0xff;
      if (wantF && op >= 0x52 && op <= 0x6d) {
        int p = pc + 1;
        if (p < insnsSize) {
          int fieldIdx = ParseUtil.u16(d, base + p * 2);
          checkRef(fieldRef(fieldIdx), callerMethodIdx, needle, cs, out, limit);
        }
      } else if (wantM
          && ((op >= 0x6e && op <= 0x72)
              || (op >= 0x74 && op <= 0x78)
              || op == 0xfa
              || op == 0xfb)) {
        // 35c/3rc/45cc/4rcc 的 method@BBBB 都紧跟操作码所在的 unit（pc+1），不是 pc+2。
        int p = pc + 1;
        if (p < insnsSize) {
          int mIdx = ParseUtil.u16(d, base + p * 2);
          checkRef(methodRef(mIdx), callerMethodIdx, needle, cs, out, limit);
        }
      }
      int w = INSN_WIDTH[op];
      pc += w <= 0 ? 1 : w;
    }
  }

  private void checkRef(
      String ref, int callerMethodIdx, String needle, boolean cs, List<String> out, int limit) {
    if (ref == null || out.size() >= limit) {
      return;
    }
    String hay = cs ? ref : ref.toLowerCase(java.util.Locale.ROOT);
    if (hay.contains(needle)) {
      out.add(methodRef(callerMethodIdx) + "  →  " + ref);
    }
  }

  private String methodRef(int idx) {
    if (idx < 0 || idx >= methodIdsSize) {
      return null;
    }
    int base = methodIdsOff + idx * 8;
    int classIdx = ParseUtil.u16(d, base);
    int protoIdx = ParseUtil.u16(d, base + 2);
    int nameIdx = ParseUtil.u32(d, base + 4);
    return descriptor(classIdx) + "->" + string(nameIdx) + methodSignature(protoIdx);
  }

  private String fieldRef(int idx) {
    if (idx < 0 || idx >= fieldIdsSize) {
      return null;
    }
    int base = fieldIdsOff + idx * 8;
    int classIdx = ParseUtil.u16(d, base);
    int typeIdx = ParseUtil.u16(d, base + 2);
    int nameIdx = ParseUtil.u32(d, base + 4);
    return descriptor(classIdx) + "->" + string(nameIdx) + ":" + descriptor(typeIdx);
  }

  /** 类型描述符 → Java 可读形式（数组递归；原始类型展开）。 */
  static String javaType(String descriptor) {
    if (descriptor == null || descriptor.isEmpty()) {
      return "";
    }
    int dims = 0;
    int i = 0;
    while (i < descriptor.length() && descriptor.charAt(i) == '[') {
      dims++;
      i++;
    }
    String base = descriptor.substring(i);
    String name;
    if (base.startsWith("L") && base.endsWith(";")) {
      name = base.substring(1, base.length() - 1).replace('/', '.');
    } else if (base.length() == 1) {
      name = primitive(base.charAt(0));
    } else {
      name = base;
    }
    StringBuilder sb = new StringBuilder(name);
    for (int k = 0; k < dims; k++) {
      sb.append("[]");
    }
    return sb.toString();
  }

  private static String primitive(char c) {
    switch (c) {
      case 'V':
        return "void";
      case 'Z':
        return "boolean";
      case 'B':
        return "byte";
      case 'S':
        return "short";
      case 'C':
        return "char";
      case 'I':
        return "int";
      case 'J':
        return "long";
      case 'F':
        return "float";
      case 'D':
        return "double";
      default:
        return String.valueOf(c);
    }
  }

  /** 类描述符 → Java 名（{@code Lcom/foo/Bar;} → {@code com.foo.Bar}）。 */
  static String javaName(String descriptor) {
    if (descriptor == null || descriptor.isEmpty()) {
      return "";
    }
    if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
      return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }
    return javaType(descriptor);
  }
}

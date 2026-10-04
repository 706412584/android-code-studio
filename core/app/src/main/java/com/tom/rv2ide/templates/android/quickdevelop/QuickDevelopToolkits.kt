/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.templates.android.quickdevelop

/**
 * Quick Develop 模板的**工具库**源码文本（生成到 `<packageId>.tool`）。
 *
 * <h3>与 Appv5 的关系</h3>
 *
 * 对应 Appv5 `runlibrary/app/` 下的五个「根组件」：
 * `字符`(zf)、`文件`(wj)、`数据`(sj)、`工具`(gj)、`系统`(xt)。
 * Appv5 通过 `iapp` 门面把这几个模块挂成字段（`iapp.zf` / `iapp.wj` / …）。
 *
 * <p><b>为什么不搬 Appv5 的代码</b>：已验证过三条硬理由——
 * ① 基类 `VC` 依赖 `ClientsUDP.a`，而该方法在仓库里已丢失（`s.java:82,105` 调用它），
 * 整包无法编译；② 反编译破损（`an.java` 里 `st` 与 `f94` 同指一个 Button 却都声明）；
 * ③ 仓库没有构建文件/manifest/res/gradlew。
 * 因此这里只复用**模块划分与中文方法名**，实现全部按 Android 官方 API 重写。
 *
 * <h3>与 `ui/` 的分工</h3>
 *
 * `ui/` 是视图层（45 个控件），`tool/` 是非视图能力（字符串/文件/数据库/媒体/系统）。
 * 两者都生成进用户工程，供用户搭 app 时直接调用。
 */
object QuickDevelopToolkits {

  /** 工具类所在子包的包名后缀。最终包名为 `<packageId>.tool`。 */
  private const val TOOL_PACKAGE_SUFFIX = "tool"

  private fun toolPackage(packageId: String): String = "$packageId.$TOOL_PACKAGE_SUFFIX"

  /**
   * 生成 `字符`（字符串 / 正则 / JSON）。
   *
   * <p>对应 Appv5 的 `zf` 与 `字符`——二者是同一个模块（前者是混淆名，方法完全一致）。
   * 全部是静态方法，不持有 Context。
   */
  fun stringKit(packageId: String): String =
      """
      package ${toolPackage(packageId)};

      import org.json.JSONArray;
      import org.json.JSONObject;
      import java.util.regex.Matcher;
      import java.util.regex.Pattern;

      /**
       * 字符：字符串 / 正则 / JSON 的常用操作。
       *
       * <p>所有方法对 {@code null} 入参都安全（返回空串/0/false），调用方不必判空。
       */
      public final class 字符 {

          private 字符() {}

          // ---------------- 字符串 ----------------

          public static int 长度(CharSequence s) {
              return s == null ? 0 : s.length();
          }

          /** 非空且长度大于 0。 */
          public static boolean 存在(CharSequence s) {
              return s != null && s.length() > 0;
          }

          public static String 转换大写(String s) {
              return s == null ? "" : s.toUpperCase();
          }

          public static String 转换小写(String s) {
              return s == null ? "" : s.toLowerCase();
          }

          public static String 去除头尾空白(String s) {
              return s == null ? "" : s.trim();
          }

          /** 把第一个 {@code from} 换成 {@code to}（字面量，不按正则）。 */
          public static String 替换(String s, String from, String to) {
              if (s == null || from == null || from.isEmpty()) {
                  return s == null ? "" : s;
              }
              int i = s.indexOf(from);
              if (i < 0) {
                  return s;
              }
              return s.substring(0, i) + (to == null ? "" : to) + s.substring(i + from.length());
          }

          /** 按正则全局替换。 */
          public static String 替换全部(String s, String regex, String to) {
              if (s == null || regex == null) {
                  return s == null ? "" : s;
              }
              return s.replaceAll(regex, to == null ? "" : to);
          }

          /** 按正则分割。 */
          public static String[] 分割(String s, String regex) {
              if (s == null || regex == null) {
                  return new String[0];
              }
              return s.split(regex);
          }

          /** 返回子串首次出现的位置，找不到返回 -1。 */
          public static int 查询(String s, String sub) {
              if (s == null || sub == null) {
                  return -1;
              }
              return s.indexOf(sub);
          }

          /** 返回子串最后一次出现的位置，找不到返回 -1。 */
          public static int 倒查(String s, String sub) {
              if (s == null || sub == null) {
                  return -1;
              }
              return s.lastIndexOf(sub);
          }

          public static boolean 查开头(String s, String prefix) {
              return s != null && prefix != null && s.startsWith(prefix);
          }

          public static boolean 查结尾(String s, String suffix) {
              return s != null && suffix != null && s.endsWith(suffix);
          }

          public static boolean 等于(String a, String b) {
              return a == null ? b == null : a.equals(b);
          }

          /** 取 [start, end) 区间的子串，越界时收敛到合法范围。 */
          public static String 取出(String s, int start, int end) {
              if (s == null) {
                  return "";
              }
              int from = Math.max(0, Math.min(start, s.length()));
              int to = Math.max(from, Math.min(end, s.length()));
              return s.substring(from, to);
          }

          /** [0, max) 区间内的随机整数。 */
          public static int 随机数(int max) {
              return max <= 0 ? 0 : (int) (Math.random() * max);
          }

          // ---------------- 正则 ----------------

          /** 整串是否能被正则匹配到（部分匹配，等价于 {@code find()}）。 */
          public static boolean 是否匹配成功(String s, String regex) {
              if (s == null || regex == null) {
                  return false;
              }
              try {
                  return Pattern.compile(regex).matcher(s).find();
              } catch (RuntimeException e) {
                  return false;
              }
          }

          /** 返回首个匹配到的整段文本，没有则返回空串。 */
          public static String 匹配(String s, String regex) {
              if (s == null || regex == null) {
                  return "";
              }
              try {
                  Matcher m = Pattern.compile(regex).matcher(s);
                  return m.find() ? m.group() : "";
              } catch (RuntimeException e) {
                  return "";
              }
          }

          /** 取首个匹配中第 {@code index} 个分组，越界返回空串。 */
          public static String 匹配组(String s, String regex, int index) {
              if (s == null || regex == null) {
                  return "";
              }
              try {
                  Matcher m = Pattern.compile(regex).matcher(s);
                  if (m.find() && index >= 0 && index <= m.groupCount()) {
                      String g = m.group(index);
                      return g == null ? "" : g;
                  }
              } catch (RuntimeException ignored) {
                  // 正则非法或分组越界：返回空串，不抛给调用方
              }
              return "";
          }

          // ---------------- JSON ----------------

          /** 解析 JSON 对象；失败返回 {@code null}。 */
          public static JSONObject json解析(String s) {
              if (s == null || s.trim().isEmpty()) {
                  return null;
              }
              try {
                  return new JSONObject(s);
              } catch (Exception e) {
                  return null;
              }
          }

          /** 解析 JSON 数组；失败返回 {@code null}。 */
          public static JSONArray json数组解析(String s) {
              if (s == null || s.trim().isEmpty()) {
                  return null;
              }
              try {
                  return new JSONArray(s);
              } catch (Exception e) {
                  return null;
              }
          }

          /** 读 JSON 里的字符串字段，缺失时返回 {@code fallback}。 */
          public static String json取字符串(JSONObject o, String key, String fallback) {
              if (o == null || key == null) {
                  return fallback;
              }
              return o.optString(key, fallback);
          }
      }
      """
          .trimIndent() + "\n"

  /**
   * 生成 `文件`（文件读写 / 目录 / 压缩）。
   *
   * <p>对应 Appv5 的 `wj`（`文件.java`，133 行）——那是五个模块里最干净的：
   * 只 import `java.io.File`，无 Android 依赖。这里保留其方法划分，实现重写。
   */
  fun fileKit(packageId: String): String =
      """
      package ${toolPackage(packageId)};

      import android.content.Context;
      import android.content.Intent;
      import android.net.Uri;
      import android.os.Build;
      import android.webkit.MimeTypeMap;
      import java.io.File;
      import java.io.FileInputStream;
      import java.io.FileOutputStream;
      import java.io.IOException;
      import java.io.InputStream;
      import java.io.OutputStream;
      import java.nio.charset.StandardCharsets;
      import java.util.ArrayList;
      import java.util.List;
      import java.util.zip.ZipEntry;
      import java.util.zip.ZipInputStream;
      import java.util.zip.ZipOutputStream;

      /**
       * 文件：路径判断 / 读写 / 目录遍历 / 复制转移 / 压缩解压。
       *
       * <p>方法名取自 Appv5 的 {@code 文件}(wj) 模块。全部静态方法。
       */
      public final class 文件 {

          private 文件() {}

          /** 路径是否存在。 */
          public static boolean 存在(String path) {
              return path != null && new File(path).exists();
          }

          /** 用路径构造 File（不创建）。 */
          public static File 文件(String path) {
              return new File(path);
          }

          /** 是否是目录。 */
          public static boolean 目录(String path) {
              return path != null && new File(path).isDirectory();
          }

          public static long 大小(String path) {
              if (path == null) {
                  return 0L;
              }
              File f = new File(path);
              return f.isFile() ? f.length() : 目录大小(f);
          }

          private static long 目录大小(File dir) {
              if (dir == null || !dir.isDirectory()) {
                  return 0L;
              }
              File[] children = dir.listFiles();
              if (children == null) {
                  return 0L;
              }
              long total = 0L;
              for (File c : children) {
                  total += c.isDirectory() ? 目录大小(c) : c.length();
              }
              return total;
          }

          /** 扩展名（不含点），无扩展名返回空串。 */
          public static String 类型(String path) {
              if (path == null) {
                  return "";
              }
              String name = new File(path).getName();
              int dot = name.lastIndexOf('.');
              return dot < 0 ? "" : name.substring(dot + 1);
          }

          /** 目录下的直接子项名字；不是目录时返回空数组。 */
          public static String[] 列表(String dir) {
              if (dir == null) {
                  return new String[0];
              }
              File[] children = new File(dir).listFiles();
              if (children == null) {
                  return new String[0];
              }
              String[] names = new String[children.length];
              for (int i = 0; i < children.length; i++) {
                  names[i] = children[i].getName();
              }
              return names;
          }

          /** 在目录下按文件名递归查找，返回首个命中的绝对路径；找不到返回空串。 */
          public static String 查找文件(String dir, String name) {
              if (dir == null || name == null) {
                  return "";
              }
              File found = 查找(new File(dir), name, 0);
              return found == null ? "" : found.getAbsolutePath();
          }

          private static File 查找(File dir, String name, int depth) {
              if (dir == null || !dir.isDirectory() || depth > 12) {
                  return null;
              }
              File[] children = dir.listFiles();
              if (children == null) {
                  return null;
              }
              for (File c : children) {
                  if (c.isFile() && name.equals(c.getName())) {
                      return c;
                  }
              }
              for (File c : children) {
                  File hit = 查找(c, name, depth + 1);
                  if (hit != null) {
                      return hit;
                  }
              }
              return null;
          }

          /** 读取文本文件（UTF-8）；失败返回空串。 */
          public static String 读取文本(String path) {
              byte[] data = 读取字节数组(path);
              return data.length == 0 ? "" : new String(data, StandardCharsets.UTF_8);
          }

          /** 写入文本（UTF-8），父目录不存在时自动创建。 */
          public static boolean 写入文本(String path, String text) {
              return 写入字节数组(path, (text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
          }

          /** 读取全部字节；失败返回空数组。 */
          public static byte[] 读取字节数组(String path) {
              if (path == null) {
                  return new byte[0];
              }
              File f = new File(path);
              if (!f.isFile()) {
                  return new byte[0];
              }
              try (FileInputStream in = new FileInputStream(f)) {
                  byte[] buf = new byte[(int) f.length()];
                  int read = 0;
                  while (read < buf.length) {
                      int n = in.read(buf, read, buf.length - read);
                      if (n < 0) {
                          break;
                      }
                      read += n;
                  }
                  if (read == buf.length) {
                      return buf;
                  }
                  byte[] trimmed = new byte[read];
                  System.arraycopy(buf, 0, trimmed, 0, read);
                  return trimmed;
              } catch (IOException e) {
                  return new byte[0];
              }
          }

          /** 写入全部字节，父目录不存在时自动创建。 */
          public static boolean 写入字节数组(String path, byte[] data) {
              if (path == null || data == null) {
                  return false;
              }
              File f = new File(path);
              File parent = f.getParentFile();
              if (parent != null && !parent.exists() && !parent.mkdirs()) {
                  return false;
              }
              try (FileOutputStream out = new FileOutputStream(f)) {
                  out.write(data);
                  out.flush();
                  return true;
              } catch (IOException e) {
                  return false;
              }
          }

          /** 复制文件或目录（目录递归）。 */
          public static boolean 复制(String src, String dst) {
              if (src == null || dst == null) {
                  return false;
              }
              return 复制(new File(src), new File(dst), 0);
          }

          private static boolean 复制(File src, File dst, int depth) {
              if (src == null || !src.exists() || depth > 20) {
                  return false;
              }
              if (src.isDirectory()) {
                  if (!dst.exists() && !dst.mkdirs()) {
                      return false;
                  }
                  File[] children = src.listFiles();
                  if (children != null) {
                      for (File c : children) {
                          if (!复制(c, new File(dst, c.getName()), depth + 1)) {
                              return false;
                          }
                      }
                  }
                  return true;
              }
              File parent = dst.getParentFile();
              if (parent != null && !parent.exists() && !parent.mkdirs()) {
                  return false;
              }
              try (InputStream in = new FileInputStream(src);
                      OutputStream out = new FileOutputStream(dst)) {
                  return 搬运(in, out);
              } catch (IOException e) {
                  return false;
              }
          }

          /** 转移（先复制再删源）；失败返回 false。 */
          public static boolean 转移(String src, String dst) {
              if (!复制(src, dst)) {
                  return false;
              }
              return 删除(src);
          }

          /** 删除文件或目录（目录递归）。 */
          public static boolean 删除(String path) {
              if (path == null) {
                  return false;
              }
              return 删除(new File(path), 0);
          }

          private static boolean 删除(File f, int depth) {
              if (f == null || !f.exists() || depth > 20) {
                  return false;
              }
              if (f.isDirectory()) {
                  File[] children = f.listFiles();
                  if (children != null) {
                      for (File c : children) {
                          删除(c, depth + 1);
                      }
                  }
              }
              return f.delete();
          }

          /** 父目录路径；没有父目录时返回空串。 */
          public static String 获取目录(String path) {
              if (path == null) {
                  return "";
              }
              File parent = new File(path).getParentFile();
              return parent == null ? "" : parent.getAbsolutePath();
          }

          /** 把文件/目录压缩为 zip。 */
          public static boolean 压缩(String src, String zipPath) {
              if (src == null || zipPath == null) {
                  return false;
              }
              File srcFile = new File(src);
              if (!srcFile.exists()) {
                  return false;
              }
              File parent = new File(zipPath).getParentFile();
              if (parent != null && !parent.exists() && !parent.mkdirs()) {
                  return false;
              }
              try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath))) {
                  String base = srcFile.getParentFile() == null ? "" : srcFile.getParentFile().getAbsolutePath();
                  压缩(zos, srcFile, base, 0);
                  return true;
              } catch (IOException e) {
                  return false;
              }
          }

          private static void 压缩(ZipOutputStream zos, File f, String base, int depth)
                  throws IOException {
              if (f == null || depth > 20) {
                  return;
              }
              String entryName =
                      base.isEmpty() ? f.getName() : f.getAbsolutePath().substring(base.length() + 1);
              if (f.isDirectory()) {
                  zos.putNextEntry(new ZipEntry(entryName + "/"));
                  zos.closeEntry();
                  File[] children = f.listFiles();
                  if (children != null) {
                      for (File c : children) {
                          压缩(zos, c, base, depth + 1);
                      }
                  }
              } else {
                  zos.putNextEntry(new ZipEntry(entryName));
                  try (InputStream in = new FileInputStream(f)) {
                      搬运(in, zos);
                  }
                  zos.closeEntry();
              }
          }

          /** 解压 zip 到目录。 */
          public static boolean 解压(String zipPath, String dstDir) {
              if (zipPath == null || dstDir == null) {
                  return false;
              }
              File dir = new File(dstDir);
              if (!dir.exists() && !dir.mkdirs()) {
                  return false;
              }
              String canonicalDir;
              try {
                  canonicalDir = dir.getCanonicalPath();
              } catch (IOException e) {
                  return false;
              }
              try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipPath))) {
                  ZipEntry entry;
                  while ((entry = zis.getNextEntry()) != null) {
                      File out = new File(dir, entry.getName());
                      // 防 zip slip：解出的路径必须仍在目标目录内
                      if (!out.getCanonicalPath().startsWith(canonicalDir)) {
                          return false;
                      }
                      if (entry.isDirectory()) {
                          out.mkdirs();
                      } else {
                          File p = out.getParentFile();
                          if (p != null && !p.exists()) {
                              p.mkdirs();
                          }
                          try (OutputStream os = new FileOutputStream(out)) {
                              搬运(zis, os);
                          }
                      }
                      zis.closeEntry();
                  }
                  return true;
              } catch (IOException e) {
                  return false;
              }
          }

          /**
           * 用系统默认应用打开文件（按扩展名推断 MIME）。
           *
           * <p><b>仅适用于 Android 7.0（API 24）以下</b>，成功返回 true。
           * API 24 起 {@code Uri.fromFile} 会抛 {@code FileUriExposedException}——
           * 把 {@code file://} 交给别的应用属于「泄露文件路径」，系统直接禁止。
           *
           * <p>因此本方法在 API 24+ <b>直接返回 false</b>，不做无用尝试。
           * 想在更高版本上打开文件，宿主应用必须：
           * <ol>
           *   <li>在 manifest 声明 {@code androidx.core.content.FileProvider}
           *   <li>写 {@code res/xml/file_paths.xml} 指定可共享目录
           *   <li>改用 {@code FileProvider.getUriForFile(...)} 得到 {@code content://} URI
           * </ol>
           * 这三步涉及 manifest 与资源，超出「一个工具类」的范围，故不在此实现。
           */
          public static boolean 打开(Context context, String path) {
              if (context == null || path == null || Build.VERSION.SDK_INT >= 24) {
                  return false;
              }
              File f = new File(path);
              if (!f.exists()) {
                  return false;
              }
              String ext = 类型(path).toLowerCase();
              String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
              Intent intent = new Intent(Intent.ACTION_VIEW);
              intent.setDataAndType(Uri.fromFile(f), mime == null ? "*/*" : mime);
              intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
              try {
                  context.startActivity(intent);
                  return true;
              } catch (RuntimeException e) {
                  return false;
              }
          }

          private static boolean 搬运(InputStream in, OutputStream out) throws IOException {
              byte[] buf = new byte[8192];
              int n;
              while ((n = in.read(buf)) > 0) {
                  out.write(buf, 0, n);
              }
              return true;
          }
      }
      """
          .trimIndent() + "\n"

  /**
   * 生成 `数据`（SQLite + 类型/单位转换）。
   *
   * <p>对应 Appv5 的 `sj`（`数据.java`，753 行）。那个文件是混合体：数据库封装 +
   * 类型转换 + 列表适配器助手。这里只取前两类——适配器助手属于视图层，
   * 已由 `ui/` 的控件承担。
   */
  fun dataKit(packageId: String): String =
      """
      package ${toolPackage(packageId)};

      import android.content.Context;
      import android.database.Cursor;
      import android.database.sqlite.SQLiteDatabase;
      import android.util.DisplayMetrics;
      import android.util.TypedValue;
      import java.util.ArrayList;
      import java.util.LinkedHashMap;
      import java.util.List;
      import java.util.Map;

      /**
       * 数据：SQLite 数据库操作 + 类型/单位转换。
       *
       * <p>方法名取自 Appv5 的 {@code 数据}(sj) 模块。
       */
      public final class 数据 {

          private 数据() {}

          // ---------------- 单位转换 ----------------

          public static int dp转px(Context context, float dp) {
              return Math.round(
                      TypedValue.applyDimension(
                              TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics()));
          }

          public static float px转dp(Context context, float px) {
              return px / context.getResources().getDisplayMetrics().density;
          }

          public static float px转sp(Context context, float px) {
              // DisplayMetrics.scaledDensity 已废弃；官方推荐的替代是用
              // TypedValue 求「1sp 等于多少 px」，再据此反算。
              DisplayMetrics dm = context.getResources().getDisplayMetrics();
              float pxPerSp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, dm);
              return pxPerSp == 0f ? 0f : px / pxPerSp;
          }

          public static float sp转px(Context context, float sp) {
              return TypedValue.applyDimension(
                      TypedValue.COMPLEX_UNIT_SP, sp, context.getResources().getDisplayMetrics());
          }

          // ---------------- 类型转换 ----------------

          /** 任意对象转字符串；null 返回空串。 */
          public static String 转字符串(Object o) {
              return o == null ? "" : String.valueOf(o);
          }

          /** 转整型；无法解析时返回 0。布尔按 1/0。 */
          public static int 转整型(Object o) {
              if (o instanceof Number) {
                  return ((Number) o).intValue();
              }
              if (o instanceof Boolean) {
                  return ((Boolean) o) ? 1 : 0;
              }
              try {
                  return Integer.parseInt(转字符串(o).trim());
              } catch (NumberFormatException e) {
                  return 0;
              }
          }

          /** 转长整；无法解析时返回 0。 */
          public static long 转长整(Object o) {
              if (o instanceof Number) {
                  return ((Number) o).longValue();
              }
              try {
                  return Long.parseLong(转字符串(o).trim());
              } catch (NumberFormatException e) {
                  return 0L;
              }
          }

          /** 转双精度；无法解析时返回 0。 */
          public static double 转双精小数(Object o) {
              if (o instanceof Number) {
                  return ((Number) o).doubleValue();
              }
              try {
                  return Double.parseDouble(转字符串(o).trim());
              } catch (NumberFormatException e) {
                  return 0d;
              }
          }

          /** 转单精度；无法解析时返回 0。 */
          public static float 转小数(Object o) {
              return (float) 转双精小数(o);
          }

          /**
           * 转布尔。
           *
           * <p>字符串按常见写法识别（true/1/yes/on 为真），数字非 0 为真。
           */
          public static boolean 转是否(Object o) {
              if (o instanceof Boolean) {
                  return (Boolean) o;
              }
              if (o instanceof Number) {
                  return ((Number) o).doubleValue() != 0d;
              }
              String s = 转字符串(o).trim().toLowerCase();
              return "true".equals(s) || "1".equals(s) || "yes".equals(s) || "on".equals(s);
          }

          /**
           * 转颜色 int。
           *
           * <p>支持 {@code #RRGGBB} / {@code #AARRGGBB} 字符串与数字。无法识别时返回黑色。
           */
          public static int 转颜色(Object o) {
              if (o instanceof Number) {
                  return ((Number) o).intValue();
              }
              String s = 转字符串(o).trim();
              if (s.isEmpty()) {
                  return 0xFF000000;
              }
              if (!s.startsWith("#")) {
                  s = "#" + s;
              }
              try {
                  return android.graphics.Color.parseColor(s);
              } catch (IllegalArgumentException e) {
                  return 0xFF000000;
              }
          }

          /** 转字节数组；字符串按 UTF-8 编码。 */
          public static byte[] 转字节组(Object o) {
              if (o instanceof byte[]) {
                  return (byte[]) o;
              }
              if (o == null) {
                  return new byte[0];
              }
              return String.valueOf(o).getBytes(java.nio.charset.StandardCharsets.UTF_8);
          }

          // ---------------- 数据库 ----------------

          /** 打开（不存在则创建）数据库文件，返回可用的数据库句柄。 */
          public static SQLiteDatabase 创建数据库(Context context, String name) {
              java.io.File dir = context.getDatabasePath(name).getParentFile();
              if (dir != null && !dir.exists()) {
                  dir.mkdirs();
              }
              return SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null);
          }

          /** 删除整个数据库文件。 */
          public static boolean 删除数据库(Context context, String name) {
              return context.deleteDatabase(name);
          }

          /** 执行任意 SQL（建表 / 增删改）。 */
          public static void 更新数据(SQLiteDatabase db, String sql) {
              if (db != null && sql != null) {
                  db.execSQL(sql);
              }
          }

          /** 建表。 */
          public static void 创建数据表(SQLiteDatabase db, String sql) {
              更新数据(db, sql);
          }

          public static void 删除数据表(SQLiteDatabase db, String table) {
              更新数据(db, "DROP TABLE IF EXISTS " + table);
          }

          /** 表是否存在。 */
          public static boolean 存在数据表(SQLiteDatabase db, String table) {
              if (db == null || table == null) {
                  return false;
              }
              try (Cursor c =
                      db.rawQuery(
                              "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                              new String[] {table})) {
                  return c != null && c.moveToFirst();
              } catch (RuntimeException e) {
                  return false;
              }
          }

          /**
           * 查询并把结果集转成「行 = 列名→值」的列表。
           *
           * <p>用 {@code Map} 而不是自定义实体类：调用方拿到的就是原始值，
           * 要转类型用本类的 {@code 转整型/转字符串} 即可，不需要为每张表生成类。
           */
          public static List<Map<String, Object>> 查询数据(SQLiteDatabase db, String sql) {
              List<Map<String, Object>> rows = new ArrayList<>();
              if (db == null || sql == null) {
                  return rows;
              }
              try (Cursor c = db.rawQuery(sql, null)) {
                  if (c == null) {
                      return rows;
                  }
                  String[] cols = c.getColumnNames();
                  while (c.moveToNext()) {
                      Map<String, Object> row = new LinkedHashMap<>();
                      for (int i = 0; i < cols.length; i++) {
                          row.put(cols[i], c.getString(i));
                      }
                      rows.add(row);
                  }
              } catch (RuntimeException ignored) {
                  // SQL 非法或表不存在：返回已读到的部分，不抛给调用方
              }
              return rows;
          }

          /** 查询行数。 */
          public static int 查询行数(SQLiteDatabase db, String table) {
              if (db == null || table == null) {
                  return 0;
              }
              try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + table, null)) {
                  return c != null && c.moveToFirst() ? c.getInt(0) : 0;
              } catch (RuntimeException e) {
                  return 0;
              }
          }

          /** 关闭数据库句柄。 */
          public static void 释放数据库(SQLiteDatabase db) {
              if (db != null && db.isOpen()) {
                  db.close();
              }
          }
      }
      """
          .trimIndent() + "\n"

  /**
   * 生成 `工具`（动画 / 媒体播放 / 通知 / 线程）。
   *
   * <p>对应 Appv5 的 `gj`（`工具.java`，27.6KB）。Appv5 里这些能力分散在
   * 若干内部类（`Animationx`/`dh`/`mtbf`/`txcl`），这里拍平成静态方法。
   */
  fun toolKit(packageId: String): String =
      """
      package ${toolPackage(packageId)};

      import android.app.Activity;
      import android.app.Notification;
      import android.app.NotificationChannel;
      import android.app.NotificationManager;
      import android.content.Context;
      import android.content.Intent;
      import android.media.MediaPlayer;
      import android.os.Build;
      import android.os.Handler;
      import android.os.Looper;
      import android.view.View;
      import android.view.animation.AlphaAnimation;
      import android.view.animation.Animation;
      import android.view.animation.TranslateAnimation;
      import android.widget.Toast;
      import java.io.File;

      /**
       * 工具：动画 / 媒体播放 / 通知 / 线程。
       *
       * <p>方法名取自 Appv5 的 {@code 工具}(gj) 模块。
       *
       * <p><b>关于主线程</b>：Toast 与通知的创建必须在主线程。这里的
       * {@code 提示} 与 {@code 发通知} 会自动切到主线程，调用方无需关心。
       */
      public final class 工具 {

          private 工具() {}

          // ---------------- 动画 ----------------

          /** 平移动画，单位像素。{@code fillAfter} 保持终态。 */
          public static void 位置移动(View view, float fromX, float toX, float fromY, float toY, long durationMs) {
              if (view == null) {
                  return;
              }
              TranslateAnimation anim =
                      new TranslateAnimation(fromX, toX, fromY, toY);
              anim.setDuration(durationMs);
              anim.setFillAfter(true);
              view.startAnimation(anim);
          }

          /** 淡入（alpha 0 → 1）。 */
          public static void 淡入(View view, long durationMs) {
              透明度(view, 0f, 1f, durationMs);
          }

          /** 淡出（alpha 1 → 0）。 */
          public static void 淡出(View view, long durationMs) {
              透明度(view, 1f, 0f, durationMs);
          }

          public static void 透明度(View view, float from, float to, long durationMs) {
              if (view == null) {
                  return;
              }
              AlphaAnimation anim = new AlphaAnimation(from, to);
              anim.setDuration(durationMs);
              anim.setFillAfter(true);
              view.startAnimation(anim);
          }

          /** 用帧动画资源做背景（对应 Appv5 的「动画背景」）。 */
          public static void 动画背景(View view, int drawableResId) {
              if (view == null) {
                  return;
              }
              android.graphics.drawable.Drawable d =
                      androidx.core.content.ContextCompat.getDrawable(view.getContext(), drawableResId);
              if (d instanceof android.graphics.drawable.AnimationDrawable) {
                  view.setBackground(d);
                  ((android.graphics.drawable.AnimationDrawable) d).start();
              } else if (d != null) {
                  view.setBackground(d);
              }
          }

          // ---------------- 媒体播放 ----------------

          /**
           * 创建并准备一个媒体播放器。
           *
           * <p>{@code prepare()} 是同步的：本地文件很快，但网络地址会阻塞。
           * 这里按本地文件路径设计；失败返回 {@code null}，调用方判空即可。
           */
          public static MediaPlayer 媒体播放(Context context, String path) {
              if (path == null) {
                  return null;
              }
              try {
                  MediaPlayer mp = new MediaPlayer();
                  if (path.startsWith("http://") || path.startsWith("https://")) {
                      mp.setDataSource(path);
                  } else {
                      mp.setDataSource(new File(path).getAbsolutePath());
                  }
                  mp.prepare();
                  return mp;
              } catch (Exception e) {
                  return null;
              }
          }

          public static void 开始(MediaPlayer mp) {
              if (mp != null && !mp.isPlaying()) {
                  mp.start();
              }
          }

          public static void 停止(MediaPlayer mp) {
              if (mp != null && mp.isPlaying()) {
                  mp.stop();
              }
          }

          public static void 取消(MediaPlayer mp) {
              if (mp != null) {
                  mp.release();
              }
          }

          /** 总时长（毫秒）。 */
          public static int 媒体时长(MediaPlayer mp) {
              return mp == null ? 0 : mp.getDuration();
          }

          /** 当前播放位置（毫秒）。 */
          public static int 播放位置(MediaPlayer mp) {
              return mp == null ? 0 : mp.getCurrentPosition();
          }

          /** 跳到指定位置（毫秒）。 */
          public static void 指定播放位置(MediaPlayer mp, int ms) {
              if (mp != null) {
                  mp.seekTo(ms);
              }
          }

          /** 设置左右声道音量（0f~1f）。 */
          public static void 播放音量(MediaPlayer mp, float left, float right) {
              if (mp != null) {
                  mp.setVolume(left, right);
              }
          }

          public static boolean 播放状态(MediaPlayer mp) {
              return mp != null && mp.isPlaying();
          }

          public static void 循环播放(MediaPlayer mp, boolean loop) {
              if (mp != null) {
                  mp.setLooping(loop);
              }
          }

          // ---------------- 通知 / 提示 ----------------

          /** Toast 提示（自动切主线程）。 */
          public static void 提示(final Context context, final CharSequence text) {
              if (context == null) {
                  return;
              }
              在主线程(
                      new Runnable() {
                          @Override
                          public void run() {
                              Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
                          }
                      });
          }

          /**
           * 发一条系统通知。
           *
           * <p>Android 8.0 起通知必须归属渠道，这里统一建一个默认渠道；
           * 同一渠道重复创建是幂等的，因此每次调用都建也不会出问题。
           */
          public static void 发通知(Context context, String title, String text) {
              if (context == null) {
                  return;
              }
              final Context app = context.getApplicationContext();
              final NotificationManager nm =
                      (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
              if (nm == null) {
                  return;
              }
              final String channelId = "quick_develop_default";
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                  NotificationChannel channel =
                          new NotificationChannel(
                                  channelId, "默认通知", NotificationManager.IMPORTANCE_DEFAULT);
                  nm.createNotificationChannel(channel);
              }
              final Notification.Builder builder =
                      Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                              ? new Notification.Builder(app, channelId)
                              : new Notification.Builder(app);
              builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                      .setContentTitle(title == null ? "" : title)
                      .setContentText(text == null ? "" : text)
                      .setAutoCancel(true);
              在主线程(
                      new Runnable() {
                          @Override
                          public void run() {
                              nm.notify((int) (System.currentTimeMillis() & 0xFFFF), builder.build());
                          }
                      });
          }

          // ---------------- 线程 / 调度 ----------------

          /** 起一个后台线程并立即启动。 */
          public static Thread 线程(Runnable task) {
              Thread t = new Thread(task);
              t.start();
              return t;
          }

          /** 延迟执行（主线程）。 */
          public static void 延迟执行(Runnable task, long delayMs) {
              new Handler(Looper.getMainLooper()).postDelayed(task, delayMs);
          }

          /** 回到桌面（等价于按 HOME）。 */
          public static void 显示桌面(Context context) {
              if (context == null) {
                  return;
              }
              Intent intent = new Intent(Intent.ACTION_MAIN);
              intent.addCategory(Intent.CATEGORY_HOME);
              intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
              context.startActivity(intent);
          }

          /** 当前是否在主线程。 */
          public static boolean 在主线程中() {
              return Looper.myLooper() == Looper.getMainLooper();
          }

          private static void 在主线程(Runnable task) {
              if (在主线程中()) {
                  task.run();
              } else {
                  new Handler(Looper.getMainLooper()).post(task);
              }
          }
      }
      """
          .trimIndent() + "\n"

  /**
   * 生成 `系统`（设备信息 / 应用管理 / 剪切板 / 震动 / 截屏）。
   *
   * <p>对应 Appv5 的 `xt`（`系统.java`，12.7KB）。
   *
   * <p><b>权限处理原则</b>：需要运行时权限的能力（震动、截屏）不静默失败，
   * 也不崩溃——能降级就降级，不能就返回明确的空值/false，由调用方决定。
   * 需要声明权限的项在方法注释里标明。
   */
  fun systemKit(packageId: String): String =
      """
      package ${toolPackage(packageId)};

      import android.app.Activity;
      import android.content.ClipData;
      import android.content.ClipboardManager;
      import android.content.Context;
      import android.content.Intent;
      import android.content.pm.ApplicationInfo;
      import android.content.pm.PackageInfo;
      import android.content.pm.PackageManager;
      import android.graphics.Bitmap;
      import android.graphics.Canvas;
      import android.net.Uri;
      import android.os.Build;
      import android.os.VibrationEffect;
      import android.os.Vibrator;
      import android.util.DisplayMetrics;
      import android.view.View;
      import android.view.WindowManager;
      import java.io.BufferedReader;
      import java.io.FileReader;
      import java.util.ArrayList;
      import java.util.List;

      /**
       * 系统：设备信息 / 应用管理 / 剪切板 / 震动 / 截屏。
       *
       * <p>方法名取自 Appv5 的 {@code 系统}(xt) 模块。
       */
      public final class 系统 {

          private 系统() {}

          // ---------------- 设备信息 ----------------

          public static String 品牌() {
              return Build.BRAND == null ? "" : Build.BRAND;
          }

          public static String 型号() {
              return Build.MODEL == null ? "" : Build.MODEL;
          }

          public static String 系统版本() {
              return Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE;
          }

          /** 屏幕宽（像素）。 */
          public static int 宽(Context context) {
              return 指标(context).widthPixels;
          }

          /** 屏幕高（像素）。 */
          public static int 高(Context context) {
              return 指标(context).heightPixels;
          }

          /**
           * 取屏幕指标。
           *
           * <p>刻意不用 {@code WindowManager.getDefaultDisplay().getMetrics()}——
           * 那套 API 自 30 起废弃，且在分屏/折叠屏上返回的是整块物理屏而非本窗口，
           * 会导致布局算错。{@code Resources.getDisplayMetrics()} 返回的是
           * 当前配置下的可用尺寸，既未废弃也与布局实际可用空间一致。
           */
          private static DisplayMetrics 指标(Context context) {
              return context == null
                      ? new DisplayMetrics()
                      : context.getResources().getDisplayMetrics();
          }

          /** 从 /proc/cpuinfo 读 CPU 型号；读不到返回空串。 */
          public static String cpu型号() {
              try (BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
                  String line;
                  while ((line = reader.readLine()) != null) {
                      if (line.toLowerCase().startsWith("hardware")
                              || line.toLowerCase().startsWith("model name")) {
                          int colon = line.indexOf(':');
                          if (colon > 0) {
                              return line.substring(colon + 1).trim();
                          }
                      }
                  }
              } catch (Exception ignored) {
                  // /proc 在某些设备上不可读：返回空串
              }
              return "";
          }

          // ---------------- 应用管理 ----------------

          /** 本应用包名。 */
          public static String 包名(Context context) {
              return context == null ? "" : context.getPackageName();
          }

          /**
           * 读取指定包的版本名。
           *
           * <p>读不到（未安装/被限制）时返回空串，不抛 NameNotFoundException。
           */
          public static String 包信息(Context context, String pkg) {
              if (context == null || pkg == null) {
                  return "";
              }
              try {
                  PackageInfo info = context.getPackageManager().getPackageInfo(pkg, 0);
                  return info.versionName == null ? "" : info.versionName;
              } catch (PackageManager.NameNotFoundException e) {
                  return "";
              }
          }

          /** 已安装应用的包名列表。 */
          public static List<String> 应用列表(Context context) {
              List<String> names = new ArrayList<>();
              if (context == null) {
                  return names;
              }
              List<ApplicationInfo> apps =
                      context.getPackageManager().getInstalledApplications(0);
              if (apps == null) {
                  return names;
              }
              for (ApplicationInfo info : apps) {
                  if (info != null && info.packageName != null) {
                      names.add(info.packageName);
                  }
              }
              return names;
          }

          /** 用启动 Intent 打开指定应用；包不存在时静默返回。 */
          public static void 打开应用(Context context, String pkg) {
              if (context == null || pkg == null) {
                  return;
              }
              Intent intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
              if (intent == null) {
                  return;
              }
              intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
              try {
                  context.startActivity(intent);
              } catch (RuntimeException ignored) {
                  // 被系统限制启动：忽略
              }
          }

          /** 跳转到系统卸载界面（真正卸载由系统弹窗确认）。 */
          public static void 卸载应用(Context context, String pkg) {
              if (context == null || pkg == null) {
                  return;
              }
              Intent intent =
                      new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
              intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
              try {
                  context.startActivity(intent);
              } catch (RuntimeException ignored) {
                  // 无卸载界面：忽略
              }
          }

          // ---------------- 剪切板 ----------------

          /** 读剪切板文本；为空返回空串。 */
          public static String 剪切板获取(Context context) {
              ClipboardManager cm = 剪切板(context);
              if (cm == null || cm.getPrimaryClip() == null) {
                  return "";
              }
              ClipData clip = cm.getPrimaryClip();
              if (clip.getItemCount() == 0) {
                  return "";
              }
              CharSequence text = clip.getItemAt(0).coerceToText(context);
              return text == null ? "" : text.toString();
          }

          /** 写剪切板。 */
          public static void 剪切板写入(Context context, String text) {
              ClipboardManager cm = 剪切板(context);
              if (cm != null) {
                  cm.setPrimaryClip(ClipData.newPlainText("text", text == null ? "" : text));
              }
          }

          private static ClipboardManager 剪切板(Context context) {
              return context == null
                      ? null
                      : (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
          }

          // ---------------- 震动 / 截屏 ----------------

          /**
           * 震动指定毫秒数。
           *
           * <p><b>需要在 manifest 声明</b> {@code android.permission.VIBRATE}。
           * 未声明或无马达时静默返回（不抛异常）。
           */
          public static void 震动(Context context, long ms) {
              if (context == null) {
                  return;
              }
              Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
              if (v == null || !v.hasVibrator()) {
                  return;
              }
              try {
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                      v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                  } else {
                      v.vibrate(ms);
                  }
              } catch (RuntimeException ignored) {
                  // 缺 VIBRATE 权限：忽略
              }
          }

          /**
           * 截取本应用当前界面。
           *
           * <p><b>不需要任何权限</b>——它把 Activity 的根视图画到 Bitmap，
           * 属于应用自身内容，因此不受「截屏需授权」的限制。
           * 缺点也在此：只能截本应用，截不到别的 app 或系统界面。
           */
          public static Bitmap 截屏(Activity activity) {
              if (activity == null || activity.getWindow() == null) {
                  return null;
              }
              View root = activity.getWindow().getDecorView().getRootView();
              if (root.getWidth() <= 0 || root.getHeight() <= 0) {
                  return null;
              }
              Bitmap bitmap =
                      Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
              root.draw(new Canvas(bitmap));
              return bitmap;
          }

          /** 截屏并保存为 PNG；失败返回空串。 */
          public static String 截屏保存(Activity activity, String path) {
              Bitmap bitmap = 截屏(activity);
              if (bitmap == null || path == null) {
                  return "";
              }
              java.io.File file = new java.io.File(path);
              java.io.File parent = file.getParentFile();
              if (parent != null && !parent.exists() && !parent.mkdirs()) {
                  return "";
              }
              try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                  bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                  out.flush();
                  bitmap.recycle();
                  return file.getAbsolutePath();
              } catch (Exception e) {
                  bitmap.recycle();
                  return "";
              }
          }
      }
      """
          .trimIndent() + "\n"

  /** 全部工具类：`文件名 -> 源码`。 */
  fun all(packageId: String): List<Pair<String, String>> =
      listOf(
          "字符" to stringKit(packageId),
          "文件" to fileKit(packageId),
          "数据" to dataKit(packageId),
          "工具" to toolKit(packageId),
          "系统" to systemKit(packageId),
      )
}

/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
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

package com.tom.rv2ide.ai.tool.skill;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 技能市场客户端（协议照 cc-haha market/providers）。
 *
 * <p><b>上游</b>：ClawHub（{@code https://clawhub.ai/api/v1}）与 SkillHub
 * （{@code https://api.skillhub.cn/api}）。列表/搜索/详情/文件下载各有一套路径，
 * 响应形态在 {@link #parseList} 里抹平。
 *
 * <p><b>安装流程照 cc-haha installService</b>：拉文件清单 → 校验（SKILL.md 存在、
 * 数量/大小限额）→ 下载到临时目录 → 逐文件 sha256 校验 → 原子改名落盘到目标目录 →
 * 写 {@code .market-meta.json} 标记。卸载只删带标记的目录，**绝不误删用户手装技能**。
 *
 * <p>纯 Java（HttpURLConnection + MessageDigest），可 JVM 单测。
 */
public final class SkillMarketClient {

  // ---- 上游常量 ----
  public static final String SOURCE_CLAWHUB = "clawhub";
  public static final String SOURCE_SKILLHUB = "skillhub";

  private static final String CLAWHUB_BASE = "https://clawhub.ai/api/v1";
  private static final String SKILLHUB_BASE = "https://api.skillhub.cn/api";

  /** 安装限额（cc-haha MARKET_LIMITS 同值）。 */
  static final int MAX_FILES = 200;
  static final long MAX_FILE_BYTES = 5 * 1024 * 1024L;
  static final long MAX_TOTAL_BYTES = 20 * 1024 * 1024L;

  /** 市场条目的最小投影。 */
  public static final class MarketSkill {
    public final String source;
    public final String slug;
    public final String name;
    public final String description;
    public final String version;
    public final long downloads;

    MarketSkill(
        String source, String slug, String name, String description, String version,
        long downloads) {
      this.source = source;
      this.slug = slug;
      this.name = name;
      this.description = description;
      this.version = version;
      this.downloads = downloads;
    }

    /** 列表行文案。 */
    public String toLine() {
      return name + (version.isEmpty() ? "" : "  v" + version)
          + (downloads > 0 ? "  ↓" + downloads : "");
    }
  }

  /** 安装结果。 */
  public static final class InstallResult {
    public final boolean ok;
    public final String message;
    /** 安装目录（ok 时非空）。 */
    public final File dir;

    private InstallResult(boolean ok, String message, File dir) {
      this.ok = ok;
      this.message = message;
      this.dir = dir;
    }

    public static InstallResult success(File dir) {
      return new InstallResult(true, "", dir);
    }

    public static InstallResult failure(String message) {
      return new InstallResult(false, message, null);
    }
  }

  private final int connectTimeoutMs;
  private final int readTimeoutMs;

  public SkillMarketClient() {
    this(15_000, 30_000);
  }

  SkillMarketClient(int connectTimeoutMs, int readTimeoutMs) {
    this.connectTimeoutMs = connectTimeoutMs;
    this.readTimeoutMs = readTimeoutMs;
  }

  // ---- 浏览 / 搜索 ----

  /** 按下载量拉市场列表（两上游合并）。失败的上游跳过——一个挂了不该清空整页。 */
  public List<MarketSkill> list(int limit) {
    List<MarketSkill> out = new ArrayList<>();
    out.addAll(listClawhub(limit));
    out.addAll(listSkillhub(limit));
    return out;
  }

  /** 关键词搜索（两上游合并）。 */
  public List<MarketSkill> search(String query, int limit) {
    List<MarketSkill> out = new ArrayList<>();
    out.addAll(searchClawhub(query, limit));
    out.addAll(searchSkillhub(query, limit));
    return out;
  }

  private List<MarketSkill> listClawhub(int limit) {
    try {
      String body =
          httpGet(
              CLAWHUB_BASE + "/skills?limit=" + limit + "&sort=downloads");
      return parseClawhubList(body);
    } catch (Exception e) {
      return new ArrayList<>();
    }
  }

  private List<MarketSkill> searchClawhub(String query, int limit) {
    try {
      String body =
          httpGet(
              CLAWHUB_BASE
                  + "/search?q="
                  + urlEncode(query)
                  + "&limit="
                  + limit);
      return parseClawhubList(body);
    } catch (Exception e) {
      return new ArrayList<>();
    }
  }

  private List<MarketSkill> parseClawhubList(String body) {
    List<MarketSkill> out = new ArrayList<>();
    try {
      JSONObject root = new JSONObject(body);
      JSONArray array =
          root.optJSONArray("skills") != null
              ? root.optJSONArray("skills")
              : root.optJSONArray("data");
      if (array == null) {
        return out;
      }
      for (int i = 0; i < array.length(); i++) {
        JSONObject item = array.optJSONObject(i);
        if (item == null) {
          continue;
        }
        String slug = item.optString("slug", item.optString("id", ""));
        if (slug.isEmpty()) {
          continue;
        }
        out.add(
            new MarketSkill(
                SOURCE_CLAWHUB,
                slug,
                item.optString("name", slug),
                item.optString("description", ""),
                item.optString("version", ""),
                item.optLong("downloads", 0)));
      }
    } catch (Exception ignored) {
      // 形态不符当空列表，由上层提示。
    }
    return out;
  }

  private List<MarketSkill> listSkillhub(int limit) {
    try {
      String body =
          httpGet(SKILLHUB_BASE + "/skills?page=1&pageSize=" + limit);
      return parseSkillhubList(body);
    } catch (Exception e) {
      return new ArrayList<>();
    }
  }

  private List<MarketSkill> searchSkillhub(String query, int limit) {
    try {
      String body =
          httpGet(
              SKILLHUB_BASE
                  + "/skills?page=1&pageSize="
                  + limit
                  + "&keyword="
                  + urlEncode(query));
      return parseSkillhubList(body);
    } catch (Exception e) {
      return new ArrayList<>();
    }
  }

  private List<MarketSkill> parseSkillhubList(String body) {
    List<MarketSkill> out = new ArrayList<>();
    try {
      JSONObject root = new JSONObject(body);
      JSONArray array =
          root.optJSONArray("skills") != null
              ? root.optJSONArray("skills")
              : root.optJSONArray("data");
      if (array == null) {
        return out;
      }
      for (int i = 0; i < array.length(); i++) {
        JSONObject item = array.optJSONObject(i);
        if (item == null) {
          continue;
        }
        String slug = item.optString("slug", item.optString("id", ""));
        if (slug.isEmpty()) {
          continue;
        }
        out.add(
            new MarketSkill(
                SOURCE_SKILLHUB,
                slug,
                item.optString("name", slug),
                item.optString("description", ""),
                item.optString("version", ""),
                item.optLong("downloads", item.optLong("installs", 0))));
      }
    } catch (Exception ignored) {
      // 同上。
    }
    return out;
  }

  // ---- 安装 ----

  /**
   * 安装一个技能到 {@code targetRoot/<sanitized-slug>/}。
   *
   * <p>流程：清单校验 → 临时目录逐文件下载（sha256 校验）→ 原子改名 → 写市场标记。
   * 目标已存在时失败（cc-haha 409 同语义）——更新即先卸载再装。
   */
  public InstallResult install(MarketSkill skill, File targetRoot) {
    try {
      List<FileEntry> files = listFiles(skill);
      if (files.isEmpty()) {
        return InstallResult.failure("市场没有返回该技能的文件清单。");
      }
      boolean hasSkillMd = false;
      long total = 0;
      for (FileEntry file : files) {
        if ("SKILL.md".equalsIgnoreCase(file.path)) {
          hasSkillMd = true;
        }
        total += file.size;
      }
      if (!hasSkillMd) {
        return InstallResult.failure("清单里没有 SKILL.md——这不是一个技能包。");
      }
      if (files.size() > MAX_FILES) {
        return InstallResult.failure("文件数超过上限（" + MAX_FILES + "）。");
      }
      if (total > MAX_TOTAL_BYTES) {
        return InstallResult.failure("总大小超过上限（20MB）。");
      }

      File dir = new File(targetRoot, sanitizeSlug(skill.slug));
      if (dir.exists()) {
        return InstallResult.failure("同名技能已存在（更新请先卸载）。");
      }

      // 临时目录下载 + 校验，全部成功才落盘。
      File tmp = new File(targetRoot, ".market-install-" + System.currentTimeMillis());
      if (!tmp.mkdirs()) {
        return InstallResult.failure("无法创建临时下载目录。");
      }
      try {
        for (FileEntry file : files) {
          if (file.size > MAX_FILE_BYTES) {
            return InstallResult.failure("单文件超过 5MB：" + file.path);
          }
          byte[] bytes = downloadFile(skill, file.path);
          if (bytes == null) {
            return InstallResult.failure("下载失败：" + file.path);
          }
          if (!file.sha256.isEmpty() && !sha256Hex(bytes).equals(file.sha256)) {
            return InstallResult.failure("校验失败（sha256 不符）：" + file.path);
          }
          File target = new File(tmp, file.path);
          File parent = target.getParentFile();
          if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return InstallResult.failure("无法创建子目录：" + file.path);
          }
          try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(bytes);
          }
        }
        // 原子落盘（同卷 rename）。
        if (!tmp.renameTo(dir)) {
          return InstallResult.failure("安装落盘失败。");
        }
        writeMarketMeta(dir, skill);
        return InstallResult.success(dir);
      } finally {
        // 改名成功后 tmp 已不存在；失败路径清理残留。
        if (tmp.exists()) {
          deleteRecursively(tmp);
        }
      }
    } catch (Exception e) {
      return InstallResult.failure("安装失败：" + e.getMessage());
    }
  }

  /** 卸载：只删带市场标记的目录，用户手装/导入的技能不受影响。 */
  public static boolean uninstall(File skillDir) {
    if (skillDir == null || !skillDir.isDirectory()) {
      return false;
    }
    if (!new File(skillDir, ".market-meta.json").isFile()) {
      return false;
    }
    return deleteRecursively(skillDir);
  }

  /** 某技能目录是否由市场安装（UI 据此决定显示卸载还是仅查看）。 */
  public static boolean isMarketInstalled(File skillDir) {
    return skillDir != null && new File(skillDir, ".market-meta.json").isFile();
  }

  private void writeMarketMeta(File dir, MarketSkill skill) throws IOException {
    JSONObject meta =
        new JSONObject()
            .put("source", skill.source)
            .put("slug", skill.slug)
            .put("version", skill.version)
            .put("installedAt", System.currentTimeMillis());
    try (FileOutputStream out = new FileOutputStream(new File(dir, ".market-meta.json"))) {
      out.write(meta.toString().getBytes(StandardCharsets.UTF_8));
    }
  }

  // ---- 上游文件清单 / 下载 ----

  private static final class FileEntry {
    final String path;
    final long size;
    final String sha256;

    FileEntry(String path, long size, String sha256) {
      this.path = path;
      this.size = size;
      this.sha256 = sha256;
    }
  }

  private List<FileEntry> listFiles(MarketSkill skill) throws IOException {
    String body;
    switch (skill.source) {
      case SOURCE_CLAWHUB:
        body =
            httpGet(
                CLAWHUB_BASE + "/skills/" + urlEncode(skill.slug) + "/versions/"
                    + (skill.version.isEmpty() ? "latest" : urlEncode(skill.version)));
        return parseClawhubFiles(body);
      case SOURCE_SKILLHUB:
        body = httpGet(SKILLHUB_BASE + "/v1/skills/" + urlEncode(skill.slug) + "/files");
        return parseSkillhubFiles(body);
      default:
        throw new IOException("未知来源：" + skill.source);
    }
  }

  private static List<FileEntry> parseClawhubFiles(String body) {
    List<FileEntry> out = new ArrayList<>();
    JSONObject root = new JSONObject(body);
    JSONArray files =
        root.optJSONArray("files") != null
            ? root.optJSONArray("files")
            : root.optJSONObject("version") == null
                ? null
                : root.optJSONObject("version").optJSONArray("files");
    if (files == null) {
      return out;
    }
    for (int i = 0; i < files.length(); i++) {
      JSONObject file = files.optJSONObject(i);
      if (file == null) {
        continue;
      }
      String path = file.optString("path", "");
      if (path.isEmpty()) {
        continue;
      }
      out.add(
          new FileEntry(
              path, file.optLong("size", 0), file.optString("sha256", "")));
    }
    return out;
  }

  private static List<FileEntry> parseSkillhubFiles(String body) {
    List<FileEntry> out = new ArrayList<>();
    JSONObject root = new JSONObject(body);
    JSONArray files =
        root.optJSONArray("files") != null
            ? root.optJSONArray("files")
            : root.optJSONArray("data");
    if (files == null) {
      return out;
    }
    for (int i = 0; i < files.length(); i++) {
      JSONObject file = files.optJSONObject(i);
      if (file == null) {
        continue;
      }
      String path = file.optString("path", file.optString("name", ""));
      if (path.isEmpty()) {
        continue;
      }
      out.add(
          new FileEntry(
              path, file.optLong("size", 0), file.optString("sha256", "")));
    }
    return out;
  }

  /** 下载单文件；302 跟随（SkillHub 的文件 302 到对象存储）。 */
  private byte[] downloadFile(MarketSkill skill, String path) throws IOException {
    String url;
    switch (skill.source) {
      case SOURCE_CLAWHUB:
        url =
            CLAWHUB_BASE
                + "/skills/"
                + urlEncode(skill.slug)
                + "/file?path="
                + urlEncode(path);
        break;
      case SOURCE_SKILLHUB:
        url =
            SKILLHUB_BASE
                + "/v1/skills/"
                + urlEncode(skill.slug)
                + "/file?path="
                + urlEncode(path);
        break;
      default:
        throw new IOException("未知来源：" + skill.source);
    }
    return httpGetBytes(url);
  }

  // ---- HTTP 原语（HttpURLConnection；redirect 默认跟随，覆盖 SkillHub 302 → COS） ----

  private String httpGet(String url) throws IOException {
    return new String(httpGetBytes(url), StandardCharsets.UTF_8);
  }

  private byte[] httpGetBytes(String url) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setConnectTimeout(connectTimeoutMs);
    connection.setReadTimeout(readTimeoutMs);
    connection.setInstanceFollowRedirects(true);
    int code = connection.getResponseCode();
    if (code < 200 || code >= 300) {
      throw new IOException("HTTP " + code);
    }
    try (InputStream in = connection.getInputStream()) {
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    } finally {
      connection.disconnect();
    }
  }

  // ---- 工具 ----

  static String sha256Hex(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(bytes);
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      return ""; // SHA-256 恒可用
    }
  }

  /** slug → 目录名：只留字母数字点下划线连字符，其余转下划线。 */
  static String sanitizeSlug(String slug) {
    StringBuilder sb = new StringBuilder();
    for (char c : slug.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
      if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-') {
        sb.append(c);
      } else {
        sb.append('_');
      }
    }
    String value = sb.toString();
    return value.isEmpty() ? "unnamed" : value;
  }

  private static String urlEncode(String value) {
    try {
      return java.net.URLEncoder.encode(value, "UTF-8");
    } catch (java.io.UnsupportedEncodingException e) {
      return value;
    }
  }

  private static boolean deleteRecursively(File file) {
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) {
        for (File child : children) {
          if (!deleteRecursively(child)) {
            return false;
          }
        }
      }
    }
    return file.delete();
  }
}

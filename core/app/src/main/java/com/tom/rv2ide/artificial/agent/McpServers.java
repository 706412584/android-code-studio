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

package com.tom.rv2ide.artificial.agent;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 已配置的 MCP server 列表。
 *
 * <p>存成**一个 JSON 数组**而不是「每个 server 一组偏好键」：server 是数量可变的列表，
 * 用偏好键表达需要自己维护索引与增删逻辑，而 JSON 数组天然支持任意条数。
 *
 * <p>读写失败一律返回空列表：MCP 是可选扩展，配置损坏不该让 AI 功能整体不可用。
 */
public final class McpServers {

  private static final String PREFS_NAME = "ai_agent_tools";

  /** 列表键。 */
  public static final String KEY_SERVERS = "mcp_servers";

  private static final String FIELD_URL = "url";
  private static final String FIELD_LABEL = "label";
  private static final String FIELD_ENABLED = "enabled";
  private static final String FIELD_TYPE = "type";
  // stdio 专属字段：type=stdio 时 url 存命令名（复用唯一必填槽位），另存参数与环境。
  private static final String FIELD_ARGS = "args";
  private static final String FIELD_ENV = "env";
  private static final String FIELD_CWD = "cwd";

  /** 默认传输类型。已有配置里没有 {@code type} 字段，必须落到它，否则升级即失效。 */
  public static final String DEFAULT_TYPE = "http";

  /** 支持的传输类型取值。 */
  public static final String TYPE_HTTP = "http";

  public static final String TYPE_SSE = "sse";

  public static final String TYPE_STDIO = "stdio";

  /**
   * 归一化传输类型。
   *
   * <p>无法识别时回落到 {@link #DEFAULT_TYPE} 而不是报错：配置可能是旧版本写的
   * （没有该字段），也可能是手改坏的。MCP 是可选扩展，一个字段写错不该让整条配置读不出来。
   */
  public static String normalizeType(String raw) {
    if (raw == null) {
      return DEFAULT_TYPE;
    }
    String value = raw.trim().toLowerCase(java.util.Locale.US);
    if (TYPE_SSE.equals(value)) {
      return TYPE_SSE;
    }
    if (TYPE_STDIO.equals(value)) {
      return TYPE_STDIO;
    }
    return DEFAULT_TYPE;
  }

  /** 一个 MCP server 配置。 */
  public static final class Server {
    public final String url;
    public final String label;
    public final boolean enabled;

    /**
     * 传输类型：{@code http} / {@code sse} / {@code stdio}。
     *
     * <p><b>为什么存字符串而不是枚举</b>：取值来自偏好里的 JSON，损坏或来自更高版本的
     * 未知值时只需回落到默认，不需要在读取处处理 {@code IllegalArgumentException}。
     * 解析统一由 {@code McpClient.Transport.fromId} 负责。
     */
    public final String type;

    /** stdio 命令参数；HTTP/SSE 时为空。 */
    public final java.util.List<String> args;
    /** stdio 附加环境变量；HTTP/SSE 时为空。 */
    public final java.util.Map<String, String> env;
    /** stdio 工作目录；空串继承父进程。 */
    public final String cwd;

    public Server(String url, String label, boolean enabled) {
      this(url, label, enabled, DEFAULT_TYPE);
    }

    public Server(String url, String label, boolean enabled, String type) {
      this(url, label, enabled, type, java.util.Collections.<String>emptyList(),
          java.util.Collections.<String, String>emptyMap(), "");
    }

    public Server(
        String url,
        String label,
        boolean enabled,
        String type,
        java.util.List<String> args,
        java.util.Map<String, String> env,
        String cwd) {
      this.url = url == null ? "" : url.trim();
      this.label = label == null ? "" : label.trim();
      this.enabled = enabled;
      this.type = normalizeType(type);
      this.args =
          args == null
              ? java.util.Collections.<String>emptyList()
              : new java.util.ArrayList<>(args);
      this.env =
          env == null
              ? java.util.Collections.<String, String>emptyMap()
              : new java.util.LinkedHashMap<>(env);
      this.cwd = cwd == null ? "" : cwd.trim();
    }

    /** 展示名：优先用户填的 label，否则用地址（stdio 时即命令名）。 */
    public String displayName() {
      return label.isEmpty() ? url : label;
    }

    public JSONObject toJson() throws org.json.JSONException {
      JSONObject json =
          new JSONObject()
              .put(FIELD_URL, url)
              .put(FIELD_LABEL, label)
              .put(FIELD_ENABLED, enabled)
              .put(FIELD_TYPE, type);
      if (TYPE_STDIO.equals(type)) {
        org.json.JSONArray argv = new org.json.JSONArray();
        for (String arg : args) {
          argv.put(arg);
        }
        json.put(FIELD_ARGS, argv);
        JSONObject envJson = new JSONObject();
        for (java.util.Map.Entry<String, String> entry : env.entrySet()) {
          envJson.put(entry.getKey(), entry.getValue());
        }
        json.put(FIELD_ENV, envJson);
        json.put(FIELD_CWD, cwd);
      }
      return json;
    }
  }

  private final SharedPreferences prefs;

  public McpServers(Context context) {
    this.prefs = context.getApplicationContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  /** 全部配置（含被禁用的），供设置界面展示。 */
  public List<Server> all() {
    String raw = prefs.getString(KEY_SERVERS, "");
    List<Server> servers = new ArrayList<>();
    if (raw == null || raw.trim().isEmpty()) {
      return servers;
    }
    try {
      JSONArray array = new JSONArray(raw);
      for (int i = 0; i < array.length(); i++) {
        JSONObject json = array.optJSONObject(i);
        if (json == null) {
          continue;
        }
        String url = json.optString(FIELD_URL, "").trim();
        if (url.isEmpty()) {
          // 没有地址的条目无法连接，跳过而不是让整个列表读不出来。
          continue;
        }
        // optString 在字段缺失时返回 ""，normalizeType 会把它归到 http——
        // 这正是升级前写入的配置应有的行为。
        String type = normalizeType(json.optString(FIELD_TYPE, DEFAULT_TYPE));
        java.util.List<String> args = new java.util.ArrayList<>();
        java.util.Map<String, String> envMap = new java.util.LinkedHashMap<>();
        String cwd = "";
        if (TYPE_STDIO.equals(type)) {
          org.json.JSONArray argv = json.optJSONArray(FIELD_ARGS);
          if (argv != null) {
            for (int a = 0; a < argv.length(); a++) {
              String arg = argv.optString(a, "").trim();
              if (!arg.isEmpty()) {
                args.add(arg);
              }
            }
          }
          org.json.JSONObject envJson = json.optJSONObject(FIELD_ENV);
          if (envJson != null) {
            java.util.Iterator<String> keys = envJson.keys();
            while (keys.hasNext()) {
              String key = keys.next();
              envMap.put(key, envJson.optString(key, ""));
            }
          }
          cwd = json.optString(FIELD_CWD, "").trim();
        }
        servers.add(
            new Server(
                url,
                json.optString(FIELD_LABEL, ""),
                json.optBoolean(FIELD_ENABLED, true),
                type,
                args,
                envMap,
                cwd));
      }
    } catch (org.json.JSONException e) {
      // 配置损坏 → 当作空列表。不抛异常，否则 AI 功能会因此完全不可用。
      return new ArrayList<>();
    }
    return servers;
  }

  /** 已启用的配置，供运行时注册工具。 */
  public List<Server> enabled() {
    List<Server> result = new ArrayList<>();
    for (Server server : all()) {
      if (server.enabled) {
        result.add(server);
      }
    }
    return result;
  }

  /** 覆盖整个列表。 */
  public void save(List<Server> servers) {
    JSONArray array = new JSONArray();
    if (servers != null) {
      for (Server server : servers) {
        if (server == null || server.url.isEmpty()) {
          continue;
        }
        try {
          array.put(server.toJson());
        } catch (org.json.JSONException e) {
          // 常量 key，不可达。
        }
      }
    }
    prefs.edit().putString(KEY_SERVERS, array.toString()).apply();
  }

  /** 追加一个 server；地址已存在时不重复添加。 */
  public void add(Server server) {
    if (server == null || server.url.isEmpty()) {
      return;
    }
    List<Server> current = all();
    for (Server existing : current) {
      if (existing.url.equals(server.url)) {
        return;
      }
    }
    current.add(server);
    save(current);
  }

  /** 按地址移除。 */
  public void remove(String url) {
    if (url == null) {
      return;
    }
    List<Server> current = all();
    List<Server> kept = new ArrayList<>();
    for (Server server : current) {
      if (!server.url.equals(url)) {
        kept.add(server);
      }
    }
    save(kept);
  }
}

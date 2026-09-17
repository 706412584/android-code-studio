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

  /** 一个 MCP server 配置。 */
  public static final class Server {
    public final String url;
    public final String label;
    public final boolean enabled;

    public Server(String url, String label, boolean enabled) {
      this.url = url == null ? "" : url.trim();
      this.label = label == null ? "" : label.trim();
      this.enabled = enabled;
    }

    /** 展示名：优先用户填的 label，否则用地址。 */
    public String displayName() {
      return label.isEmpty() ? url : label;
    }

    public JSONObject toJson() throws org.json.JSONException {
      return new JSONObject()
          .put(FIELD_URL, url)
          .put(FIELD_LABEL, label)
          .put(FIELD_ENABLED, enabled);
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
        servers.add(
            new Server(url, json.optString(FIELD_LABEL, ""), json.optBoolean(FIELD_ENABLED, true)));
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

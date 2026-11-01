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

package com.assistant.engine;

import android.content.Context;
import android.content.SharedPreferences;
import com.tom.rv2ide.ai.tool.mcp.McpClient;
import com.tom.rv2ide.ai.tool.mcp.McpToolAdapter;
import com.tom.rv2ide.ai.tool.mcp.McpToolInfo;
import com.tom.rv2ide.ai.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * MCP 服务端配置与工具接入。
 *
 * <p><b>为什么值得暴露</b>：MCP 是本 App 唯一的「外接能力」通道——用户可以把任意
 * MCP server（本地 stdio 或远程 HTTP/SSE）接进来，模型立刻多出一批工具。
 * 没有这个入口，助手的工具集就固定死在内置的那十几个。
 *
 * <p><b>失败一律跳过</b>：某个 server 连不上不该让整个对话不可用。拉取工具列表失败时
 * 记录原因并跳过，其余照常。这与 ACS 的取舍一致。
 *
 * <p><b>每次运行重新拉取</b>：工具列表可能随 server 升级变化，缓存会让用户改了配置
 * 后看不到新工具。
 */
public final class McpSettings {

  private static final String PREFS_NAME = "assistant_mcp";
  private static final String KEY_LIST = "servers";

  /** 一条 MCP server 配置。 */
  public static final class Server {
    public final String id;
    public final String label;
    /** HTTP/SSE 为端点 URL；stdio 为启动命令。 */
    public final String target;
    /** http / sse / stdio。 */
    public final String transport;
    public final boolean enabled;

    public Server(String id, String label, String target, String transport, boolean enabled) {
      this.id = id == null ? "" : id;
      this.label = label == null ? "" : label;
      this.target = target == null ? "" : target;
      this.transport = transport == null ? "http" : transport;
      this.enabled = enabled;
    }

    JSONObject toJson() throws JSONException {
      return new JSONObject()
          .put("id", id)
          .put("label", label)
          .put("target", target)
          .put("transport", transport)
          .put("enabled", enabled);
    }

    static Server fromJson(JSONObject o) {
      return new Server(
          o.optString("id", ""),
          o.optString("label", ""),
          o.optString("target", ""),
          o.optString("transport", "http"),
          o.optBoolean("enabled", true));
    }
  }

  /** 一次接入的结果：注册了几个工具、哪些 server 失败。 */
  public static final class AttachResult {
    public final int toolCount;
    public final List<String> failures;

    AttachResult(int toolCount, List<String> failures) {
      this.toolCount = toolCount;
      this.failures = failures == null ? Collections.<String>emptyList() : failures;
    }
  }

  private final SharedPreferences prefs;

  public McpSettings(Context context) {
    this.prefs =
        context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  /** 全部 server 配置（含未启用的）。 */
  public List<Server> all() {
    List<Server> out = new ArrayList<>();
    String raw = prefs.getString(KEY_LIST, "");
    if (raw == null || raw.isEmpty()) {
      return out;
    }
    try {
      JSONArray arr = new JSONArray(raw);
      for (int i = 0; i < arr.length(); i++) {
        JSONObject o = arr.optJSONObject(i);
        if (o != null) {
          out.add(Server.fromJson(o));
        }
      }
    } catch (JSONException e) {
      // 存储损坏：返回空表而不是抛异常，用户重新配置一次即可。
    }
    return out;
  }

  /** 已启用的 server。 */
  public List<Server> enabled() {
    List<Server> out = new ArrayList<>();
    for (Server server : all()) {
      if (server.enabled && !server.target.isEmpty()) {
        out.add(server);
      }
    }
    return out;
  }

  public void add(String label, String target, String transport) {
    List<Server> list = all();
    list.add(new Server("mcp_" + System.currentTimeMillis(), label, target, transport, true));
    save(list);
  }

  public void update(String id, String label, String target, String transport, boolean enabled) {
    List<Server> list = all();
    for (int i = 0; i < list.size(); i++) {
      if (list.get(i).id.equals(id)) {
        list.set(i, new Server(id, label, target, transport, enabled));
        break;
      }
    }
    save(list);
  }

  public void remove(String id) {
    List<Server> list = all();
    list.removeIf(s -> s.id.equals(id));
    save(list);
  }

  private void save(List<Server> list) {
    JSONArray arr = new JSONArray();
    for (Server server : list) {
      try {
        arr.put(server.toJson());
      } catch (JSONException ignored) {
        // 单条序列化失败不拖垮整表。
      }
    }
    prefs.edit().putString(KEY_LIST, arr.toString()).apply();
  }

  /**
   * 把已启用的 MCP server 的工具注册进注册表。
   *
   * <p>返回的 {@link AttachResult} 带失败原因，供界面如实展示——「接上了但工具没出现」
   * 是最难排查的状态，必须让用户看到哪个 server 失败、为什么。
   *
   * <p><b>调用方负责关闭</b>：成功接入的 client 会留在返回的列表里，运行结束后必须
   * 逐个 close，否则 SSE 长连接会一直挂着。
   */
  public AttachResult attach(
      AppHttpPort http, ToolRegistry registry, List<McpClient> clientsOut) {
    List<String> failures = new ArrayList<>();
    int toolCount = 0;
    Set<String> usedNames = new HashSet<>();
    for (com.tom.rv2ide.ai.tool.BaseTool existing : registry.getAll()) {
      usedNames.add(existing.getName());
    }

    for (Server server : enabled()) {
      McpClient client = null;
      try {
        client =
            new McpClient(
                http,
                server.target,
                Collections.<String, String>emptyMap(),
                McpClient.Transport.fromId(server.transport));
        List<McpToolInfo> tools = client.listTools();
        String label = server.label.isEmpty() ? server.target : server.label;
        for (McpToolInfo info : tools) {
          McpToolAdapter adapter = new McpToolAdapter(client, info, label);
          // 重名跳过：内置工具优先，避免外部 server 覆盖掉 file_read 这类核心工具。
          if (usedNames.add(adapter.getName())) {
            registry.register(adapter);
            toolCount++;
          }
        }
        clientsOut.add(client);
      } catch (Exception e) {
        // 失败就地释放：SSE 传输此时可能已经建好长连接。
        if (client != null) {
          try {
            client.close();
          } catch (Exception ignored) {
            // 关闭失败没有补救手段，忽略。
          }
        }
        String name = server.label.isEmpty() ? server.target : server.label;
        failures.add(name + "：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
      }
    }
    return new AttachResult(toolCount, failures);
  }
}

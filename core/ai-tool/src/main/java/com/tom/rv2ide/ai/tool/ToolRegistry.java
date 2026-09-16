/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from LineCode Pro (https://github.com/LangLang03/LineCodePro),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
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

package com.tom.rv2ide.ai.tool;

import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolInfo;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.json.JSONArray;

/**
 * 工具注册表：按名字登记与查找工具，线程安全。
 *
 * <p><b>与上游的差异</b>：LineCode Pro 的构造器接受 Android {@code Context} 与
 * {@code IpcProviderManager}，并内置从 Room 读取 MCP / Agent 扩展的逻辑
 * （{@code ExtensionStore}、{@code CustomMcpHttpTool}、{@code CustomAgentExtensionTool}）。
 *
 * <p>本移植去掉扩展工具能力，改为显式注册：调用方在构造后自行 {@link #register} 需要的工具。
 * 这样注册表不依赖 Android，也不依赖任何数据层。若将来需要 MCP 扩展，
 * 在 app 层实现一个把远程工具包装成 {@link BaseTool} 的适配器再注册进来即可。
 */
public final class ToolRegistry {

  private final Map<String, BaseTool> tools = new LinkedHashMap<>();
  private final Map<String, ToolDisplayCategory> displayCategoryCache = new LinkedHashMap<>();
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  /** 创建一个空注册表。工具需通过 {@link #register} 加入。 */
  public ToolRegistry() {}

  /** 创建注册表并登记给定工具。 */
  public ToolRegistry(Collection<? extends BaseTool> initial) {
    if (initial != null) {
      for (BaseTool tool : initial) {
        register(tool);
      }
    }
  }

  /** 登记（或覆盖）一个工具。 */
  public void register(BaseTool tool) {
    if (tool == null) {
      return;
    }
    lock.writeLock().lock();
    try {
      tools.put(tool.getName(), tool);
      displayCategoryCache.put(tool.getName(), tool.getDisplayCategory());
    } finally {
      lock.writeLock().unlock();
    }
  }

  /** 按名字查找工具；不存在返回 null。 */
  public BaseTool get(String name) {
    lock.readLock().lock();
    try {
      return tools.get(name);
    } finally {
      lock.readLock().unlock();
    }
  }

  /** 取缓存的展示分类；未登记时回退为 {@link ToolDisplayCategory#GENERIC}。 */
  public ToolDisplayCategory getCachedDisplayCategory(String name) {
    lock.readLock().lock();
    try {
      ToolDisplayCategory category = displayCategoryCache.get(name);
      return category != null ? category : ToolDisplayCategory.GENERIC;
    } finally {
      lock.readLock().unlock();
    }
  }

  /** 所有已登记工具的快照。 */
  public List<BaseTool> getAll() {
    lock.readLock().lock();
    try {
      return new ArrayList<>(tools.values());
    } finally {
      lock.readLock().unlock();
    }
  }

  /** 按名字集合筛选工具，保持登记顺序。 */
  public List<BaseTool> getByNameSet(Set<String> names) {
    ArrayList<BaseTool> selected = new ArrayList<>();
    if (names == null || names.isEmpty()) {
      return selected;
    }
    lock.readLock().lock();
    try {
      for (BaseTool tool : tools.values()) {
        if (names.contains(tool.getName())) {
          selected.add(tool);
        }
      }
    } finally {
      lock.readLock().unlock();
    }
    return selected;
  }

  /** 返回 {@link ToolInfo} 视图，供 AI 协议层使用而不依赖 BaseTool 具体类型。 */
  public List<ToolInfo> getToolInfoByNameSet(Set<String> names) {
    ArrayList<ToolInfo> selected = new ArrayList<>();
    if (names == null || names.isEmpty()) {
      return selected;
    }
    lock.readLock().lock();
    try {
      for (BaseTool tool : tools.values()) {
        if (names.contains(tool.getName())) {
          selected.add(tool);
        }
      }
    } finally {
      lock.readLock().unlock();
    }
    return selected;
  }

  /** 已登记的工具名集合。 */
  public Set<String> getToolNames() {
    lock.readLock().lock();
    try {
      return new java.util.LinkedHashSet<>(tools.keySet());
    } finally {
      lock.readLock().unlock();
    }
  }

  /** 序列化为 OpenAI tools 数组格式。 */
  public static JSONArray toJsonArray(Collection<BaseTool> tools)
      throws org.json.JSONException {
    JSONArray array = new JSONArray();
    if (tools == null) {
      return array;
    }
    for (BaseTool tool : tools) {
      array.put(tool.toJson());
    }
    return array;
  }

  /** {@link ToolInfo} 版本的序列化，供 AI 协议层使用。 */
  public static JSONArray toToolInfoJsonArray(Collection<ToolInfo> tools)
      throws org.json.JSONException {
    JSONArray array = new JSONArray();
    if (tools == null) {
      return array;
    }
    for (ToolInfo tool : tools) {
      array.put(tool.toJson());
    }
    return array;
  }

  /** 名字是否带扩展工具前缀（{@code agentx_} / {@code mcpx_}）。 */
  public static boolean isExtensionToolName(String name) {
    return ToolNames.isExtensionToolName(name);
  }

  /** 名字是否为自定义 Agent 工具。 */
  public static boolean isCustomAgentToolName(String name) {
    return ToolNames.isCustomAgentToolName(name);
  }

  /** 名字是否为自定义 MCP 工具。 */
  public static boolean isCustomMcpToolName(String name) {
    return ToolNames.isCustomMcpToolName(name);
  }
}

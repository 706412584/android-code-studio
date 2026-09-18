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
import java.util.Collections;
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

  /**
   * 工具名别名 → 规范名。
   *
   * <p><b>为什么需要</b>：模型的工具命名先验来自训练语料里大量 `read` / `write` / `edit` /
   * `bash` 风格的调用，即使 tools 定义里写的是 `file_read`，它仍会时不时发出 `read`。
   * 实测一次构建排障中，模型连续 5 次调用不存在的 `read`，每次白烧一轮。
   *
   * <p>这里只做<b>查找期</b>归一，不改变对外暴露的规范名——规范名与移植源保持一致，
   * 而模型用哪套名字都能跑通。别名不会遮蔽同名工具：精确匹配优先。
   */
  // 用静态块逐个 put，而不是 Map.ofEntries(Map.entry(...))：后者是 Java 9 API，
  // Android 上直到 API 30 才有。这类问题编译期（JDK 17）与 JVM 单测都发现不了，
  // 只在真机上抛 NoSuchMethodError。模块已配 options.release = 8 来拦住它。
  private static final Map<String, String> ALIASES;

  static {
    Map<String, String> aliases = new LinkedHashMap<>();
    aliases.put("read", ToolNames.FILE_READ);
    aliases.put("read_file", ToolNames.FILE_READ);
    aliases.put("write", ToolNames.FILE_WRITE);
    aliases.put("write_file", ToolNames.FILE_WRITE);
    aliases.put("create_file", ToolNames.FILE_WRITE);
    aliases.put("edit", ToolNames.FILE_EDIT);
    aliases.put("edit_file", ToolNames.FILE_EDIT);
    aliases.put("replace", ToolNames.FILE_EDIT);
    aliases.put("delete", ToolNames.FILE_DELETE);
    aliases.put("delete_file", ToolNames.FILE_DELETE);
    aliases.put("rm", ToolNames.FILE_DELETE);
    aliases.put("ls", ToolNames.LIST_DIR);
    aliases.put("list", ToolNames.LIST_DIR);
    aliases.put("list_files", ToolNames.LIST_DIR);
    aliases.put("list_directory", ToolNames.LIST_DIR);
    aliases.put("find", ToolNames.GLOB);
    aliases.put("search_files", ToolNames.GLOB);
    aliases.put("bash", ToolNames.SHELL_EXECUTE);
    aliases.put("shell", ToolNames.SHELL_EXECUTE);
    aliases.put("run", ToolNames.SHELL_EXECUTE);
    aliases.put("exec", ToolNames.SHELL_EXECUTE);
    aliases.put("run_command", ToolNames.SHELL_EXECUTE);
    ALIASES = Collections.unmodifiableMap(aliases);
  }

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

  /**
   * 按名字查找工具；不存在返回 null。
   *
   * <p>精确匹配优先；未命中时尝试别名归一（如模型发 `read` → {@code file_read}）。
   */
  public BaseTool get(String name) {
    if (name == null) {
      return null;
    }
    lock.readLock().lock();
    try {
      BaseTool exact = tools.get(name);
      if (exact != null) {
        return exact;
      }
      String canonical = ALIASES.get(name);
      return canonical == null ? null : tools.get(canonical);
    } finally {
      lock.readLock().unlock();
    }
  }

  /** 把可能的别名归一为规范名；无法归一或已是规范名时原样返回。 */
  public static String canonicalName(String name) {
    if (name == null) {
      return null;
    }
    String canonical = ALIASES.get(name);
    return canonical == null ? name : canonical;
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

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

package com.tom.rv2ide.ai.tool;

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/**
 * CodeGraph 代码知识图谱查询。
 *
 * <p>为什么需要它：CodeGraph 的索引（{@code .codegraph/}）早已在设备上构建，CLI
 * （{@code acs-codegraph}）也能跑，但**此前没有注册成 AI 工具** —— 助手只能靠
 * {@code shell_execute} 手敲命令，而且它并不知道有这个东西存在，于是回答「找不到
 * codegraph 工具」。本工具把查询能力正式暴露给模型。
 *
 * <p>为什么走 shell 而不是直接调 {@code CodeGraphManager}：本模块（{@code core/ai-tool}）
 * 是**纯 Java、零 Android 依赖**，因此能在 JVM 上单测；而 {@code CodeGraphManager} 在
 * app 模块且依赖 Android。复用既有的 {@link ShellBackendRegistry}（与
 * {@code shell_execute} 同一套后端）即可，无需把 Android 依赖引入本模块。
 *
 * <p>为什么命令名是 {@code acs-codegraph}：它是 ACS 生成的 wrapper（在 App 自己的
 * {@code files/usr/bin} 里），负责设置 {@code PATH}/{@code LD_LIBRARY_PATH}/{@code HOME}
 * 并 exec node。直接写 {@code codegraph} 会因不在 PATH 而 command not found。
 */
public final class CodeGraphTool extends BaseTool {

  /** 各 action 允许的子命令白名单：防止模型把任意字符串拼进命令行。 */
  private static final Map<String, String> ACTIONS = new LinkedHashMap<>();

  static {
    ACTIONS.put("query", "按名字搜索符号（类/方法/字段），返回 file:line");
    ACTIONS.put("explore", "理解一个功能区域：相关符号源码 + 调用路径，一次返回");
    ACTIONS.put("node", "单个符号的源码 + 调用者/被调用者轨迹");
    ACTIONS.put("callers", "谁调用了这个符号");
    ACTIONS.put("callees", "这个符号调用了谁");
    ACTIONS.put("impact", "改这个符号会影响哪些代码");
    ACTIONS.put("files", "项目文件结构（来自索引）");
    ACTIONS.put("status", "索引状态与统计");
  }

  /** 查询类超时：2 分钟（与 shell_execute 默认一致）。 */
  private static final long TIMEOUT_MS = 120_000L;

  /** 返回给模型的输出上限：32KB，超出保留首尾。 */
  private static final int MAX_OUTPUT_CHARS = 32 * 1024;

  private final ShellBackendRegistry registry;

  public CodeGraphTool(ShellBackendRegistry registry) {
    this.registry = registry;
  }

  @Override
  public String getName() {
    return ToolNames.CODEGRAPH;
  }

  @Override
  public String getDescription() {
    return "查询项目的 CodeGraph 代码知识图谱：按符号名找源码位置、调用关系、改动影响面。"
        + "比逐个 grep 文件更快更准（结果基于已解析的引用图）。"
        + "适用于「这个函数在哪定义」「谁调用了它」「改这里会影响什么」。"
        + "action=explore 最常用：给一段自然语言描述，一次拿到相关符号源码与调用路径。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.READ;
  }

  /** 只读查询，与 file_read 等同级，只读模式下放行。 */
  @Override
  public boolean isAllowedInReadonlyMode() {
    return true;
  }

  /**
   * 就绪探测。由 app 层注入（见 {@link #setAvailabilityProbe}）——本模块是纯 Java、
   * 零 Android 依赖，无法自己知道安装目录在哪（路径常量在 termux 模块、且按包名替换）。
   */
  public interface AvailabilityProbe {
    /** codegraph 是否已安装且入口/运行时就绪。 */
    boolean isReady();

    /**
     * wrapper 的**绝对路径**（如 {@code /data/data/<pkg>/files/usr/bin/acs-codegraph}）。
     *
     * <p>为什么不能只写裸名 {@code acs-codegraph}：AI 的 shell 后端执行命令时
     * **不设置 PATH**（实测 `$PREFIX` 为空），裸名会 `command not found`。真机实测确认：
     * 绝对路径可跑通，裸名不行。
     *
     * <p>未安装时返回 null/空串，此时 {@link #isReady()} 也应为 false。
     */
    String wrapperPath();
  }

  /** 进程级探测回调。未注入时按「不可用」处理，措辞偏保守。 */
  private static volatile AvailabilityProbe probe;

  /** app 启动时注入一次。 */
  public static void setAvailabilityProbe(AvailabilityProbe p) {
    probe = p;
  }

  /**
   * 工具清单里的补充说明——**只在 codegraph 真正可用时**才给出「优先用它」的强指引。
   *
   * <p>为什么条件化：系统提示词是静态模板，无法感知本机是否装了 codegraph、项目是否
   * 建了索引。若无条件叫模型「优先用 codegraph」，在没装的环境里只会换来一堆失败调用。
   * 这里按实际状态给不同措辞：可用 → 明确要求优先；未装 → 告知改用 grep/glob。
   */
  @Override
  public String promptSupplement(String executionMode) {
    AvailabilityProbe p = probe;
    if (p != null && p.isReady()) {
      return "搜索代码**优先用本工具**（按符号查定义/调用关系/影响面），比 grep 快且准；"
          + "仅当查非代码内容（字符串/注释/配置）或按文件名查找时才用 grep/glob。";
    }
    return "当前未检测到可用的 codegraph（未安装或未建索引）——请改用 grep/glob 检索，"
        + "并可在「设置 → AI 助手 → CodeGraph」安装后重启会话。";
  }

  @Override
  public boolean isConcurrencySafe() {
    // 多个查询互不依赖，可并发（codegraph 自身对索引是只读的）。
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    JSONObject action = new JSONObject().put("type", "string").put("description",
        "查询类型，见下。默认 explore");
    StringBuilder hint = new StringBuilder("可用值：");
    for (Map.Entry<String, String> e : ACTIONS.entrySet()) {
      hint.append("\n- ").append(e.getKey()).append(": ").append(e.getValue());
    }
    action.put("description", action.getString("description") + "\n" + hint);
    action.put("enum", new org.json.JSONArray(ACTIONS.keySet()));
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put("action", action)
                .put(
                    "query",
                    new JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "查询内容：符号名（query/node/callers/callees/impact）"
                                + "或自然语言描述（explore）。files/status 可省略")))
        .put("required", new org.json.JSONArray().put("query"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String action = input.optString("action", "explore").trim().toLowerCase();
    String query = input.optString("query", "").trim();

    if (!ACTIONS.containsKey(action)) {
      return error("不支持的 action：" + action + "。可用：" + String.join(", ", ACTIONS.keySet()));
    }
    if (query.isEmpty() && !action.equals("files") && !action.equals("status")) {
      return error("action=" + action + " 需要 query 参数。");
    }

    ShellBackendRegistry.Resolution resolution = registry.resolveActive();
    if (!resolution.isUsable()) {
      return error(
          "没有可用的 shell 后端，无法执行 codegraph。请在设置里启用 Termux 或 Shizuku 后端。");
    }

    // 必须用 wrapper 的**绝对路径**：shell 后端不设 PATH，裸名会 command not found
    // （真机实测确认）。
    AvailabilityProbe p = probe;
    String wrapper = p == null ? null : p.wrapperPath();
    if (wrapper == null || wrapper.trim().isEmpty()) {
      return error(
          "codegraph 未安装或路径未知。请到「设置 → AI 助手 → CodeGraph」安装后重试。");
    }

    // 工作目录：项目根。codegraph 从 cwd 向上找 .codegraph/。
    String cwd = context.getHomePath();

    // 命令：<wrapper 绝对路径> <action> <query>。wrapper 与参数都用单引号包裹
    // （路径可能含空格），内部单引号做 '\'' 转义，避免注入。
    StringBuilder cmd = new StringBuilder(quote(wrapper)).append(' ').append(action);
    if (!query.isEmpty()) {
      cmd.append(' ').append(quote(query));
    }

    ShellRequest request = new ShellRequest(cmd.toString(), cwd, TIMEOUT_MS, null);
    ShellRequest.ShellResult result = resolution.getBackend().execute(request, null);

    String out = result.getStdout() == null ? "" : result.getStdout();
    String err = result.getStderr() == null ? "" : result.getStderr();

    if (result.isTimedOut()) {
      return error("codegraph " + action + " 超时（" + (TIMEOUT_MS / 1000) + "s）。"
          + "首次查询会加载索引，可重试；若持续超时请检查索引是否完整。");
    }
    if (result.getExitCode() != 0) {
      return error(codegraphFailure(action, out, err, result.getExitCode()));
    }
    if (out.trim().isEmpty()) {
      return ok("codegraph " + action + " 无结果" + (query.isEmpty() ? "" : "：" + query)
          + "。可能是索引里没有该符号，或索引未覆盖此项目。");
    }
    return ok(truncate(out));
  }

  /** 单引号包裹并转义内部单引号，安全传给 `/system/bin/sh -c`。 */
  private static String quote(String s) {
    return "'" + s.replace("'", "'\\''") + "'";
  }

  /** 失败时给出可操作的诊断，而不是把裸退出码丢给模型。 */
  private static String codegraphFailure(String action, String out, String err, int exitCode) {
    String combined = (err + "\n" + out).toLowerCase();
    if (combined.contains("command not found") || combined.contains("not found")) {
      return "找不到 acs-codegraph 命令。请到「设置 → AI 助手 → CodeGraph」确认已安装并构建索引。"
          + "\n原始输出：" + truncate(err.isEmpty() ? out : err);
    }
    if (combined.contains("not initialized")
        || combined.contains("no index")
        || combined.contains(".codegraph")) {
      return "此项目尚未建立 CodeGraph 索引。请到「设置 → AI 助手 → CodeGraph」"
          + "对当前项目点「初始化索引」。\n原始输出：" + truncate(err.isEmpty() ? out : err);
    }
    return "codegraph " + action + " 失败（退出码 " + exitCode + "）："
        + truncate(err.isEmpty() ? out : err);
  }

  /** 首尾保留、中间省略，避免超长输出灌爆上下文。 */
  private static String truncate(String s) {
    if (s == null) {
      return "";
    }
    String t = s.trim();
    if (t.length() <= MAX_OUTPUT_CHARS) {
      return t;
    }
    int head = MAX_OUTPUT_CHARS * 2 / 3;
    int tail = MAX_OUTPUT_CHARS - head;
    return t.substring(0, head)
        + "\n\n…（省略 "
        + (t.length() - MAX_OUTPUT_CHARS)
        + " 字符）…\n\n"
        + t.substring(t.length() - tail);
  }
}

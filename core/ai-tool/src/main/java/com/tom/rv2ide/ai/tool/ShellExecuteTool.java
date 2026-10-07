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

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.List;
import org.json.JSONObject;

/**
 * 执行 shell 命令。
 *
 * <p>后端由 {@link ShellBackendRegistry} 决定：优先用配置选定的后端，不可用时回退，
 * 并在结果里如实说明实际使用的后端——权限级别不同，能执行的命令也不同。
 *
 * <p><b>模型可临时指定后端</b>（{@code backend} 参数）：默认走用户设置的后端，
 * 但「这条命令需要 adb 权限」还是「需要 git」只有模型自己知道，因此允许它为单次
 * 调用指定后端，且不改动用户设置。指定不可用的后端会回退并在结果里说明原因——
 * 直接失败会让模型卡在「选对了却执行不了」，而它往往不知道那台设备没装该后端。
 *
 * <p>命令因找不到可执行文件而失败时，结果里追加「可换哪个后端」的提示，但
 * **不自动重试**：同一条命令在两个后端各跑一次可能重复副作用，且「命令找不到」
 * 只能靠 stderr 文本匹配判定，不可靠。
 *
 * <p><b>与上游的差异</b>：LineCode Pro 的 {@code ShellExecuteTool} 内联了 SSH /
 * TerminalProvider / proot 三种分支，且硬编码了「是否为内置 provider」的判断。
 * 本移植改为面向 {@link ShellBackend} 接口，后端差异被隔离在各自的实现里。
 * 命令结果不再直接返回给模型，而是先经过长度截断，避免一次 {@code cat} 大文件
 * 就把上下文撑爆。
 */
public final class ShellExecuteTool extends BaseTool {

  /** 返回给模型的输出上限：32KB。超出时保留首尾，中间省略。 */
  private static final int MAX_OUTPUT_CHARS = 32 * 1024;

  /** 单次命令允许的最长超时：10 分钟。 */
  private static final long MAX_TIMEOUT_MS = 10 * 60 * 1000L;

  /**
   * 单次命令允许的最短超时：1 秒。
   *
   * <p><b>为什么需要下限</b>：模型常把 {@code timeoutMs} 当成「秒」来填，于是写出
   * {@code timeoutMs: 10} 表示「10 秒」。但字段单位是毫秒，10 就成了「10 毫秒」——
   * 命令连 fork 都来不及，一律被 kill。实测设备会话里 15 次 shell 调用有 5 次
   * 填了 10/30，全部失败，模型反复重试同一件事。
   *
   * <p>夹到 1 秒之后，这类误填会变成「命令正常跑完」而不是「必然超时」，
   * 模型从结果里看到成功就会继续往下走。真正的短超时（例如轮询某文件出现）
   * 用 1 秒也够——那类需求本来就该在命令里自己循环。
   */
  private static final long MIN_TIMEOUT_MS = 1_000L;

  private final ShellBackendRegistry registry;

  public ShellExecuteTool(ShellBackendRegistry registry) {
    this.registry = registry;
  }

  @Override
  public String getName() {
    return ToolNames.SHELL_EXECUTE;
  }

  @Override
  public String getDescription() {
    return "在本机执行 shell 命令并返回退出码与输出。"
        + "可用于运行脚本、查看文件、调用系统命令。"
        + "命令由「后端」执行，不同后端能用的命令不同："
        + "一个提供 adb 权限（pm/am/logcat），另一个提供命令行工具链（git/node/python）。"
        + "默认用用户设置的后端；需要另一个后端的能力时，用 backend 参数临时指定。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.SYSTEM;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.SHELL;
  }

  @Override
  public boolean needsConfirmation() {
    // 执行任意命令是有副作用的操作，确认模式下应经用户同意。
    return true;
  }

  @Override
  public boolean isConcurrencySafe() {
    // 命令可能有依赖顺序（如先 cd 再执行），串行执行更可预期。
    return false;
  }

  /**
   * 告诉模型「这台设备上有哪些后端、各自能做什么、什么时候该指定哪个」。
   *
   * <p><b>为什么必须动态生成</b>：后端的能力差异（adb 权限 vs 命令行工具链）是本机
   * 事实，静态模板写不出来；而模型恰恰需要这个事实才能一次选对后端。不写清楚的话，
   * 它会用默认后端跑 {@code git}，失败后反复重试同一条命令。
   *
   * <p>只有一个可用后端时不啰嗦——直接说明没有可选项，避免模型浪费时间纠结。
   */
  @Override
  public String promptSupplement(String executionMode) {
    if (registry == null) {
      return null;
    }
    List<ShellBackend> available = registry.available();
    if (available.isEmpty()) {
      return null;
    }

    ShellBackendRegistry.Resolution active = registry.resolveActive();
    // 「当前默认」标记只在有得选时才有意义——单后端下它既无信息量，也没有配套的
    // 选择指引（见下），只会让输出显得自相矛盾。
    boolean hasChoice = available.size() > 1;

    StringBuilder sb = new StringBuilder();
    sb.append("后端决定这条命令能用到什么：");
    for (ShellBackend backend : available) {
      sb.append("\n- ").append(backend.id()).append(": ").append(backend.capabilitySummary());
      if (hasChoice && active.isUsable() && active.getBackend() == backend) {
        sb.append("（当前默认）");
      }
    }

    if (hasChoice) {
      sb.append(
          "\n默认用上面标「当前默认」的那个。"
              + "仅当命令依赖另一个后端的独有能力时，才在本次调用里填 backend 参数指定它"
              + "——它只作用于这一次调用，不会改动用户设置，用完不必切回。"
              + "若拿不准，先用默认后端试一次：失败时结果里会提示可换的后端。");
    }
    return sb.toString();
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "command",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "要执行的 shell 命令"))
                .put(
                    "cwd",
                    new JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "工作目录。**省略时默认就是当前项目根目录**，"
                                + "所以执行项目内命令（./gradlew、git 等）不需要传这个参数，"
                                + "也不需要先在 command 里 cd。仅在要切到项目外的目录时才传。"))
                .put(
                    "timeoutMs",
                    new JSONObject()
                        .put("type", "number")
                        .put(
                            "description",
                            "超时**毫秒数**（不是秒）。默认 120000（2 分钟），"
                                + "最小 "
                                + MIN_TIMEOUT_MS
                                + "，最大 "
                                + MAX_TIMEOUT_MS
                                + "。想给 30 秒就填 30000，填 30 表示 30 毫秒。"
                                + "构建等耗时命令建议 600000。"))
                .put(
                    "backend",
                    new JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "**通常不需要填**——省略即用用户设置的后端。"
                                + "仅当本次命令依赖某个后端独有的能力时才指定，"
                                + "用完不必切回（它只作用于这一次调用，不改用户设置）。"
                                + "取值见工具说明；指定不可用的后端会自动回退并说明原因。")))
        .put("required", new org.json.JSONArray().put("command"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String command = input.optString("command", "").trim();
    if (command.isEmpty()) {
      return error("command 不能为空");
    }
    if (registry == null) {
      return error("shell 后端未配置");
    }

    // 模型可为单次调用临时指定后端（见 backend 参数说明）；省略时用用户配置的后端。
    String requestedBackend = input.optString("backend", "").trim();
    ShellBackendRegistry.Resolution resolution = registry.resolve(requestedBackend);
    if (!resolution.isUsable()) {
      return error("没有可用的 shell 后端。" + resolution.getFallbackReason());
    }
    ShellBackend backend = resolution.getBackend();

    String cwd = input.optString("cwd", "").trim();
    if (cwd.isEmpty() && context != null) {
      cwd = context.getHomePath();
    }

    long timeout = (long) input.optDouble("timeoutMs", ShellRequest.DEFAULT_TIMEOUT_MS);
    if (timeout <= 0) {
      timeout = ShellRequest.DEFAULT_TIMEOUT_MS;
    }
    // 下限 1 秒，上限 10 分钟。两侧都要夹：低于下限会把「模型把毫秒当秒填」变成
    // 必然超时，高于上限会让一次调用独占 agent 循环十分钟。
    timeout = Math.max(MIN_TIMEOUT_MS, Math.min(timeout, MAX_TIMEOUT_MS));

    if (context != null) {
      context.reportProgress("执行命令: " + abbreviate(command, 80));
    }

    ShellRequest request = new ShellRequest(command, cwd, timeout, null);
    ShellRequest.ShellResult result;
    try {
      result = backend.execute(request, ShellRequest.ShellOutputSink.NOOP);
    } catch (RuntimeException e) {
      ExceptionUtils.restoreInterrupt(e);
      return error("命令执行异常: " + ExceptionUtils.describeException(e));
    }

    StringBuilder sb = new StringBuilder();
    sb.append("[后端: ").append(backend.displayName()).append("]\n");
    // 回显工作目录：让模型**看到**自己站在哪，而不是只能从参数描述里推断。
    //
    // 实测问题：模型不知道默认 cwd 就是项目根，于是每次都在命令里写
    // `cd /data/data/.../ACSProjects/MyGameActivity && ./gradlew ...`——既啰嗦，
    // 又因为绝对路径写错一次就整条命令失败。回显之后模型能直接确认「已经在项目根」，
    // 后续调用就会省略 cd。
    if (!cwd.isEmpty()) {
      sb.append("[工作目录: ").append(cwd).append("]\n");
    }
    if (resolution.isFallback()) {
      // 如实报告回退，避免模型以为用的是有特权的后端
      sb.append("[注意: 已回退 — ").append(resolution.getFallbackReason()).append("]\n");
    }
    sb.append(truncate(result.toDisplayText()));

    // 「命令找不到」这类失败换个后端往往就能解决，但**不自动重试**——
    // 同一条命令在两个后端各跑一次可能重复副作用，且判定只能靠 stderr 文本匹配，
    // 不可靠。给提示、让模型自己决定，副作用可控且它能看到两次的差异。
    if (!result.isSuccess()) {
      appendBackendHintIfExecutableMissing(sb, result, backend);
    }

    // 命令失败也是有效结果（模型需要看到错误去修正），因此只在超时时标记为错误。
    if (result.isTimedOut()) {
      return error(sb.toString());
    }
    return ok(sb.toString());
  }

  /**
   * 命令因「找不到可执行文件」失败时，提示可用后端及其能力，引导模型换后端重试。
   *
   * <p>典型场景：Shizuku 后端只有 Android 系统 PATH，{@code git} / {@code node} /
   * {@code python} 都不在其中，而它们装在 Termux 的 bin 里（那是 app 私有目录，
   * shell 身份读不到，改 PATH 也解决不了）。模型看到「inaccessible or not found」
   * 时并不知道「设备上其实有 git，只是这个后端看不见」——不提示的话它只会反复重试
   * 或改用别的笨办法。
   */
  private void appendBackendHintIfExecutableMissing(
      StringBuilder sb, ShellRequest.ShellResult result, ShellBackend backend) {
    String combined = (result.getStdout() + "\n" + result.getStderr()).toLowerCase();
    boolean missing =
        combined.contains("inaccessible or not found")
            || combined.contains("command not found")
            || combined.contains("not found")
            || combined.contains("no such file or directory")
            || combined.contains("permission denied");
    if (!missing) {
      return;
    }

    // 列出**其它**可用后端的能力，让模型知道还有什么选择；没有别的可用后端时只说明现状。
    StringBuilder others = new StringBuilder();
    for (ShellBackend candidate : registry.all()) {
      if (candidate == backend || !candidate.isAvailable()) {
        continue;
      }
      others.append("\n- ").append(candidate.id()).append(": ").append(candidate.capabilitySummary());
    }

    sb.append("\n[提示: 本次用的是 ").append(backend.displayName()).append("。");
    if (backend.hasCliToolchain()) {
      sb.append("该后端提供命令行工具链，但仍找不到命令——可能确实没安装。");
    } else {
      sb.append("它只提供 Android 系统命令，git/node/python 等命令行工具不在其中。");
    }
    if (others.length() > 0) {
      sb.append("\n换后端可能解决，填 backend 参数即可（仅作用于本次调用）：").append(others);
    }
    sb.append("]");
  }

  /** 中间截断：保留首尾，中间标注省略量。 */
  static String truncate(String text) {
    if (text == null || text.length() <= MAX_OUTPUT_CHARS) {
      return text == null ? "" : text;
    }
    int half = MAX_OUTPUT_CHARS / 2;
    int omitted = text.length() - MAX_OUTPUT_CHARS;
    return text.substring(0, half)
        + "\n…（省略 " + omitted + " 字符）…\n"
        + text.substring(text.length() - half);
  }

  private static String abbreviate(String value, int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(0, max) + "…";
  }
}

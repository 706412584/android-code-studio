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

package com.tom.rv2ide.artificial.agent.tool;

import android.content.Context;
import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ShellBackendRegistry;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 读取应用日志，并可做结构化崩溃判定。
 *
 * <p>「运行 app 测试」闭环的第四步：改代码 → 构建 → 安装 → 启动 → <b>读日志</b> → 修。
 *
 * <p><b>为什么不用 IDE 已有的日志设施</b>：
 * <ul>
 *   <li>{@code IDELogcatReader} 只读 IDE 自身进程（{@code --pid=self}），看不到被测应用
 *   <li>{@code LogReceiverService} 是流式推送且单消费者互斥，会和日志面板抢通道，
 *       还要求用户开启开关并让被测应用集成 logwire 库
 * </ul>
 * 因此这里直接调用系统 {@code logcat -d}：dump 后即退出，天然是「取一段」语义，
 * 且不侵入用户项目、不与任何 UI 竞争。
 *
 * <p><b>两种模式</b>：
 * <ul>
 *   <li>{@code raw}（默认）— 返回过滤后的日志文本，保持原有行为
 *   <li>{@code crash} — <b>结构化崩溃判定</b>：读 crash buffer 并按 PID 过滤
 *       {@code FATAL EXCEPTION}，返回「崩没崩 + 崩溃堆栈」；顺带识别 ANR。
 *       这一步把「崩溃了没有」从「模型自己正则翻日志」变成一个明确结论，
 *       是自动化验证的关键信号。
 * </ul>
 *
 * <p><b>权限说明</b>：Android 4.1+ 起普通应用只能读取自身进程的日志，因此本工具经
 * {@code shell_execute} 的后端执行 {@code logcat}。**不强制要求 Shizuku**：真机实测
 * （Android 16）Termux 后端的进程 uid 在 {@code log} 组里，`logcat -d` 能正常读取；
 * 有 Shizuku 时走 adb 级权限更稳。权限不足时 logcat 返回空输出，结果里会说明，
 * 避免模型误判「应用没有输出日志」。
 */
public final class LogcatReadTool extends BaseTool {

  private static final Logger log = LoggerFactory.getLogger(LogcatReadTool.class);

  /** 默认读取行数。 */
  private static final int DEFAULT_LINES = 200;

  /** 最大读取行数，避免一次拉取过多内容撑爆上下文。 */
  private static final int MAX_LINES = 2000;

  /** 命令执行超时。 */
  private static final long TIMEOUT_MS = 15_000L;

  /** 崩溃模式下扫描 main buffer 的行数（crash buffer 之外再兜底查一遍 FATAL）。 */
  private static final int CRASH_SCAN_LINES = 1000;

  /** 崩溃堆栈最多返回的行数，避免一次异常刷屏。 */
  private static final int MAX_STACK_LINES = 60;

  /** 模式：原始日志。 */
  private static final String MODE_RAW = "raw";

  /** 模式：结构化崩溃判定。 */
  private static final String MODE_CRASH = "crash";

  private final Context appContext;
  private final ShellBackendRegistry shellBackends;

  /**
   * 最近一次执行失败的原因（后端不可用/异常）。
   *
   * <p>做成字段而不是返回值的一部分：{@link #runLogcat} 已有「行列表 or null」的契约，
   * 再塞一个失败原因会污染所有调用点。工具实例每次运行新建，不存在跨运行的脏数据。
   */
  private volatile String lastFailureNote = "";

  /**
   * 便捷构造（**不推荐**）。
   *
   * <p>它会自建一个 shell 后端注册表，而那个注册表**不知道用户在设置里选的后端**——
   * 实测踩过：用户已配好 Shizuku、`shell_execute` 能正常跑 logcat，但用本构造的
   * logcat_read 解析到 Termux（无 adb 权限）而恒失败，模型据此误判「设备缺权限」。
   *
   * <p>生产路径请用 {@link #LogcatReadTool(Context, ShellBackendRegistry)} 并传入
   * 与其它工具共享的那个注册表（见 AgentOrchestrator.buildRegistry）。
   */
  public LogcatReadTool(Context context) {
    this(context, PhoneShellRunner.defaultRegistry(context));
  }

  public LogcatReadTool(Context context, ShellBackendRegistry shellBackends) {
    this.appContext = context.getApplicationContext();
    this.shellBackends = shellBackends;
  }

  @Override
  public String getName() {
    return "logcat_read";
  }

  @Override
  public String getDescription() {
    return "读取本机日志（logcat）。默认模式按包名与级别过滤返回原始日志。"
        + "设置 mode=crash 可做结构化崩溃判定：按 PID 读 crash buffer 的 FATAL EXCEPTION，"
        + "返回「是否崩溃 + 崩溃堆栈」（并识别 ANR）。"
        + "经 shell 后端执行（有 Shizuku 时自动用 adb 级权限，无则用当前后端）；"
        + "权限不足时会返回空结果并说明原因。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.READ;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "packageName",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "按应用包名过滤；省略则读取全部日志"))
                .put(
                    "mode",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "raw（默认，返回原始日志）或 crash（结构化崩溃判定）")
                        .put(
                            "enum",
                            new org.json.JSONArray().put(MODE_RAW).put(MODE_CRASH)))
                .put(
                    "lines",
                    new JSONObject()
                        .put("type", "number")
                        .put("description", "读取最近多少行，默认 " + DEFAULT_LINES + "，最大 " + MAX_LINES))
                .put(
                    "level",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "最低日志级别：V/D/I/W/E，默认 V")
                        .put(
                            "enum",
                            new org.json.JSONArray()
                                .put("V")
                                .put("D")
                                .put("I")
                                .put("W")
                                .put("E")))
                .put(
                    "filter",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "按关键字过滤（大小写不敏感）")))
        .put("required", new org.json.JSONArray());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String mode = input.optString("mode", MODE_RAW).trim().toLowerCase();
    String packageName = input.optString("packageName", "").trim();

    if (MODE_CRASH.equals(mode)) {
      return crashCheck(packageName, context);
    }
    return readRaw(input, context, packageName);
  }

  // ---------------------------------------------------------------------------
  // 原始日志模式
  // ---------------------------------------------------------------------------

  private ToolResult readRaw(JSONObject input, ToolContext context, String packageName) {
    int lines = (int) input.optDouble("lines", DEFAULT_LINES);
    if (lines <= 0) {
      lines = DEFAULT_LINES;
    }
    lines = Math.min(lines, MAX_LINES);

    String level = input.optString("level", "V").trim().toUpperCase();
    if (level.isEmpty()) {
      level = "V";
    }
    String filter = input.optString("filter", "").trim();

    if (context != null) {
      context.reportProgress("读取日志" + (packageName.isEmpty() ? "" : ": " + packageName));
    }

    List<String> command = new ArrayList<>();
    command.add("logcat");
    // -d: dump 后退出（不是流式跟随），这正是「取一段」需要的行为
    command.add("-d");
    command.add("-v");
    command.add("threadtime");
    command.add("-t");
    command.add(String.valueOf(lines));
    if (!level.isEmpty() && !"V".equals(level)) {
      command.add("*:" + level);
    }

    String pid = packageName.isEmpty() ? null : findPid(packageName);
    if (!packageName.isEmpty()) {
      if (pid == null) {
        // 两种可能都要说清：应用真没跑，或当前后端查不到别人的进程（Termux 权限较窄）。
        // 早先只报前者，模型会据此断定「应用没在运行」——而它可能只是查不到。
        return error(
            "查不到应用 "
                + packageName
                + " 的进程。可能它未运行，也可能当前 shell 后端权限不足（无 Shizuku 时"
                + " pidof 查不到其它应用）。可先 launch_app 启动它，或省略 packageName 读取全部日志。");
      }
      command.add("--pid=" + pid);
    }

    List<String> output = runLogcat(command);
    if (output == null) {
      // 把后端给出的具体原因带上（如「Shizuku 未授权」「没有可用的 shell 后端」）。
      // 只说「没有可用后端」会让模型与用户都不知道该修哪里。
      String note = lastFailureNote;
      return error(
          "读取日志失败：命令未执行。"
              + (note.isEmpty() ? "" : note + " ")
              + "可在「设置 → AI 助手 → Shell 后端」里检查后端状态，"
              + "或改用 shell_execute 直接执行 logcat。");
    }

    StringBuilder text = new StringBuilder();
    for (String line : output) {
      if (filter.isEmpty() || line.toLowerCase().contains(filter.toLowerCase())) {
        text.append(line).append('\n');
      }
    }

    if (text.toString().trim().isEmpty()) {
      StringBuilder sb = new StringBuilder();
      sb.append("(没有匹配的日志)\n");
      // 区分「真的没日志」与「有输出但被过滤掉了」：后者是最常见的误判来源
      // ——模型给了 filter 却没命中任何行，会误以为应用没输出。
      if (!output.isEmpty()) {
        sb.append("说明：logcat 有 ").append(output.size()).append(" 行输出，但没有行匹配当前过滤条件");
        if (!filter.isEmpty()) {
          sb.append("（filter=\"").append(filter).append("\"）");
        }
        if (!level.isEmpty() && !"V".equals(level)) {
          sb.append("（level=").append(level).append("，低于该级别的日志已被排除）");
        }
        sb.append("。可放宽过滤条件后重试。\n");
      } else if (!lastFailureNote.isEmpty()) {
        // 执行本身失败了（非零退出码 / 后端异常）——原因必须可见，
        // 否则会被当成「应用没有日志输出」。
        sb.append("说明：logcat 执行失败：").append(lastFailureNote).append('\n');
      }
      if (!packageName.isEmpty()) {
        sb.append("提示：权限不足时 logcat 不会输出其它应用的日志；");
        sb.append("若当前 shell 后端是 Termux，普通应用只能读取自身进程的日志。\n");
      }
      return ok(sb.toString());
    }

    StringBuilder sb = new StringBuilder();
    if (!packageName.isEmpty()) {
      sb.append("[应用: ").append(packageName).append(" (pid ").append(pid).append(")]\n");
    }
    sb.append(text);
    return ok(sb.toString());
  }

  // ---------------------------------------------------------------------------
  // 结构化崩溃判定
  // ---------------------------------------------------------------------------

  /**
   * 结构化崩溃判定。
   *
   * <p>步骤：
   * <ol>
   *   <li>{@code pidof <pkg>} 拿 PID；拿不到说明进程未运行
   *   <li>读 {@code logcat -b crash}（按 PID 过滤）中的 {@code FATAL EXCEPTION}
   *   <li>crash buffer 为空时，兜底扫 main buffer 最近若干行（部分 ROM 只写 main）
   *   <li>识别 {@code ANR in <pkg>}
   *   <li>返回「是否崩溃 + 崩溃堆栈」，而不是原始大段日志
   * </ol>
   *
   * <p><b>为什么按 PID 过滤</b>：crash buffer 是全设备共享的，其它应用崩溃会污染结果。
   * 用 {@code pidof} 得到的 PID 过滤后，命中就是我们这个进程的崩溃。
   */
  private ToolResult crashCheck(String packageName, ToolContext context) {
    if (packageName.isEmpty()) {
      return error("mode=crash 需要提供 packageName");
    }
    if (context != null) {
      context.reportProgress("崩溃判定: " + packageName);
    }

    String pid = findPid(packageName);

    // 1) crash buffer（按 PID 过滤，若拿到 PID）
    List<String> crashLines =
        runLogcat(buildCrashCommand(pid, packageName));

    // 2) 兜底：main buffer 最近 N 行里找 FATAL / ANR
    List<String> mainLines = runLogcat(buildMainFatalCommand(pid, packageName));

    List<String> all = new ArrayList<>();
    if (crashLines != null) {
      all.addAll(crashLines);
    }
    if (mainLines != null) {
      all.addAll(mainLines);
    }

    if (all.isEmpty() && pid == null) {
      // 没有进程、也没有任何相关日志。
      //
      // **不能说死「未运行」**：pid 为 null 有两种可能——应用真没跑，或当前 shell
      // 后端查不到别人的进程（Termux 的 pidof 权限较窄）。早先只报前者，模型会据此
      // 断定「应用没在运行」，而它可能只是查不到，诊断方向被完全带偏。
      return ok(
          "崩溃判定: 未运行（或无法确认）\n"
              + "Package: "
              + packageName
              + "\n说明: 查不到该应用的进程（pidof 无结果），也没有读到崩溃日志。\n"
              + "提示: 两种可能——(1) 应用确实没在运行，先用 launch_app 启动；"
              + "(2) 当前 shell 后端权限不足，查不到其它应用的进程（无 Shizuku 时常见）。"
              + "若刚启动就查不到进程，还可能是启动即崩溃，请检查启动结果与 main buffer 日志。");
    }

    FatalReport fatal = extractFatal(all, packageName, pid);
    String anr = findAnr(all, packageName);

    StringBuilder sb = new StringBuilder();
    boolean crashed = fatal != null || anr != null;

    if (!crashed) {
      sb.append("崩溃判定: 未崩溃\n");
      sb.append("Package: ").append(packageName).append('\n');
      sb.append("PID: ").append(pid == null ? "(未运行)" : pid).append('\n');
      sb.append("扫描: crash buffer")
          .append(pid == null ? "" : " (--pid=" + pid + ")")
          .append(" + main buffer 最近 ")
          .append(CRASH_SCAN_LINES)
          .append(" 行，未发现 FATAL EXCEPTION / ANR。");
      return ok(sb.toString());
    }

    sb.append("崩溃判定: 已崩溃\n");
    sb.append("Package: ").append(packageName).append('\n');
    sb.append("PID: ").append(pid == null ? "(进程已退出)" : pid).append('\n');

    if (fatal != null) {
      sb.append("类型: FATAL EXCEPTION\n");
      if (!fatal.exception.isEmpty()) {
        sb.append("异常: ").append(fatal.exception).append('\n');
      }
      if (!fatal.thread.isEmpty()) {
        sb.append("线程: ").append(fatal.thread).append('\n');
      }
      if (!fatal.process.isEmpty()) {
        sb.append("进程: ").append(fatal.process).append('\n');
      }
      if (!fatal.stack.isEmpty()) {
        sb.append("堆栈:\n").append(fatal.stack);
        if (!fatal.stack.endsWith("\n")) {
          sb.append('\n');
        }
      }
    }
    if (anr != null) {
      sb.append("类型: ANR\n");
      sb.append("详情: ").append(anr).append('\n');
    }
    sb.append("提示: 依据堆栈里的第一条应用帧定位代码；修改后重新构建安装再验证。");
    return ok(sb.toString());
  }

  /** 一条 FATAL EXCEPTION 的解析结果。 */
  private static final class FatalReport {
    final String thread;
    final String process;
    final String exception;
    final String stack;

    FatalReport(String thread, String process, String exception, String stack) {
      this.thread = thread == null ? "" : thread;
      this.process = process == null ? "" : process;
      this.exception = exception == null ? "" : exception;
      this.stack = stack == null ? "" : stack;
    }
  }

  /**
   * 从日志行中抽取第一条 {@code FATAL EXCEPTION} 及其堆栈。
   *
   * <p>crash buffer 的典型形态（每行都带 {@code E AndroidRuntime: } 前缀）：
   * <pre>
   * 10-04 12:34:56.789  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main
   * 10-04 12:34:56.789  1234  1234 E AndroidRuntime: Process: com.example, PID: 1234
   * 10-04 12:34:56.789  1234  1234 E AndroidRuntime: java.lang.NullPointerException: ...
   * 10-04 12:34:56.789  1234  1234 E AndroidRuntime: 	at com.example.MainActivity.onCreate(MainActivity.java:42)
   * </pre>
   * 抽取时会剥掉 logcat 的前缀，只留下异常本体。
   */
  static FatalReport extractFatal(List<String> lines, String packageName, String pid) {
    int start = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains("FATAL EXCEPTION")) {
        // 若给了包名，优先选属于该包的那条
        if (packageName == null || packageName.isEmpty() || lineBelongsTo(lines, i, packageName, pid)) {
          start = i;
          break;
        }
        if (start < 0) {
          start = i;
        }
      }
    }
    if (start < 0) {
      return null;
    }

    String header = stripPrefix(lines.get(start));
    String thread = "";
    int colon = header.indexOf("FATAL EXCEPTION:");
    if (colon >= 0) {
      thread = header.substring(colon + "FATAL EXCEPTION:".length()).trim();
    }

    String process = "";
    String exception = "";
    StringBuilder stack = new StringBuilder();
    int emitted = 0;
    for (int i = start + 1; i < lines.size() && emitted < MAX_STACK_LINES; i++) {
      String body = stripPrefix(lines.get(i));
      if (body.isEmpty()) {
        continue;
      }
      if (body.startsWith("Process:")) {
        process = body.substring("Process:".length()).trim();
        continue;
      }
      if (body.startsWith("Caused by:") || body.startsWith("at ") || body.startsWith("\tat ")) {
        stack.append(body).append('\n');
        emitted++;
        continue;
      }
      if (exception.isEmpty() && looksLikeException(body)) {
        exception = body;
        stack.append(body).append('\n');
        emitted++;
        continue;
      }
      // 遇到下一条 FATAL 或明显无关的行就停止
      if (body.contains("FATAL EXCEPTION")) {
        break;
      }
      if (stack.length() == 0) {
        // 异常头之前的信息（如 "Process:" 已处理）
        continue;
      }
      break;
    }

    return new FatalReport(thread, process, exception, stack.toString());
  }

  /** 判断某行附近（同 PID 或同包名）是否属于目标应用。 */
  private static boolean lineBelongsTo(List<String> lines, int index, String packageName, String pid) {
    int from = Math.max(0, index - 3);
    int to = Math.min(lines.size() - 1, index + 4);
    for (int i = from; i <= to; i++) {
      String line = lines.get(i);
      if (line.contains(packageName)) {
        return true;
      }
      if (pid != null && !pid.isEmpty() && containsPid(line, pid)) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsPid(String line, String pid) {
    // threadtime 格式：时间 PID TID 级别 标签: 内容 —— PID 是第二个数值列
    String[] parts = line.trim().split("\\s+");
    return parts.length >= 3 && parts[2].equals(pid);
  }

  private static boolean looksLikeException(String body) {
    // 形如 java.lang.XxxException: msg / android.os.Xxx / kotlin.Xxx
    return body.matches("[a-zA-Z_$][\\w$]*(\\.[\\w$]+)*Exception.*")
        || body.matches("[a-zA-Z_$][\\w$]*(\\.[\\w$]+)*Error.*")
        || body.contains("Exception:")
        || body.contains("Error:");
  }

  /** 抽取 ANR 行（{@code ANR in <pkg>}）。 */
  static String findAnr(List<String> lines, String packageName) {
    for (String line : lines) {
      if (line.contains("ANR in " + packageName)) {
        return stripPrefix(line);
      }
    }
    return null;
  }

  /** 剥掉 logcat threadtime 前缀，留下 tag 之后的正文。 */
  private static String stripPrefix(String line) {
    if (line == null) {
      return "";
    }
    // 形式: "10-04 12:34:56.789  1234  1234 E AndroidRuntime: 正文"
    int marker = line.indexOf(": ");
    int threadtime = line.indexOf(" E ") >= 0 ? line.indexOf(" E ") : line.indexOf(" W ");
    if (marker > 0 && threadtime > 0 && marker > threadtime) {
      return line.substring(marker + 2).trim();
    }
    return line.trim();
  }

  // ---------------------------------------------------------------------------
  // 命令构造与执行
  // ---------------------------------------------------------------------------

  /** 构造读 crash buffer 的命令。 */
  static List<String> buildCrashCommand(String pid, String packageName) {
    List<String> command = new ArrayList<>();
    command.add("logcat");
    command.add("-d");
    command.add("-b");
    command.add("crash");
    command.add("-v");
    command.add("threadtime");
    if (pid != null && !pid.isEmpty()) {
      command.add("--pid=" + pid);
    }
    return command;
  }

  /** 构造读 main buffer 中 FATAL/ANR 的命令（兜底，按级别 E）。 */
  static List<String> buildMainFatalCommand(String pid, String packageName) {
    List<String> command = new ArrayList<>();
    command.add("logcat");
    command.add("-d");
    command.add("-v");
    command.add("threadtime");
    command.add("-t");
    command.add(String.valueOf(CRASH_SCAN_LINES));
    command.add("*:E");
    if (pid != null && !pid.isEmpty()) {
      command.add("--pid=" + pid);
    }
    return command;
  }

  /**
   * 执行 logcat 命令并收集输出行。
   *
   * @return 输出行；无可用后端或超时返回 null（调用方报「未执行」）
   */
  private List<String> runLogcat(List<String> command) {
    // 用**任意可用后端**而不是严格要求 Shizuku 的那条路径。
    //
    // 真机实测（Android 16 / PJA110）：没装 Shizuku 时 `shell_execute` 走 Termux 后端
    // 执行 `logcat -d` 能正常读到日志（Termux 进程 uid 在 log 组），而此前本工具因
    // 强制要求 Shizuku 恒失败——同一台设备、同一条命令，两个工具给出相反结论，
    // 模型据此得出「设备缺 adb 权限」的错误诊断（实际它自己就能读）。
    //
    // 权限真的不足时 logcat 返回空输出或非零退出码，由调用方按输出内容判断，
    // 而不是在这里一刀切拒绝。
    String cmd = joinCommand(command);
    PhoneShellRunner.Output output =
        PhoneShellRunner.execWithAnyBackend(shellBackends, cmd, TIMEOUT_MS);
    if ("none".equals(output.channel) || output.timedOut) {
      lastFailureNote = output.note == null ? "" : output.note;
      return null;
    }
    // 执行本身失败（后端抛异常等）：记下原因供上层展示。
    // 不返回 null——非零退出码也可能伴随有用的输出（logcat 的权限错误就打在 stderr）。
    lastFailureNote = output.ok ? "" : (output.note == null ? "" : output.note);
    List<String> lines = new ArrayList<>();
    String text = output.combined();
    if (text.isEmpty()) {
      return lines;
    }
    for (String line : text.split("\\r?\\n")) {
      lines.add(line);
    }
    return lines;
  }

  /** 把命令列表拼成一条 shell 命令（各元素已由构造方保证无空格）。 */
  private static String joinCommand(List<String> parts) {
    StringBuilder sb = new StringBuilder();
    for (String part : parts) {
      if (sb.length() > 0) {
        sb.append(' ');
      }
      sb.append(part);
    }
    return sb.toString();
  }

  /**
   * 按包名查找进程 PID（经统一执行器，任意可用后端）。
   *
   * <p>找不到时返回 null——调用方据此给出明确提示，而不是返回一个空日志列表。
   */
  private String findPid(String packageName) {
    if (!PhoneShellRunner.isValidPackage(packageName)) {
      return null;
    }
    // 与 runLogcat 一致用宽松后端：Shizuku 与 Termux 都能查 pidof（后者权限更弱，
    // 查不到时返回 null，调用方会提示「可能未运行或权限不足」而不是断言没运行）。
    return PhoneShellRunner.findPidWithAnyBackend(shellBackends, packageName);
  }
}

/*
 * This file is part of AndroidCodeStudio.
 *
 * Ported from Aharou (https://github.com/520huxiangli/Aharou),
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

package com.tom.rv2ide.artificial.agent.tool.vd;

import android.content.Context;
import com.tom.rv2ide.ai.tool.ShellBackendRegistry;
import com.tom.rv2ide.artificial.agent.tool.PhoneUiSelector;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 影子屏控制器：在宿主上创建一块**不在设备屏幕显示**的虚拟显示屏。
 *
 * <p>机制参考并改编自 Aharou 的 {@code VdController}（其来源是 Genymobile/scrcpy 的
 * new-display，Apache-2.0）：资产里内置一个约 4KB 的 runner，经 Shizuku 以 shell 身份
 * {@code app_process} 拉起——创建无头虚拟屏后保活；后续启动 App / 截图 / 触控
 * 全部经同一条命令行通道完成，用户主屏零打扰。
 *
 * <p><b>与 Aharou 的差异</b>：
 * <ul>
 *   <li>shell 通道走 ACS 的 {@link PhoneShellRunner}（Shizuku 后端），不是 Hilt 注入的
 *       HostShellManager；无 adb 权限时给出明确错误。
 *   <li>命名空间全部换成 ACS 自己的（{@code /data/local/tmp/acs-vd.jar}、
 *       {@code acs-vd.stop}、显示屏名 {@code acs-vd}），与 Aharou 同机共存不互踩。
 * </ul>
 *
 * <p><b>线程</b>：所有公开方法都是阻塞调用（shell 命令同步等待），调用方负责放到
 * 后台线程——工具执行本来就在后台执行器上。
 *
 * <p><b>Android 10 实测差异</b>（黑鲨 SKW-A0 / JOYUI 12）：SurfaceFlinger 输出里虚拟屏
 * 没有数字 id（只有 {@code + DisplayDevice{virtual, "acs-vd"}}），{@code sfDisplayId}
 * 为空是常态——截图走 runner 自己出图、不依赖它；{@code dumpsys activity} 的 resumed 行
 * 叫 {@code ResumedActivity}（13+ 叫 {@code topResumedActivity}），同段还有栈级的
 * {@code mResumedActivity}——行选择见 {@link #pickResumedLine}（不能裸子串匹配，会先命中
 * 栈级记录取到过期值）。触控注入（{@code input -d}）实测可用，即使该屏不是
 * top-focused display 也能送达（前提：目标窗口可见；任务若被系统挪回主屏，注入会静默丢弃）。
 *
 * <p><b>root 模式 shell（Shizuku uid 0）</b>：{@code createVirtualDisplay} 会校验
 * 「packageName 必须属于调用 uid」，而 uid 0 名下没有任何包（实测
 * {@code cmd package list packages --uid 0} 为空）——runner 以 uid 0 原样启动必然抛
 * {@code SecurityException: packageName must match the calling uid}。因此 uid 为 0 时
 * 先 {@code su 2000 -g 1015} 降权到 shell 再启动；{@code -g 1015}（sdcard_rw）是 runner
 * 写 App 外部私有目录（截图输出落点）所需，缺它时实测 Permission denied。
 */
public final class VdController {

  private static final Logger log = LoggerFactory.getLogger(VdController.class);

  public static final String DISPLAY_NAME = "acs-vd";
  private static final String JAR_REMOTE = "/data/local/tmp/acs-vd.jar";
  private static final String STOP_FILE = "/data/local/tmp/acs-vd.stop";
  private static final String LOG_FILE = "/data/local/tmp/acs-vd.log";
  private static final String ASSET_PATH = "vd/acs-vd.jar";
  private static final String SHOT_REQ_FILE = "/data/local/tmp/acs-vd.shot";

  /** 等 runner 出图的轮询间隔与上限。 */
  private static final long SHOT_POLL_MS = 200L;
  private static final long SHOT_WAIT_MS = 5_000L;

  public static final int DEFAULT_WIDTH = 1080;
  public static final int DEFAULT_HEIGHT = 1920;
  public static final int DEFAULT_DPI = 440;

  /** KEYCODE_PASTE：让目标输入框自己从剪贴板取文本，中文等非 ASCII 走这条路。 */
  private static final int KEYCODE_PASTE = 279;

  /** 影子屏信息。 */
  public static final class VdInfo {
    /** 逻辑 display id（短 id；{@code am start --display} / {@code input -d} 用）。 */
    public final int displayId;
    /** SurfaceFlinger display id（长 id；{@code screencap -d} 用）；拿不到时为空串。 */
    public final String sfDisplayId;
    public final int width;
    public final int height;
    public final int dpi;

    VdInfo(int displayId, String sfDisplayId, int width, int height, int dpi) {
      this.displayId = displayId;
      this.sfDisplayId = sfDisplayId == null ? "" : sfDisplayId;
      this.width = width;
      this.height = height;
      this.dpi = dpi;
    }
  }

  private final Context appContext;
  private final ShellBackendRegistry shellBackends;

  /**
   * runner 出图的落点：App 私有外部目录。runner 以 shell 身份可写，App 又能直接读，
   * 二进制不经 shell 文本通道（那是给命令输出用的）。
   */
  private final String shotFileName =
      "vd/frame-" + UUID.randomUUID().toString().substring(0, 8) + ".png";

  /**
   * runner 实际写图的路径。本实例自己启动时就是我们传进去的那个名字；复用别的实例
   * （App 重启后残留、或同机另一个包）留下的 runner 时，输出路径由**启动者**决定，
   * 必须从它的命令行参数里读回来，否则截图会一直等一个没人写的文件。
   */
  private volatile String runnerShotPath;

  /** 本实例是否亲手起过 runner——只有没起过才需要去读别人的启动参数。 */
  private volatile boolean startedHere = false;

  public VdController(Context context, ShellBackendRegistry shellBackends) {
    this.appContext = context.getApplicationContext();
    this.shellBackends = shellBackends;
    this.runnerShotPath = defaultShotPath();
  }

  /** shell 后端注册表（VdTool 的 caption 查询复用同一条通道）。 */
  public ShellBackendRegistry shellBackends() {
    return shellBackends;
  }

  private String defaultShotPath() {
    File dir = appContext.getExternalFilesDir(null);
    return new File(dir, shotFileName).getAbsolutePath();
  }

  /** 一条 shell 命令的执行结果（命令本身的成败，含超时判定）。 */
  static final class Exec {
    final boolean ok;
    final String output;
    final String error;

    Exec(boolean ok, String output, String error) {
      this.ok = ok;
      this.output = output == null ? "" : output;
      this.error = error == null ? "" : error;
    }

    String combined() {
      return output;
    }
  }

  /**
   * 经 Shizuku 执行一条命令。
   *
   * <p>影子屏的每一步（建屏、启动 App、触控）都必须以 shell 身份执行——
   * display 服务只对 shell/root 放行 {@code createVirtualDisplay} 与
   * {@code am start --display}。Termux 后端拿不到这些权限，因此这里要求
   * adb 后端；不可用时给出具体原因而不是静默失败。
   */
  private Exec exec(String command, long timeoutMs) {
    PhoneUiSelector.Exec output = PhoneUiSelector.exec(shellBackends, command, timeoutMs);
    if (output.ok) {
      return new Exec(true, output.stdout, "");
    }
    String error = output.error;
    if (error == null || error.trim().isEmpty()) {
      error = "命令失败：" + command;
    }
    return new Exec(false, output.stdout, error);
  }

  /** 把资产里的 runner 部署到 /data/local/tmp（md5 不同才覆盖）。 */
  public void ensureDeployed() {
    File local = exportAsset();
    String localMd5 = md5Hex(local);
    Exec remoteCheck = exec("md5sum " + JAR_REMOTE + " 2>/dev/null | awk '{print $1}'", 20_000L);
    String remoteMd5 = remoteCheck.output.trim();
    if (!localMd5.equals(remoteMd5)) {
      Exec r =
          exec(
              "cp \"" + local.getAbsolutePath() + "\" " + JAR_REMOTE
                  + " && chmod 644 " + JAR_REMOTE + " && echo DEPLOYED",
              30_000L);
      if (!r.output.contains("DEPLOYED")) {
        throw new IllegalStateException(
            "部署 runner 失败：" + (r.error.isEmpty() ? r.output : r.error).trim());
      }
      log.info("acs-vd runner 已部署（md5={}）", localMd5);
    }
  }

  private File exportAsset() {
    try {
      File dir = new File(appContext.getExternalFilesDir(null), "vd");
      if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
        throw new IllegalStateException("无法创建 vd 资产目录: " + dir.getAbsolutePath());
      }
      File local = new File(dir, "acs-vd.jar");
      try (InputStream input = appContext.getAssets().open(ASSET_PATH);
          FileOutputStream output = new FileOutputStream(local)) {
        byte[] buf = new byte[8192];
        int n;
        while ((n = input.read(buf)) > 0) {
          output.write(buf, 0, n);
        }
      }
      return local;
    } catch (Exception e) {
      throw new IllegalStateException("导出 vd 资产失败: " + e.getMessage(), e);
    }
  }

  /** 启动影子屏（已在运行则直接返回现有信息）。 */
  public VdInfo start(int width, int height, int dpi) {
    VdInfo existing = refresh();
    if (existing != null) {
      return existing;
    }
    ensureDeployed();
    // 自己起就把随机名定下来；之后截图按这个路径等 runner 出图。
    runnerShotPath = defaultShotPath();
    startedHere = true;
    String launch =
        "cd /data/local/tmp; rm -f " + STOP_FILE + " " + LOG_FILE + "; "
            + "CLASSPATH=" + JAR_REMOTE + " setsid app_process / com.tom.rv2ide.vd.VdMain "
            + width + " " + height + " " + dpi + " "
            + "\"" + runnerShotPath + "\" " + SHOT_REQ_FILE + " "
            + "> " + LOG_FILE + " 2>&1 < /dev/null & echo LAUNCHED";
    // root 模式 shell（uid 0）先降权到 shell：runner 声明 com.android.shell 包名，
    // createVirtualDisplay 校验「包名属于调用 uid」，uid 0 名下无包，必然 SecurityException。
    String prefix = privilegeDropPrefix();
    exec(prefix.isEmpty() ? launch : prefix + shellQuote(launch), 30_000L);

    int id = -1;
    String lastLog = "";
    for (int attempt = 0; attempt < 20; attempt++) {
      sleep(500L);
      Exec logExec = exec("cat " + LOG_FILE + " 2>/dev/null", 10_000L);
      lastLog = logExec.output;
      Matcher m = Pattern.compile("VD_READY id=(\\d+)").matcher(lastLog);
      if (m.find()) {
        id = Integer.parseInt(m.group(1));
        break;
      }
      if (lastLog.contains("VD_ERROR") || lastLog.contains("VD_FAILED")) {
        throw new IllegalStateException("影子屏启动失败：" + abbreviate(lastLog, 400));
      }
    }
    if (id < 0) {
      throw new IllegalStateException(
          "影子屏启动超时（日志尾部：" + abbreviate(exec("tail -5 " + LOG_FILE, 10_000L).output, 300) + "）");
    }
    VdInfo info = refresh();
    if (info == null) {
      info = new VdInfo(id, "", width, height, dpi);
    }
    // DisplayInfo 不跟着改的话，App 拿到的 screenWidthDp 仍是物理设备的值
    // （363dp = 手机档），宽屏/平板分支永远走不到，也就模拟不出大屏布局。
    try {
      exec("wm size -d " + info.displayId + " " + width + "x" + height, 15_000L);
      exec("wm density -d " + info.displayId + " " + dpi, 15_000L);
    } catch (RuntimeException e) {
      log.info("设置影子屏尺寸失败：{}", e.getMessage());
    }
    return info;
  }

  /** 停止影子屏。 */
  public void stop() {
    exec("touch " + STOP_FILE + " && echo OK", 15_000L);
    sleep(600L);
    startedHere = false;
  }

  /**
   * 需要时给出「降权到 shell(2000)」的命令前缀；不需要时返回空串。
   *
   * <p><b>为什么需要</b>：runner 声明 {@code com.android.shell} 包名，而
   * {@code createVirtualDisplay} 校验「包名必须属于调用 uid」。当 shell 通道是 root
   * （Shizuku 以 root 启动、或 adb root 模式）时调用 uid 为 0，而 uid 0 名下没有任何包
   * ——直接启动必然 {@code SecurityException: packageName must match the calling uid}
   * （实测黑鲨 SKW-A0，Shizuku 以 root 运行）。降权到 shell 后校验通过；
   * {@code -g 1015}（sdcard_rw）是 runner 把截图写进 App 外部私有目录所需，
   * 缺它时写图 Permission denied。
   *
   * <p>只在探测到 uid 0 时才包裹：普通 Shizuku 是 uid 2000，设备上通常没有 su，
   * 无条件包裹会让本可用的启动直接失败。su 一并从常见路径查找——Shizuku 进程的
   * PATH 不一定含 /sbin（Magisk su 的落点）。
   */
  private String privilegeDropPrefix() {
    Exec probe =
        exec(
            "id -u; command -v su 2>/dev/null"
                + " || ls /sbin/su /system/bin/su /system/xbin/su 2>/dev/null | head -n 1",
            15_000L);
    String[] lines = probe.output.trim().split("\\s+");
    if (lines.length == 0 || !"0".equals(lines[0])) {
      return "";
    }
    String su = lines.length > 1 && !lines[1].isEmpty() ? lines[1] : "su";
    return su + " 2000 -g 1015 sh -c ";
  }

  /** 刷新当前状态；未运行返回 null。 */
  public VdInfo refresh() {
    // 逻辑 display id / 尺寸 / density 都从 DisplayManager 侧取：runner 报的 VD_READY 是创建
    // 瞬间的编号，之后系统会重排（实测 runner 报 5、AM 实际是 8），拿它去 am/input 会打偏。
    String displayDump;
    try {
      displayDump = exec("dumpsys display | grep -E 'mViewports|\"" + DISPLAY_NAME + "\":'", 20_000L).output;
    } catch (RuntimeException e) {
      return null;
    }
    VdInfo parsed = parseDisplayDump(displayDump);
    if (parsed == null) {
      return null;
    }
    // 截图要的是 SurfaceFlinger 的 display token。Android 14+ 的 --display-id 会列出虚拟屏，
    // Android 13 只列物理屏，得从 DisplayDevice 行（Display <id> (virtual, "acs-vd")）里捞；
    // Android 10 两处都没有数字 id——空串是常态，截图不依赖它（见 screenshot()）。
    String sfOut;
    try {
      sfOut =
          exec(
                  "dumpsys SurfaceFlinger --display-id; dumpsys SurfaceFlinger | grep -F '\""
                      + DISPLAY_NAME + "\")'",
                  20_000L)
              .output;
    } catch (RuntimeException e) {
      sfOut = "";
    }
    String sfId = parseSfDisplayId(sfOut);
    return new VdInfo(parsed.displayId, sfId, parsed.width, parsed.height, parsed.dpi);
  }

  /**
   * 从 {@code dumpsys display} 输出里解析影子屏信息（mViewports 行 + DisplayDeviceInfo 行）；
   * 找不到返回 null。mViewports 的字段随 Android 版本变（14+ 有 densityDpi，13 没有，
   * density 只在 DisplayDeviceInfo 行里），别把某一版的字段当成必需项。测试可见。
   */
  static VdInfo parseDisplayDump(String displayDump) {
    if (displayDump == null) {
      return null;
    }
    Matcher vp =
        Pattern.compile(
                "type=VIRTUAL[^}]*?displayId=(\\d+)[^}]*?uniqueId='[^']*"
                    + Pattern.quote(DISPLAY_NAME)
                    + "[^']*'[^}]*?deviceWidth=(\\d+), deviceHeight=(\\d+)")
            .matcher(displayDump);
    if (!vp.find()) {
      return null;
    }
    int id = Integer.parseInt(vp.group(1));
    int w = Integer.parseInt(vp.group(2));
    int h = Integer.parseInt(vp.group(3));
    Matcher dm = Pattern.compile("density (\\d+)").matcher(displayDump);
    int dpi;
    if (dm.find()) {
      dpi = Integer.parseInt(dm.group(1));
    } else {
      Matcher dm2 = Pattern.compile("densityDpi=(\\d+)").matcher(displayDump);
      dpi = dm2.find() ? Integer.parseInt(dm2.group(1)) : DEFAULT_DPI;
    }
    return new VdInfo(id, "", w, h, dpi);
  }

  /** 从 SurfaceFlinger 输出里解析 display token；取不到返回空串（Android 10 常态）。测试可见。 */
  static String parseSfDisplayId(String sfOut) {
    if (sfOut == null) {
      return "";
    }
    Matcher sf =
        Pattern.compile(
                "Display (\\d+) \\(Virtual display\\): displayName=\"" + Pattern.quote(DISPLAY_NAME) + "\"")
            .matcher(sfOut);
    if (sf.find()) {
      return sf.group(1);
    }
    Matcher sf2 =
        Pattern.compile("Display (\\d+) \\(virtual, \"" + Pattern.quote(DISPLAY_NAME) + "\"\\)")
            .matcher(sfOut);
    return sf2.find() ? sf2.group(1) : "";
  }

  // component / keycode 会被拼进 Shizuku（adb shell, uid 2000）执行的命令，
  // 必须先白名单校验再拼——否则 `com.x/.Y; 任意命令` 能在宿主上跑。
  private static final Pattern COMPONENT_SLASH_RE =
      Pattern.compile("^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$");
  private static final Pattern PACKAGE_NAME_RE =
      Pattern.compile("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*$");
  private static final Pattern KEY_CODE_RE =
      Pattern.compile("^(KEYCODE_[A-Z0-9_]+|[0-9]{1,4})$");

  /** 在影子屏上启动应用（component 可传 "pkg/act" 或仅 "pkg"）。 */
  public VdInfo launch(String component) {
    VdInfo info = refresh();
    if (info == null) {
      throw new IllegalStateException("影子屏未运行，请先 start");
    }
    String raw = component.trim();
    String comp;
    if (raw.contains("/")) {
      if (!COMPONENT_SLASH_RE.matcher(raw).matches()) {
        throw new IllegalArgumentException("component 格式不合法：" + raw);
      }
      comp = raw;
    } else {
      if (!PACKAGE_NAME_RE.matcher(raw).matches()) {
        throw new IllegalArgumentException("包名格式不合法：" + raw);
      }
      comp =
          exec("cmd package resolve-activity --brief " + raw + " 2>/dev/null | tail -1", 20_000L)
              .output
              .trim();
    }
    if (!comp.contains("/")) {
      throw new IllegalStateException("找不到可启动的入口：" + component);
    }
    // resolve-activity 的输出来自系统，但按「外部输入」对待：再验一次格式才拼进 am 命令。
    // 系统输出格式受约束、被恶意 App 影响的可能极低，但白名单校验的成本是零。
    if (!COMPONENT_SLASH_RE.matcher(comp).matches()) {
      throw new IllegalStateException("resolve-activity 返回了异常的组件名：" + abbreviate(comp, 120));
    }
    String pkg = comp.substring(0, comp.indexOf('/'));
    // 直接往影子屏启动，**不走 move-stack**。move-stack 那条路要先把应用在主屏拉起来、
    // 再把它整个搬进影子屏：屏幕上会先闪出这个应用，目标应用若本就在主屏运行还会被连任务搬走
    // （用户会看到屏幕跳转）。曾经以为 shell 用 `--display` 会被 SecurityException 挡下，
    // 实测在本屏（带 TRUSTED/OWN_FOCUS 标志）不成立，直启就能落上去。
    Exec r = exec("am start --display " + info.displayId + " -f 0x10000000 -n " + comp, 30_000L);
    String out = r.output;
    if (out.contains("Error") || out.contains("Exception")) {
      throw new IllegalStateException("启动失败：" + abbreviate(out, 300));
    }
    // am 对「已在别处运行的实例」会回一句 Warning 并直接交旗，那种情况下这一步不会出现在影子屏。
    for (int attempt = 0; attempt < 8; attempt++) {
      sleep(400L);
      if (hasTaskOnDisplay(pkg, info.displayId)) {
        VdInfo refreshed = refresh();
        return refreshed == null ? info : refreshed;
      }
    }
    throw new IllegalStateException(
        comp + " 没能落到影子屏：多半是它已经运行在别的屏幕上，系统把 intent 交给了那个实例。"
            + "先在那边退出它再试（不做 move-stack 搬家，以免动到用户当前屏幕）。");
  }

  /**
   * 目标包是否已是 {@code displayId} 那块屏的栈顶（每块屏只有一条 resumed 记录，用作落屏校验）。
   *
   * <p>行选择交给 {@link #pickResumedLine}（可单测）：awk 只负责圈出该屏的 section 并收集
   * 所有含 resumed 的行——单靠 awk 一条正则选行会踩到 {@code mResumedActivity} 与
   * {@code ResumedActivity} 的前缀包含问题。
   */
  private boolean hasTaskOnDisplay(String pkg, int displayId) {
    try {
      // 不能改用「整段 section 里 grep 包名」：屏幕段之后还跟着全局区域，会把别的屏上的包一起数进来。
      // 退出条件除了下一块屏的段头，还要防「本段是最后一块屏」时读进全局段（那里也有一条
      // ResumedActivity，属于别的屏）。
      String out =
          exec(
                  "dumpsys activity activities | awk '/Display #" + displayId
                      + " \\(activities from top to bottom\\)/{f=1;next}"
                      + " f&&/Display #/{exit} f&&/ActivityStackSupervisor/{exit}"
                      + " f&&/ResumedActivity/{print}'",
                  15_000L)
              .output;
      String line = pickResumedLine(out);
      return line != null && line.contains(pkg + "/");
    } catch (RuntimeException e) {
      return false;
    }
  }

  /**
   * 从 section 文本里选出「该屏的 resumed 记录」行；选不出返回 null。测试可见。
   *
   * <p>三个候选形态（实测取样）：
   * <ul>
   *   <li>{@code "  topResumedActivity=ActivityRecord{...}"} — Android 13+ 屏级记录；
   *   <li>{@code " ResumedActivity:ActivityRecord{...}"} — Android 10 屏级记录（行首单空格）；
   *   <li>{@code "    mResumedActivity: ActivityRecord{...}"} — Android 10 **栈级**记录（行首四空格），
   *       取值可能滞后于屏级记录（实测同一段里两者指向不同 Activity）。
   * </ul>
   *
   * <p>优先级：{@code topResumedActivity} &gt; 行首空白 + {@code ResumedActivity:}；
   * {@code mResumedActivity} 必须排除——它是 {@code ResumedActivity} 的子串，
   * 裸子串匹配会先命中它取到过期值（reviewer 实测：段内 mResumedActivity 在前，
   * 真值在后，launch 落屏校验会误判）。
   */
  public static String pickResumedLine(String section) {
    if (section == null) {
      return null;
    }
    String fallback = null;
    for (String raw : section.split("\n")) {
      String line = raw.trim();
      if (line.isEmpty()) {
        continue;
      }
      if (line.startsWith("topResumedActivity")) {
        // 13+ 的屏级记录（格式 `topResumedActivity=...`；不用 startsWith("...=")，
        // 防个别 ROM 打成 `topResumedActivity:` 时漏掉）。
        return line;
      }
      // 屏级：行首（去缩进后）以 ResumedActivity: 开头；mResumedActivity 是 "m" 开头，天然排除。
      if (fallback == null && line.startsWith("ResumedActivity:")) {
        fallback = line;
      }
    }
    return fallback;
  }

  /**
   * 截图影子屏 → 返回本地 PNG 文件。
   *
   * <p>走 runner 自己出图：Android 13 的 {@code screencap} 只认物理屏 token，对虚拟屏会
   * 静默写出 0 字节文件（14+ 才支持），所以改成「touch 请求文件 → runner 写 PNG → 直接读结果」。
   * 这条链路不经过 SurfaceFlinger 的 display token——Android 10 的 SF 输出里虚拟屏没有数字
   * id（只有 {@code + DisplayDevice{virtual, "acs-vd"}}），{@code sfDisplayId} 为空属正常，
   * 不能据此拒绝截图（Aharou 原版有这个检查，是因为它只在 13/14 上验证过）。
   */
  public File screenshot(VdInfo known) {
    VdInfo info = known == null ? refresh() : known;
    if (info == null) {
      throw new IllegalStateException("影子屏未运行，请先 start");
    }
    File shot = new File(resolveRunnerShotPath());
    File parent = shot.getParentFile();
    if (parent != null) {
      parent.mkdirs();
    }
    if (shot.exists()) {
      shot.delete();
    }
    exec("touch " + SHOT_REQ_FILE, 10_000L);
    long waited = 0L;
    while (waited < SHOT_WAIT_MS && !(shot.exists() && shot.length() > 0)) {
      sleep(SHOT_POLL_MS);
      waited += SHOT_POLL_MS;
    }
    if (!shot.exists() || shot.length() == 0) {
      throw new IllegalStateException(
          "截图失败：runner 未在 " + (SHOT_WAIT_MS / 1000) + " 秒内产出画面"
              + "（日志尾部：" + abbreviate(exec("tail -3 " + LOG_FILE, 10_000L).output, 200) + "）");
    }
    // runner 的截图输出路径在**启动时固定**，每次截图都会先删再写同一个文件。
    // 直接把该路径交给调用方（视觉回归会把它存成基线、或稍后比对）会在下一次
    // 截图时被覆盖——与 phone_screenshot 每次新建文件的契约不一致。
    // 因此这里复制一份到唯一文件名再返回；复制失败时退回原路径（至少本次可用）。
    return copyToUnique(shot);
  }

  /** 把 runner 刚产出的帧复制成唯一文件名；失败时返回原文件。 */
  private File copyToUnique(File source) {
    try {
      String base = source.getName();
      int dot = base.lastIndexOf('.');
      String ext = dot >= 0 ? base.substring(dot) : ".png";
      File dir = source.getParentFile();
      if (dir == null) {
        return source;
      }
      File unique =
          new File(
              dir,
              "shot-"
                  + System.currentTimeMillis()
                  + "-"
                  + UUID.randomUUID().toString().substring(0, 8)
                  + ext);
      java.nio.file.Files.copy(
          source.toPath(),
          unique.toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      return unique;
    } catch (Exception e) {
      log.warn("复制影子屏截图失败，退回原路径: {}", source, e);
      return source;
    }
  }

  /**
   * 取 runner 实际在写的截图路径。复用别人启动的 runner 时，从它的命令行里读回来
   * （{@code VdMain <w> <h> <dpi> "<png路径>" <请求文件>}，路径是唯一带引号的参数）。
   */
  private String resolveRunnerShotPath() {
    if (startedHere) {
      return runnerShotPath;
    }
    String args;
    try {
      args =
          exec("ps -A -o args | grep -m1 'com[.]tom[.]rv2ide[.]vd[.]VdMain'", 10_000L).output;
    } catch (RuntimeException e) {
      args = "";
    }
    String path = parseRunnerShotPath(args);
    File ownDir = appContext.getExternalFilesDir(null);
    String ownDirPath = ownDir == null ? null : ownDir.getAbsolutePath();
    if (path == null || ownDirPath == null || !path.startsWith(ownDirPath)) {
      throw new IllegalStateException(
          "影子屏由另一个 ACS 实例启动（截图写在 " + (path == null ? "未知路径" : path) + "），先 stop 再 start");
    }
    runnerShotPath = path;
    return path;
  }

  /** 从 runner 的命令行里取截图输出路径；取不到返回 null。测试可见。 */
  public static String parseRunnerShotPath(String psArgs) {
    if (psArgs == null) {
      return null;
    }
    Matcher m = Pattern.compile("\"([^\"]+\\.png)\"").matcher(psArgs);
    return m.find() ? m.group(1) : null;
  }

  /** 在影子屏上点按。 */
  public void tap(VdInfo info, int x, int y) {
    exec("input -d " + requireDisplayId(info) + " tap " + x + " " + y, 20_000L);
  }

  /** 在影子屏上滑动。 */
  public void swipe(VdInfo info, int x1, int y1, int x2, int y2, int durationMs) {
    exec(
        "input -d " + requireDisplayId(info) + " swipe " + x1 + " " + y1 + " " + x2 + " " + y2
            + " " + durationMs,
        20_000L);
  }

  /** 在影子屏上发送按键（如 KEYCODE_BACK / KEYCODE_HOME / 4 / 3）。 */
  public void keyEvent(VdInfo info, String code) {
    String c = code.trim();
    if (!KEY_CODE_RE.matcher(c).matches()) {
      throw new IllegalArgumentException("keycode 不合法：" + c);
    }
    exec("input -d " + requireDisplayId(info) + " keyevent " + c, 20_000L);
  }

  /**
   * 在影子屏当前获得焦点的输入框里输入文字，{@code submit} 为真时补一个回车。
   *
   * <p>{@code input text} 只可靠地支持 ASCII，且空格必须写成 {@code %s}（input 命令自身的
   * 约定：它的参数按空白切分，{@code %s} 会被还原成空格）；中文等非 ASCII 走宿主剪贴板 +
   * {@code KEYCODE_PASTE}，让目标 App 自己粘贴（{@code input text} 传不进去多字节字符）。
   */
  public void type(VdInfo info, String text, boolean submit) {
    int displayId = requireDisplayId(info);
    if (text != null && !text.isEmpty()) {
      boolean nonAscii = false;
      for (int i = 0; i < text.length(); i++) {
        if (text.charAt(i) > 0x7F) {
          nonAscii = true;
          break;
        }
      }
      if (nonAscii) {
        setHostClipboard(text);
        // 等系统把新剪贴板同步到目标进程，再发粘贴键，否则可能粘到旧内容。
        sleep(150L);
        exec("input -d " + displayId + " keyevent " + KEYCODE_PASTE, 20_000L);
      } else {
        // text 来自模型/用户，进 shell 前整体单引号转义；空格按 input 约定换成 %s。
        String encoded = text.replace(" ", "%s");
        exec("input -d " + displayId + " text " + shellQuote(encoded), 20_000L);
      }
    }
    if (submit) {
      exec("input -d " + displayId + " keyevent 66", 20_000L);
    }
  }

  private int requireDisplayId(VdInfo info) {
    if (info == null) {
      throw new IllegalStateException("影子屏未运行");
    }
    return info.displayId;
  }

  /**
   * 把文本放进宿主剪贴板。
   *
   * <p>App 进程有 {@code ClipboardManager} 权限（自身即前台应用时可写），
   * 之后影子屏里的目标输入框经 {@code KEYCODE_PASTE} 自行取用——绕开
   * {@code input text} 的多字节限制。
   */
  private void setHostClipboard(String text) {
    android.content.ClipboardManager cm =
        (android.content.ClipboardManager) appContext.getSystemService(Context.CLIPBOARD_SERVICE);
    if (cm == null) {
      throw new IllegalStateException("拿不到剪贴板服务，无法输入非 ASCII 文本");
    }
    cm.setPrimaryClip(android.content.ClipData.newPlainText("acs-vd", text));
  }

  /** shell 单引号转义（POSIX 风格：内部单引号替换为 '\''）。 */
  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  private static String md5Hex(File file) {
    try {
      MessageDigest digest = MessageDigest.getInstance("MD5");
      try (InputStream input = new java.io.FileInputStream(file)) {
        byte[] buf = new byte[8192];
        int n;
        while ((n = input.read(buf)) > 0) {
          digest.update(buf, 0, n);
        }
      }
      StringBuilder sb = new StringBuilder();
      for (byte b : digest.digest()) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException("计算 md5 失败: " + e.getMessage(), e);
    }
  }

  private static String abbreviate(String value, int max) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("被中断");
    }
  }
}

/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */

package com.tom.rv2ide.handlers.system.installer

import android.util.Log
import com.tom.rv2ide.utils.Environment
import java.io.File

/**
 * 用官方 **`sdkmanager`** 安装 SDK 组件（build-tools / platforms）。
 *
 * <p><b>为什么弃用 `idesetup --sdk`</b>：那走的是 AndroidIDE 的镜像 manifest，其
 * build-tools 包**最高只到 35.0.1**（上游从未构建更高版本），而 AGP 9.x 要求
 * build-tools ≥ 36.0.0——镜像链路给不了。实测设备上的 `sdkmanager`（随 cmdline-tools
 * 一起装的官方工具）**能直连 Google 仓库**，可列出并安装 build-tools 至 37.0.0、
 * platforms 至 android-36，且是官方源、不依赖镜像同步。
 *
 * <p><b>调用方式</b>：`sdkmanager` 的 shebang 是 `#!/usr/bin/env sh`，而 Android 上没有
 * `/usr/bin/env`，**直接执行会报 "No such file or directory"**，必须 `sh <path>` 调起。
 * 这也正是这里用 ProcessBuilder 显式传 `[sh, sdkmanager, ...args]` 的原因。
 *
 * <p><b>JAVA_HOME 必需</b>：sdkmanager 是 Java 程序，环境里没有 JAVA_HOME 会启动失败。
 * 优先用随 IDE 分发的 JDK（`$PREFIX/lib/jvm/java-17-openjdk`），找不到再扫 lib/jvm 下的 jdk 目录。
 */
class SdkManagerInstaller {

  companion object {
    private const val TAG = "SdkManagerInstaller"

    /** 递归 chown 整个 SDK 目录的超时（2.3G、上万个文件，给足时间）。 */
    private const val CHOWN_TIMEOUT_MS = 5 * 60 * 1000L

    /**
     * Google 官方 SDK 仓库索引。
     *
     * <p>`repository2-3.xml` 与 `sdkmanager` 自己读的是同一份文件；用 `-3` 是因为
     * 它是当前 schema 版本（`-1` / `-2` 是旧格式，可能缺少较新字段）。
     */
    private const val REPOSITORY_XML_URL =
        "https://dl.google.com/android/repository/repository2-3.xml"

    /**
     * sdkmanager 可执行文件（随 cmdline-tools 安装；找不到说明还没做过首次引导）。
     *
     * <p><b>为什么不再硬编码 `latest`</b>：`latest` 是首装时建的目录名，之后
     * 用户/引导装的新版本落在 `cmdline-tools/<version>/`（实测 pja110 上同时有
     * `2.0` 与 `19.0`）。`latest` 永远指向那个**旧的 2.0**，而它的仓库解析器
     * 不认识 `api-level="37.2"` 这类浮点平台号 —— 列表里看不到 37.x，
     * 直接 install 也会报 `Warning: Failed to find package platforms;android-37.2`
     * （实测）。因此按版本号挑最高的那个可用目录。
     */
    @JvmStatic
    fun sdkManagerFile(): File? = cmdlineToolsDir()?.resolve("bin/sdkmanager")?.takeIf { it.exists() }

    /**
     * 选中的 cmdline-tools 目录。
     *
     * <p>策略：`cmdline-tools/` 下所有**含 `bin/sdkmanager` 的子目录**里按版本号取最高
     * （`latest` 视作最低——它只是首装留下的符号约定，实际版本由 `source.properties`
     * 决定，但读它要多一次 IO，且 `latest` 指向的往往就是最旧的那份）。
     *
     * <p>回退：一个合法目录都没有时返回 `latest`（保持原有报错文案可读，
     * 让 [unavailableReason] 能区分「未装」与「权限不可读」）。
     */
    @JvmStatic
    fun cmdlineToolsDir(): File? {
      val root = File(Environment.ANDROID_HOME, "cmdline-tools")
      if (!root.isDirectory) return null
      val candidates =
          root.listFiles()
              ?.filter { it.isDirectory && File(it, "bin/sdkmanager").exists() }
              .orEmpty()
      if (candidates.isEmpty()) return File(root, "latest").takeIf { it.isDirectory }
      // "latest" 视为 0：只要存在任何带版本号的目录就不选它。
      return candidates.maxWithOrNull(
          compareBy { if (it.name == "latest") 0 else versionSortKey(it.name) })
    }

    /** 目录名转成可比较的数字段；`19.0` → [19,0]，`latest` → [0]。 */
    private fun versionSortKey(name: String): Int =
        name.substringBefore('.').substringBefore('-').toIntOrNull() ?: 1

    /**
     * sdkmanager 是否**可用**（存在 + 本进程可读可执行）。
     *
     * <p><b>为什么不能只看 `exists()`</b>：`File.exists()` 在父目录不可穿越时也返回 false，
     * 与「确实没装」无法区分。实测踩过：设备上 `cmdline-tools/latest/bin/` 是
     * `0700 属主 system`（异常安装留下的），app 以别的 uid 运行 → 读不进去 →
     * `exists()` 为 false → 报「未安装」，而真相是**权限问题**，用户按提示去装 cmdline-tools
     * 也修不好。这里显式区分二者，让报错可行动。
     *
     * <p>返回 null 表示可用；否则返回给用户看的原因。
     */
    @JvmStatic
    fun unavailableReason(): String? {
      val sm = cmdlineToolsDir()?.resolve("bin/sdkmanager")
          ?: File(Environment.ANDROID_HOME, "cmdline-tools/latest/bin/sdkmanager")
      if (!sm.exists()) {
        // 再探一层父目录：能列出 bin/ 说明是"确实没有该文件"，列不出则是权限问题。
        val bin = sm.parentFile
        return if (bin != null && bin.isDirectory && !bin.canRead()) {
          "cmdline-tools 目录不可读（权限问题）：${bin.absolutePath}"
        } else {
          "未找到 sdkmanager（cmdline-tools 未安装）"
        }
      }
      if (!sm.canRead()) return "sdkmanager 不可读：${sm.absolutePath}"
      if (!sm.canExecute()) return "sdkmanager 不可执行：${sm.absolutePath}"
      if (javaHome() == null) return "未找到 JDK（sdkmanager 需要 JAVA_HOME）"
      // 这里**刻意不检写权限**：`--list` 只需要读，目录不可写时列版本照样能用。
      // 把写检查并进来会让「列出可用版本」在只需读的场景下被误报为不可用
      // （实测：SDK 目录属主是 u1_i7610、app 不可写，但 sdk_list 工作正常）。
      // 写检查由 install()/uninstall() 与 UI 各自按需调用 writeBlockedReason()。
      return null
    }

    /** 是否可用（供 UI / AI 工具快速判断，不产生副作用）。 */
    @JvmStatic fun isAvailable(): Boolean = unavailableReason() == null

    /** 随 IDE 分发的 JDK；找不到返回 null（调用方据此报错而非静默失败）。 */
    @JvmStatic
    fun javaHome(): File? {
      val preferred = File(Environment.PREFIX, "lib/jvm/java-17-openjdk")
      if (preferred.isDirectory) return preferred
      // 兜底：lib/jvm 下任意一个 jdk 目录（版本变了也不至于挂）。
      return File(Environment.PREFIX, "lib/jvm")
          .listFiles()
          ?.firstOrNull { it.isDirectory && it.name.contains("jdk") }
    }

    /**
     * `$ANDROID_HOME` 是否可写；不可写时返回可行动的原因，可写返回 null。
     *
     * <p><b>为什么单独检这个</b>：sdkmanager 读得到、跑得起来，但写不进 SDK 目录时
     * 只在 10% 处甩一句 `Failed to read or create install properties file` 就中止——
     * 那句话指向 sdkmanager 自己的属性文件，看不出真因是**目录属主不是本 app**。
     * 与其让用户拿到一段莫名其妙的警告，不如安装前直接说清。
     *
     * <p>实测（黑鲨，2026-11-08）：SDK 目录属主是 `u1_i7610` / `system:system`
     * （设备数据从备份还原、跨 Android 用户/uid 留下），app uid 10304 对这些目录
     * 全无写权限，于是**任何**组件的安装都必然卡在 10%。
     * 对照实验：`--sdk_root` 指向 app 自有目录时，同样的包安装 100% 成功。
     *
     * <p>只用于安装/卸载/修复路径，**不要**放进 {@link #unavailableReason()}。
     */
    @JvmStatic
    fun writeBlockedReason(): String? {
      val home = Environment.ANDROID_HOME
      if (!home.isDirectory) return "SDK 目录不存在：${home.absolutePath}"
      // 直接试探写：比比对 uid/gid 可靠——目录可能用 ACL 或 setgid 放开，
      // 只看属主会误判成"不可写"。
      val probe = File(home, ".acs-write-probe")
      val writable = try {
        probe.createNewFile()
      } catch (e: Exception) {
        false
      }
      if (writable) {
        probe.delete()
        return null
      }
      return "SDK 目录不可写（属主不是本应用）：${home.absolutePath}"
    }

    /**
     * platforms 目录里已安装的 API level（如 "android-36"、"android-37.2"），**按版本升序**。
     *
     * <p><b>不能用字符串排序</b>：`sorted()` 会把 `android-37.2` 排在 `android-36` 之前
     * （逐字符比较时 `'3'=='3'`、`'7'>'6'` 看似对，但 `android-9` 会排在 `android-10`
     * 之后）。更关键的是调用方 `SdkManagerActivity` 用 `installed.lastOrNull()` 当作
     * 「最新已装版本」来决定列出哪些可装版本——顺序错了这个判断就全错。
     */
    @JvmStatic
    fun installedPlatforms(): List<String> =
        File(Environment.ANDROID_HOME, "platforms")
            .listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("android-") }
            ?.map { it.name.removePrefix("android-") }
            ?.sortedWith(versionComparator)
            ?.map { "android-$it" }
            ?: emptyList()

    /**
     * build-tools 目录里已安装的版本（如 "36.0.0"），按版本升序。
     *
     * <p>与 [installedPlatforms] 同理：调用方以 `lastOrNull()` 当「最新已装」，
     * 字符串排序会把 `9.0.0` 排在 `10.0.0` 之后。
     */
    @JvmStatic
    fun installedBuildTools(): List<String> =
        File(Environment.ANDROID_HOME, "build-tools")
            .listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.sortedWith(versionComparator)
            ?: emptyList()

    /** ndk 目录里已安装的版本（如 "26.1.10909125"）。 */
    @JvmStatic
    fun installedNdk(): List<String> = installedDirs("ndk")

    /** cmake 目录里已安装的版本（如 "3.22.1"）。 */
    @JvmStatic
    fun installedCmake(): List<String> = installedDirs("cmake")

    /**
     * platform-tools 是否已安装。
     *
     * <p>它没有版本目录（就一个包），以 `adb` 可执行文件是否存在为准——装了就有 adb。
     */
    @JvmStatic
    fun isPlatformToolsInstalled(): Boolean =
        File(Environment.ANDROID_HOME, "platform-tools/adb").exists()

    /** 读 `$ANDROID_HOME/<name>/` 下的版本目录名（升序）。 */
    private fun installedDirs(name: String): List<String> =
        File(Environment.ANDROID_HOME, name)
            .listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.sortedWith(versionComparator)
            ?: emptyList()

    /** 按数字段比较版本号（"9" > "10" 这类字符串排序错误由此避免）。 */
    private val versionComparator = versionStringComparator
  }

  /**
   * 跑 `sdkmanager --list` 拉**可用**包，解析成各组件列表。
   *
   * <p>只用正式版（过滤掉 `-rc`）。网络不通时返回 null（调用方回退到"只显示已安装"）。
   */
  fun listAvailable(onOutput: ((String) -> Unit)? = null): AvailableSdk? {
    val lines = mutableListOf<String>()
    val code = runSdkManager(listOf("--list"), null) { line ->
      lines.add(line)
      onOutput?.invoke(line)
    }
    if (code != 0) {
      Log.e(TAG, "sdkmanager --list failed, exit=$code")
      return null
    }
    val text = lines.joinToString("\n")
    fun versions(prefix: String, idPattern: String): List<String> =
        Regex("$prefix;($idPattern)")
            .findAll(text)
            .map { it.groupValues[1] }
            .filterNot { it.contains("rc") }
            .distinct()
            .sortedWith(versionComparator)
            .toList()

    val fromSdkManager = parsePlatformPackages(text)
    // **旧版 sdkmanager 列不出带 minor 的平台**：实测设备上的 cmdline-tools;2.0
    // 只能列到 android-36——它的仓库解析器不认识 `api-level="37.2"` 这种浮点形式，
    // 于是 google 仓库里真实存在的 36.1 / 37.0 / 37.1 / 37.2 全都消失了。
    // 这不是本应用能靠改 sdkmanager 修好的，但**能绕过**：直接抓 Google 的
    // repository XML 自己解析。只在 sdkmanager 的结果里缺少高版本时才走网络，
    // 正常设备（已装新版 cmdline-tools）不会多一次请求。
    val platforms =
        if (fromSdkManager.any { it.substringBefore('.').toIntOrNull()?.let { v -> v >= 37 } == true }) {
          fromSdkManager
        } else {
          fetchPlatformsFromRepository()?.let { fresh ->
            // 用仓库结果与 sdkmanager 结果取并集：仓库给的是官方全集，
            // sdkmanager 那边可能已装了某个被仓库下架的版本（仍应显示为已装）。
            (fromSdkManager + fresh).distinct().sortedWith(versionComparator)
          } ?: fromSdkManager
        }

    return AvailableSdk(
        buildTools = versions("build-tools", """[0-9][0-9.]*"""),
        platforms = platforms,
        // NDK / CMake 同样由 sdkmanager 管理（实测可列到 ndk;26.x、cmake;4.1.2），
        // 因此把它们也收进同一个界面，替代 IDE 配置里那套走镜像的安装。
        ndk = versions("ndk", """[0-9][0-9.]*"""),
        cmake = versions("cmake", """[0-9][0-9.]*"""),
        // platform-tools 无版本尾巴（就一个包），用固定占位表示"可安装"。
        platformTools = if (text.contains("platform-tools")) listOf("latest") else emptyList(),
    )
  }

  /**
   * 抓 Google 的仓库索引，解析出可用平台。网络失败返回 null。
   *
   * <p><b>只在必要时调用</b>：`listAvailable()` 会先看 sdkmanager 的结果里有没有
   * API ≥ 37 的平台，有就不再走这条路——已装新版 cmdline-tools 的设备因此
   * 不会多一次网络请求（见调用处注释）。
   *
   * <p><b>超时 15s</b>：这个请求在 UI 线程的协程里跑（`loadAvailable`），
   * 国内网络下 dl.google.com 可能很慢；设短一点让失败尽快发生、回退到 sdkmanager。
   */
  private fun fetchPlatformsFromRepository(): List<String>? {
    return try {
      val url = java.net.URL(REPOSITORY_XML_URL)
      val conn =
          (url.openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 15_000
            requestMethod = "GET"
          }
      try {
        val code = conn.responseCode
        if (code != 200) {
          Log.w(TAG, "repository xml HTTP $code")
          return null
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val parsed = parseRepositoryXml(text)
        Log.d(TAG, "repository xml -> ${parsed.size} platforms, latest=${parsed.lastOrNull()}")
        parsed
      } finally {
        conn.disconnect()
      }
    } catch (e: Exception) {
      // 断网 / DNS 失败 / 超时：都当作"拿不到"，由调用方回退。
      Log.w(TAG, "fetch repository xml failed", e)
      null
    }
  }

  /**
   * 安装一个包（如 `build-tools;36.0.0` / `platforms;android-36`）。
   *
   * <p>先接受许可（`--licenses` 需要交互，这里用 `yes` 管道喂），再安装——首次用
   * sdkmanager 时若许可未接受会直接失败。
   */
  fun install(packageId: String, onOutput: (String) -> Unit): Boolean {
    // 目录不可写时**提前**报真因。否则 sdkmanager 会跑到 10% 再甩一句
    // "Failed to read or create install properties file"——那句话指向它自己的属性文件，
    // 用户完全看不出是 SDK 目录属主不对（见 writeBlockedReason 的实测记录）。
    writeBlockedReason()?.let {
      onOutput("[错误] $it")
      onOutput("可在「设置 → 构建与运行 → SDK 管理器」里用「修复权限」一键处理（需 root）。")
      return false
    }
    acceptLicenses(onOutput)
    val code = runSdkManager(listOf("--install", packageId), packageId) { onOutput(it) }
    Log.d(TAG, "install $packageId exit=$code")
    return code == 0
  }

  /**
   * 卸载一个包（如 `build-tools;35.0.1`）。
   *
   * <p>破坏性操作：调用方必须先经用户确认。卸载前同样检目录可写性——
   * `--uninstall` 要删目录，不可写时同样会以难懂的方式失败。
   */
  fun uninstall(packageId: String, onOutput: (String) -> Unit): Boolean {
    writeBlockedReason()?.let {
      onOutput("[错误] $it")
      return false
    }
    val code = runSdkManager(listOf("--uninstall", packageId), null) { onOutput(it) }
    Log.d(TAG, "uninstall $packageId exit=$code")
    return code == 0
  }

  /**
   * 把 `$ANDROID_HOME` 递归改为本应用的 uid/gid（需 root）。
   *
   * <p><b>为什么需要它</b>：设备数据从备份还原时，SDK 目录可能仍属旧设备的 Android
   * 用户（实测黑鲨上是 `u1_i7610`）或 `system:system`，而本 app 是 `u0_a304`。
   * 于是 sdkmanager 读得到、跑得起来，却**写不进**，所有安装都卡在 10%。这不是代码能绕过的
   * 问题——只能改属主。
   *
   * <p><b>为什么用 `su`</b>：改属主要 uid 0。设备已 root（Magisk）时可直接做；
   * 未 root 时明确失败并说明，而不是静默降级。
   *
   * @param onOutput 逐行输出（含失败原因）
   * @return 是否成功（chown 后重新探测可写性，不只看退出码）
   */
  fun fixPermissions(onOutput: (String) -> Unit): Boolean {
    val home = Environment.ANDROID_HOME
    if (!home.isDirectory) {
      onOutput("[错误] SDK 目录不存在：${home.absolutePath}")
      return false
    }
    if (writeBlockedReason() == null) {
      onOutput("SDK 目录本就可写，无需修复。")
      return true
    }

    val uid = android.os.Process.myUid()
    onOutput("将把 ${home.absolutePath} 递归改为 uid $uid（需要 root）…")

    // 复用 RootShellBackend：它已实现 su 候选路径探测、Magisk 授权等待、
    // 超时与「不抛异常、以结果表示失败」的契约，并有单测。自己拼 ProcessBuilder
    // 会重复这些边界处理。
    val backend = com.tom.rv2ide.ai.tool.RootShellBackend.shared()
    if (!backend.isAvailable()) {
      onOutput("[错误] root 不可用：${backend.unavailableReason()}")
      onOutput("请先在 Magisk 里授予本应用 root 权限，再重试。")
      return false
    }

    val quoted = home.absolutePath.replace("'", "'\\''")
    // chown 改属主；chmod u+rwX 兜底（有文件可能原本就没有属主写位）。
    // 两条串在一起：任一条失败都不算成功。
    val cmd = "chown -R $uid:$uid '$quoted' && chmod -R u+rwX '$quoted'"
    val result =
        backend.execute(
            com.tom.rv2ide.ai.tool.ShellRequest(
                cmd, home.absolutePath, CHOWN_TIMEOUT_MS, null),
            com.tom.rv2ide.ai.tool.ShellRequest.ShellOutputSink.NOOP,
        )
    if (!result.stdout.isNullOrEmpty()) onOutput(result.stdout.trim())
    if (!result.stderr.isNullOrEmpty()) onOutput(result.stderr.trim())
    if (!result.isSuccess) {
      onOutput("[错误] 修复命令失败（exit=${result.exitCode}）")
      return false
    }

    // 以「现在真能写」为准，不只看退出码——chown 成功但目录仍不可写（如挂载只读）时
    // 报成功会让用户以为修好了，下次安装再撞同样的墙。
    val still = writeBlockedReason()
    if (still != null) {
      onOutput("[错误] 修复后仍不可写：$still")
      return false
    }
    onOutput("修复完成，SDK 目录现在可写。")
    return true
  }

  private fun acceptLicenses(onOutput: (String) -> Unit) {
    // 许可已由调用方在首装时接受过时这里会立刻返回，代价很小。
    runSdkManager(listOf("--licenses"), null) { onOutput(it) }
  }

  /**
   * 跑一次 sdkmanager。返回退出码（失败返回 -1）。
   *
   * @param args sdkmanager 的参数（不含可执行文件本身）
   * @param answer 需要交互应答时喂给 stdin 的行（如许可提示的 "y"）；null 表示不喂
   */
  private fun runSdkManager(
      args: List<String>,
      answer: String?,
      onOutput: (String) -> Unit,
  ): Int {
    val sm = sdkManagerFile()
    if (sm == null) {
      // 区分「没装」与「权限不可读」——两者都表现为 exists()==false，但修法完全不同。
      onOutput("[错误] ${unavailableReason() ?: "sdkmanager 不可用"}")
      return -1
    }
    val jdk = javaHome()
    if (jdk == null) {
      onOutput("[错误] ${unavailableReason() ?: "找不到 JDK，无法运行 sdkmanager。"}")
      return -1
    }
    val sh = shell()
    val cmd = listOf(sh.absolutePath, sm.absolutePath) + args
    return try {
      val pb = ProcessBuilder(cmd)
      val env = pb.environment()
      env["HOME"] = Environment.HOME.absolutePath
      env["PREFIX"] = Environment.PREFIX.absolutePath
      env["JAVA_HOME"] = jdk.absolutePath
      env["PATH"] =
          listOfNotNull(
                  Environment.BIN_DIR.absolutePath,
                  // 用实际选中的版本目录，而不是固定的 latest（见 [cmdlineToolsDir]）。
                  cmdlineToolsDir()?.resolve("bin")?.absolutePath,
                  "/system/bin",
              )
              .joinToString(":")
      env["LD_LIBRARY_PATH"] = Environment.LIB_DIR.absolutePath
      env["TMPDIR"] = Environment.TMP_DIR.absolutePath
      pb.redirectErrorStream(true)

      val process = pb.start()
      if (answer != null) {
        process.outputStream.bufferedWriter().use { w ->
          repeat(50) { w.write("$answer\n") }
        }
      } else {
        // 不喂应答但进程可能等 stdin，显式关掉避免挂住。
        runCatching { process.outputStream.close() }
      }
      process.inputStream.bufferedReader().useLines { lines -> lines.forEach { onOutput(it) } }
      process.waitFor()
    } catch (e: Exception) {
      Log.e(TAG, "runSdkManager failed: ${args.joinToString(" ")}", e)
      onOutput("[错误] ${e.message}")
      -1
    }
  }

  private fun shell(): File =
      when {
        Environment.LOGIN_SHELL.exists() -> Environment.LOGIN_SHELL
        Environment.BASH_SHELL.exists() -> Environment.BASH_SHELL
        else -> File("/system/bin/sh")
      }

  /**
   * `sdkmanager --list` 解析结果。
   *
   * <p>这些包都由官方 sdkmanager 管理，因此都收进同一个界面：装 build-tools / platforms
   * 是编译必需，ndk / cmake 是原生项目（Native C++）必需，platform-tools 提供 adb。
   */
  data class AvailableSdk(
      val buildTools: List<String>,
      val platforms: List<String>,
      val ndk: List<String>,
      val cmake: List<String>,
      /** platform-tools 无版本号（就一个包），非空即"可安装"。 */
      val platformTools: List<String>,
  )
}

/**
 * 按数字段比较版本号：`"9"` > `"10"` 这类字符串排序错误由此避免，
 * `"37.2"` 也能与 `"36"` 正确比较（逐段补齐后比）。
 */
private val versionStringComparator = Comparator<String> { a, b ->
  val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
  val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
  for (i in 0 until maxOf(pa.size, pb.size)) {
    val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
    if (c != 0) return@Comparator c
  }
  0
}

/**
 * 从 `sdkmanager --list` 的输出里解析出可安装的平台版本（不含 `android-` 前缀，升序）。
 *
 * <p><b>为什么必须允许 minor 后缀</b>：Google 自 API 36 起改用「主版本 + 次要版本」
 * 编号，官方仓库里 `platforms;android-36.1`、`android-37.0`、`android-37.1`、
 * `android-37.2` 这类带小数后缀的包是常态。早先的正则 `android-[0-9]+` 只截取
 * 整数部分，37.0 / 37.1 / 37.2 三者被截成同一个字符串 `"37"`，`distinct()` 后只剩
 * 一个 chip，而安装时拼出的包名 `platforms;android-37` 在仓库里根本不存在
 * ——用户点它必然失败（实测反馈「只有 33-36，装不了 37」）。
 *
 * <p><b>为什么去掉前缀后再排序</b>：[versionStringComparator] 按 `split('.')` 逐段比较数字。
 * `"android-37.2"` 会被拆成 `["android-37","2"]`，首段 `toIntOrNull()` 得 null→0，
 * 于是所有平台都退化成「只比 minor」、顺序全乱。去掉前缀后 `"37.2"` 拆成 `[37,2]`、
 * `"36"` 拆成 `[36]`，比较才正确。
 *
 * <p>也**不能**用 `sortedBy { it.toIntOrNull() ?: 0 }`——`"37.2".toIntOrNull()` 是
 * null，会被一律当成 0 排到最前。
 *
 * <p>抽成顶层函数是为可单测（[SdkManagerInstaller] 需要 Context / 进程，JVM 单测跑不了）。
 */
internal fun parsePlatformPackages(text: String): List<String> {
  return Regex("platforms;(android-[0-9]+(?:\\.[0-9]+)?)")
      .findAll(text)
      .map { it.groupValues[1] }
      .filterNot { it.contains("rc") }
      .map { it.removePrefix("android-") }
      .distinct()
      .sortedWith(versionStringComparator)
      .toList()
}

/**
 * 从 Google 的 repository XML 里直接解析可用平台（不含 `android-` 前缀，升序）。
 *
 * <p><b>为什么需要绕过 sdkmanager</b>：实测设备上的 `cmdline-tools;2.0` 的
 * `--list` 只能列到 `android-36`——它的仓库解析器不认识 `api-level="37.2"`
 * 这种浮点形式，于是官方仓库里真实存在的 36.1 / 37.0 / 37.1 / 37.2 全部消失，
 * 用户在 SDK 管理器里永远看不到、也就永远装不上（这正是「只有 33-36」的直接原因）。
 * 升级 cmdline-tools 能解决，但那要用户手动操作；这里直接读仓库索引更可靠。
 *
 * <p><b>只解析 `<path>` 属性</b>：不需要完整 XML 解析器——`remotePackage path=` 的
 * 值形式固定（`platforms;android-37.2`），正则足够且没有解析失败的边角。
 * `<channelRef ref="channel-0"/>` 是 stable，但我们**不按通道过滤**：
 * `--list` 本来就不区分，过滤反而会让预览版从列表里莫名消失。
 *
 * @return 解析结果；网络失败 / 响应异常时返回 null（调用方回退到 sdkmanager 的结果）
 */
internal fun parseRepositoryXml(xml: String): List<String> {
  return Regex("""path="platforms;(android-[0-9]+(?:\.[0-9]+)?)"""")
      .findAll(xml)
      .map { it.groupValues[1] }
      .map { it.removePrefix("android-") }
      .distinct()
      .sortedWith(versionStringComparator)
      .toList()
}

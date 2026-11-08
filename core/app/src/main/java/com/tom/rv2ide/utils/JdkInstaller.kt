/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.utils

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 用 Termux 的 `apt` 安装 / 卸载 JDK。
 *
 * <p><b>为什么不能用 sdkmanager 那套</b>：JDK **没有**独立安装器。SDK 组件由官方
 * `sdkmanager` 管理（可下载到任意目录），而 JDK 是 Termux 的 apt 包，装进
 * `$PREFIX/lib/jvm/`——也正是 [JdkUtils.findJavaInstallations] 唯一扫描的位置。
 * 因此「装其他 JDK 版本」只能是 apt。
 *
 * <p><b>必须在 app 自己的 uid 下跑</b>（`/system/bin/sh -c`，由 Termux 环境变量驱动），
 * 不要用 root：apt 的包数据库在 $PREFIX 下，root 跑会留下 root 属主的文件，
 * 之后 app uid 再装就写不进去了（与 SDK 目录那个坑同类）。
 */
class JdkInstaller {

  companion object {
    private const val TAG = "JdkInstaller"

    /** apt 超时：下载 + 解包几十 MB，给足时间。 */
    private const val APT_TIMEOUT_MS = 10 * 60 * 1000L

    /** 读线程 join 的上限：进程已结束，读线程应立刻收尾；给个上限避免卡住。 */
    private const val READER_JOIN_MS = 2_000L

    /** 查询可用包列表的超时（走 apt-cache，是本地索引，正常不到 1 秒）。 */
    private const val QUERY_TIMEOUT_MS = 30_000L

    /** 包名前缀（apt 包名 = `openjdk-<大版本>`）。 */
    private const val PACKAGE_PREFIX = "openjdk-"

    /**
     * 兜底版本列表。
     *
     * <p>**只在运行时查询失败时用**（如 apt 索引还没建、仓库临时不可达）。
     * 正常路径是 [discoverAvailable]——实测源里到底有几个版本是会变的，
     * 写死会让「仓库加了 JDK 25」必须发新版才支持。这两个值来自 2026-11-08
     * 在 ACS 包源（`acs-packages-arm64-64c907.gitlab.io`）上的实测结果。
     */
    @JvmField val FALLBACK_VERSIONS: List<String> = listOf("17", "21")

    /**
     * 从 apt 源动态发现可安装的 JDK 大版本（升序），如 `["17", "21"]`。
     *
     * <p>用 `apt-cache pkgnames openjdk-` + 过滤纯数字后缀：排除 `openjdk-17-source`
     * （源码包）、`openjdk-17-x`（X11 部分）这些**不是 JDK 本体**的包。
     *
     * <p>失败（命令跑不起来 / 查不到任何版本）时回退到 [FALLBACK_VERSIONS]，
     * 而不是返回空——空列表会让界面一张卡都不显示，比「可能少一个版本」更糟。
     */
    @JvmStatic
    fun discoverAvailable(): List<String> {
      val out = runApt(listOf("apt-cache", "pkgnames", PACKAGE_PREFIX), QUERY_TIMEOUT_MS)
      if (out == null) {
        Log.w(TAG, "apt-cache pkgnames 失败，用兜底版本列表 $FALLBACK_VERSIONS")
        return FALLBACK_VERSIONS
      }
      val versions =
          out.lineSequence()
              .map { it.trim() }
              .filter { it.startsWith(PACKAGE_PREFIX) }
              .map { it.removePrefix(PACKAGE_PREFIX) }
              .filter { it.isNotEmpty() && it.all(Char::isDigit) }
              .distinct()
              .sortedBy { it.toIntOrNull() ?: 0 }
              .toList()
      if (versions.isEmpty()) {
        Log.w(TAG, "apt-cache 未列出任何 JDK，用兜底版本列表 $FALLBACK_VERSIONS")
        return FALLBACK_VERSIONS
      }
      return versions
    }

    /** 大版本号 → apt 包名（如 "21" → "openjdk-21"）。 */
    @JvmStatic fun packageFor(javaVersion: String): String = "$PACKAGE_PREFIX$javaVersion"

    /**
     * 跑一条 apt 工具命令并取回 stdout；失败返回 null。
     *
     * <p>与 [apt] 不同：这里只要输出、不要逐行回调，且失败要能与「输出为空」区分开，
     * 所以单独一条路径而不是复用。
     */
    private fun runApt(argv: List<String>, timeoutMs: Long): String? {
      if (Environment.PREFIX?.isDirectory != true) return null
      val prefix = Environment.PREFIX.absolutePath
      val script =
          "export PREFIX='$prefix'; " +
              "export PATH=\"\$PREFIX/bin:\$PATH\"; " +
              "export LD_LIBRARY_PATH=\"\$PREFIX/lib\"; " +
              "export HOME='${Environment.HOME.absolutePath}'; " +
              argv.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" } +
              " 2>/dev/null"
      return try {
        val process = ProcessBuilder("/system/bin/sh", "-c", script).redirectErrorStream(true).start()
        // 与 apt() 同样的顾虑：读线程 + 带超时的 waitFor，两条缺一不可。
        val out = StringBuilder()
        val reader = Thread { runCatching { process.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } } }
        reader.start()
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
          process.destroyForcibly()
          reader.join(READER_JOIN_MS)
          return null
        }
        reader.join(READER_JOIN_MS)
        if (process.exitValue() != 0) null else out.toString()
      } catch (e: Exception) {
        Log.w(TAG, "apt 命令失败：${argv.joinToString(" ")}", e)
        null
      }
    }
  }

  /**
   * 该 JDK 版本是否已装。
   *
   * <p>判据是**磁盘上的目录**（`$PREFIX/lib/jvm/java-<v>-openjdk`）而不是 `apt list`：
   * [JdkUtils] 也是按目录识别的，两者判据一致才不会出现「apt 说装了、列表里没有」。
   */
  fun isInstalled(javaVersion: String): Boolean = jvmDir(javaVersion)?.isDirectory == true

  /** 某个版本对应的 JDK 目录；不存在返回 null。 */
  fun jvmDir(javaVersion: String): File? =
      File(Environment.PREFIX, "lib/jvm/java-$javaVersion-openjdk")

  /** 安装一个 JDK 版本（如 "21"）。返回是否成功。 */
  fun install(javaVersion: String, onOutput: (String) -> Unit): Boolean {
    val pkg = packageFor(javaVersion)
    if (pkg == null) {
      onOutput("[错误] 不支持的 JDK 版本：$javaVersion")
      return false
    }
    if (isInstalled(javaVersion)) {
      onOutput("JDK $javaVersion 已安装，无需重复安装。")
      return true
    }
    onOutput("正在安装 $pkg（走 Termux 的 apt，需要联网）…")
    val ok = apt(listOf("install", "-y", pkg), onOutput)
    if (!ok) return false
    // 以磁盘为准复查：apt 退出码为 0 但目录没出现，等于没装成，不能报成功。
    if (!isInstalled(javaVersion)) {
      onOutput("[错误] apt 报告成功，但 ${jvmDir(javaVersion)?.absolutePath} 未出现。")
      return false
    }
    onOutput("JDK $javaVersion 安装完成。")
    return true
  }

  /**
   * 卸载一个 JDK 版本。返回是否成功。
   *
   * <p><b>调用方必须先确认它不是当前选中的 JDK</b>：IDE 自身就跑在
   * `Environment.JAVA_HOME` 指向的那个 JDK 上，删掉它会把 IDE 弄崩。
   * 这里只做卸载，不替调用方判断——但会在输出里再次提醒。
   */
  fun uninstall(javaVersion: String, onOutput: (String) -> Unit): Boolean {
    val pkg = packageFor(javaVersion)
    if (pkg == null) {
      onOutput("[错误] 不支持的 JDK 版本：$javaVersion")
      return false
    }
    if (!isInstalled(javaVersion)) {
      onOutput("JDK $javaVersion 未安装。")
      return true
    }
    onOutput("正在卸载 $pkg…")
    val ok = apt(listOf("remove", "-y", pkg), onOutput)
    if (!ok) return false
    if (isInstalled(javaVersion)) {
      onOutput("[错误] apt 报告成功，但目录仍在：${jvmDir(javaVersion)?.absolutePath}")
      return false
    }
    onOutput("JDK $javaVersion 已卸载。")
    return true
  }

  /**
   * 跑一次 apt。
   *
   * <p>用 `/system/bin/sh -c` + 显式导出的 Termux 环境变量，而不是
   * `TermuxShellEnvironment`：后者要拿 `IDEApplication.instance`，而这个类被
   * 设置页直接调用，构造时机可能早于 Application 就绪。显式 export 更稳，
   * 且与 [SdkManagerInstaller] 的做法一致。
   */
  private fun apt(args: List<String>, onOutput: (String) -> Unit): Boolean {
    val prefix = Environment.PREFIX.absolutePath
    // DEBIAN_FRONTEND 为非交互：apt 有时会弹 debconf 提示，无 tty 时会挂住。
    val script =
        "export PREFIX='$prefix'; " +
            "export PATH=\"\$PREFIX/bin:\$PATH\"; " +
            "export LD_LIBRARY_PATH=\"\$PREFIX/lib\"; " +
            "export HOME='${Environment.HOME.absolutePath}'; " +
            "export DEBIAN_FRONTEND=noninteractive; " +
            "apt-get ${args.joinToString(" ")} 2>&1"
    return try {
      val pb = ProcessBuilder("/system/bin/sh", "-c", script)
      pb.redirectErrorStream(true)
      val process = pb.start()
      // 读放在独立线程：两条路都要走通，缺一条就出错——
      // 在主线程直接读会撑满管道缓冲（apt 输出量大）而死锁；
      // 只在主线程 waitFor 则进程挂住时永远等不到超时生效。
      val reader =
          Thread {
            try {
              process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { onOutput(it) }
              }
            } catch (e: Exception) {
              // 进程被强杀时读会抛，忽略：退出码已表达失败。
            }
          }
      reader.start()
      val finished = process.waitFor(APT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      if (!finished) {
        process.destroyForcibly()
        reader.join(READER_JOIN_MS)
        onOutput("[错误] apt 超时（${APT_TIMEOUT_MS / 60000} 分钟）")
        return false
      }
      reader.join(READER_JOIN_MS)
      process.exitValue() == 0
    } catch (e: Exception) {
      onOutput("[错误] ${e.message}")
      false
    }
  }

}

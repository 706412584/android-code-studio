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

    /** 可安装的 JDK 版本 → apt 包名。 */
    @JvmField
    val AVAILABLE: List<Pair<String, String>> =
        listOf("17" to "openjdk-17", "21" to "openjdk-21")

    /** 已安装的 apt JDK 包名（用于「卸掉哪个」）。 */
    fun packageFor(javaVersion: String): String? =
        AVAILABLE.firstOrNull { it.first == javaVersion }?.second
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

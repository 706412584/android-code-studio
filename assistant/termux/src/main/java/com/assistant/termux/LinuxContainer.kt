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

package com.assistant.termux

import android.content.Context
import java.io.File

/**
 * Linux 容器（proot + rootfs）——**精简版**，只做「起一个能用的 shell」。
 *
 * <p><b>为什么用 proot 而不是 Termux bootstrap</b>：proot 不需要 root，靠 ptrace 重写
 * 路径系统调用，把任意 rootfs 伪装成 {@code /}。它能直接跑官方 Ubuntu base 镜像，
 * 因而有完整的 apt 与常用命令；而 bootstrap 是 Termux 自己裁剪的一套，命令少且与
 * 发行版习惯不同（用户明确要求「要么 tex 要么乌班图」，选乌班图）。
 *
 * <p><b>不照抄参考项目</b>：{@code D:/app/android/android} 的 ContainerManager 有 1186 行，
 * 其中大量是它自己的业务加固（SysV 共享内存替身给 PostgreSQL、libatomic 给 Node 26、
 * git 代理改写、dpkg 自愈、多发行版/镜像分层……）。本 App 没有这些场景，
 * 照抄只会引入无法验证的代码。这里只保留**让 shell 能起来**的最小集，
 * 并在每一处说明「为什么这一条不能省」。
 */
class LinuxContainer(private val context: Context) {

  companion object {
    /** Ubuntu LTS 系列。点版本动态取（见 [resolveArchiveNames]）。 */
    private const val UBUNTU_SERIES = "24.04"

    /**
     * rootfs 下载源，按顺序尝试。
     *
     * <p>只用国内镜像：设备常挂代理，直连 cdimage.ubuntu.com 实测超时。
     * 三个源覆盖不同网络环境（教育网/阿里云/华为云）。
     */
    private val UBUNTU_MIRRORS =
        listOf(
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage",
            "https://mirrors.aliyun.com/ubuntu-cdimage",
            "https://repo.huaweicloud.com/ubuntu-cdimage",
        )

    /**
     * proot 五件套的文件名。
     *
     * <p>以 {@code lib*.so} 形式放进 jniLibs 而不是 assets：{@code nativeLibraryDir} 是
     * App 私有目录里**唯一允许 execve 的位置**（targetSdk 29+ 的 W^X 会拒绝执行
     * filesDir 下的文件）。放进 jniLibs 后系统还会自动按 ABI 解压到该目录。
     */
    /** 自报身份的 UA。空 UA 或 curl 型 UA 会被部分国内镜像 403。 */
    const val UA = "Mozilla/5.0 (Linux; Android) ACS-Assistant/1.0"

    private val PROOT_FILES =
        listOf(
            "libproot.so",
            "libproot-loader.so",
            "libproot-loader32.so",
            "libtalloc.so",
            "libandroid-shmem.so",
        )
  }

  /** rootfs 存放位置。放 filesDir 而不是外部存储：rootfs 含大量符号链接，FUSE 不支持。 */
  val rootfsDir: File
    get() = File(context.filesDir, "linux").apply { mkdirs() }

  /** 主 rootfs 目录。 */
  val rootfs: File
    get() = File(rootfsDir, "ubuntu-$UBUNTU_SERIES")

  /** 工作区：与助手的 filesDir/workspace 是同一个目录，容器内外看到同一份文件。 */
  val workspace: File
    get() = File(context.filesDir, "workspace").apply { mkdirs() }

  /** proot 本体所在目录（= nativeLibraryDir）。 */
  private val prootDir: File
    get() = File(context.applicationInfo.nativeLibraryDir)

  private val prootBin: File
    get() = File(prootDir, "libproot.so")

  private val prootLoader: File
    get() = File(prootDir, "libproot-loader.so")

  private val prootLoader32: File
    get() = File(prootDir, "libproot-loader32.so")

  /** proot 全套是否就绪（jniLibs 配置正确、已被系统解压）。 */
  val isProotAvailable: Boolean
    get() = prootBin.isFile && prootLoader.isFile

  /**
   * rootfs 是否已安装。
   *
   * <p>判据是 `usr/bin` 而不是 `bin`：Ubuntu base 里 `/bin` 是指向 `usr/bin` 的
   * **符号链接**（`bin -> usr/bin`），`File.isDirectory` 对软链返回 false，
   * 用它会得出「装好了却说没装」的错误结论。
   */
  val isRootfsInstalled: Boolean
    get() = File(rootfs, "usr/bin").isDirectory

  /** 设备架构在 Ubuntu 命名里的叫法（arm64 / amd64，不是 aarch64 / x86_64）。 */
  private fun ubuntuAbi(): String =
      when {
        android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } -> "arm64"
        android.os.Build.SUPPORTED_ABIS.any { it == "x86_64" } -> "amd64"
        else -> "arm64"
      }

  /**
   * 列出各镜像上可用的 rootfs 文件名。
   *
   * <p><b>为什么动态列目录而不是写死版本号</b>：Ubuntu 的 cdimage **只保留最近几个点版本**，
   * 实测写死 `24.04.2` 时三个镜像分别是 403/404/404（`24.04.3` 已发布、旧版被清理）。
   * 硬编码版本号必然在几周内失效。
   *
   * <p>先请镜像的目录索引，从中挑出属于 [UBUNTU_SERIES] 的 arm64 归档并按版本号倒序。
   * 索引拿不到（部分镜像不开放目录列表）时返回空，由调用方跳过该镜像。
   */
  fun listRootfsArchives(mirrorBase: String, timeoutMs: Int = 20_000): List<String> {
    val dir = "$mirrorBase/ubuntu-base/releases/$UBUNTU_SERIES/release/"
    val html =
        try {
          val conn = (java.net.URL(dir).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", UA)
          }
          try {
            if (conn.responseCode !in 200..299) return emptyList()
            conn.inputStream.bufferedReader().use { it.readText() }
          } finally {
            conn.disconnect()
          }
        } catch (e: Exception) {
          return emptyList()
        }
    val abi = ubuntuAbi()
    val pattern = Regex("ubuntu-base-(\\d+\\.\\d+\\.\\d+)-base-$abi\\.tar\\.gz")
    return pattern
        .findAll(html)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .distinct()
        // 版本号倒序：优先最新（旧的可能随时被上游清掉）。
        // 用逐段数值比较而不是字符串比较——字符串比较会把 "24.04.9" 排在 "24.04.10" 之后。
        .sortedWith { a, b -> compareVersions(b, a) }
        .map { "$dir" + "ubuntu-base-$it-base-$abi.tar.gz" }
        .toList()
  }

  /** 逐段数值比较版本号（"24.04.10" > "24.04.9"）。 */
  private fun compareVersions(a: String, b: String): Int {
    val pa = a.split('.')
    val pb = b.split('.')
    for (i in 0 until maxOf(pa.size, pb.size)) {
      val va = pa.getOrNull(i)?.toIntOrNull() ?: 0
      val vb = pb.getOrNull(i)?.toIntOrNull() ?: 0
      if (va != vb) return va - vb
    }
    return 0
  }

  /**
   * rootfs 下载地址候选，按顺序尝试。
   *
   * <p>每个镜像先列目录取所有可用版本，再有版本号的按新到旧排——单个镜像内多个候选
   * 可以抵御「最新版刚好被清理」的情况。
   */
  fun rootfsUrls(): List<String> =
      UBUNTU_MIRRORS.flatMap { mirror -> listRootfsArchives(mirror) }

  /**
   * 构造进容器的 proot 命令行。
   *
   * <p>每一条 {@code -b} 都有理由，删任何一条都会导致某类程序起不来：
   * <ul>
   *   <li>{@code /dev /proc /sys} —— 缺了连 shell 都跑不起来（无 /dev/null、无 /proc/self）
   *   <li>{@code /system} —— Ubuntu 里的 bionic 二进制要靠宿主 /system/lib64 的动态库
   *   <li>{@code /apex}（仅 Android 10+）—— {@code /system/bin/linker64} 是软链到
   *       {@code /apex/com.android.runtime/bin/linker64}；不绑它，所有 bionic 程序报
   *       「not found」，而那不是缺库、是找不到解释器，极难排查
   *   <li>{@code -0} —— 伪 root。apt/dpkg 需要 uid 0 才肯工作
   *   <li>工作区绑定 —— 容器内 {@code /root/workspace} 与助手的 workspace 是同一份文件
   * </ul>
   *
   * @param extraArgs 追加在最后（如 {@code /bin/sh} 或 {@code /bin/bash -lc "cmd"}）
   */
  fun buildArgv(extraArgs: List<String>): List<String> {
    val argv =
        mutableListOf(
            prootBin.absolutePath,
            "-r",
            rootfs.absolutePath,
            "-b",
            "/dev",
            "-b",
            "/proc",
            "-b",
            "/sys",
            "-b",
            "/system",
        )
    // Android 9 及以下没有 /apex；proot 绑定不存在的源路径会直接报错，因此必须先判存在。
    if (File("/apex").isDirectory) {
      argv.addAll(listOf("-b", "/apex"))
    }
    argv.addAll(listOf("-0"))

    // 抑制 proot 自身的启动日志。
    //
    // <p>不加的话，容器一起来就有一屏 `proot info: vpid ... / getcwd / can't chdir to
    // '/data/data/...` 之类的内部信息糊在终端顶部（用 `-0` 伪 root 时尤其多），
    // 用户要往上翻很久才看到自己的提示符。参考项目不受影响是因为它另有处理，
    // 而 proot 的标准做法就是把 verbose 级别降到最低。
    argv.addAll(listOf("-v", "-1"))
    // 工作区双向绑定：助手写的文件终端能看到，终端生成的文件助手也能读。
    argv.addAll(listOf("-b", "${workspace.absolutePath}:/root/workspace"))
    argv.addAll(extraArgs)
    return argv
  }

  /**
   * 容器内环境变量。
   *
   * <p>**proot 不继承宿主环境**（{@code TerminalSession} 收的是完整数组），因此
   * 这里必须显式给全，缺一个 proot 就起不来。
   */
  fun buildEnv(): Map<String, String> =
      buildMap {
        // proot 自己的临时目录。放 rootfs 的 tmp 而不是 App cache：
        // 系统清理 cache 会让正在运行的 proot 突然失去临时空间。
        put("PROOT_TMP_DIR", File(rootfs, "tmp").apply { mkdirs() }.absolutePath)
        // Termux 版 proot 的 loader 是分离的，必须显式指路，否则无法注入子进程。
        put("PROOT_LOADER", prootLoader.absolutePath)
        put("PROOT_LOADER_32", prootLoader32.absolutePath)
        // proot 动态链接 libtalloc / libandroid-shmem，二者与它同在 nativeLibraryDir。
        put("LD_LIBRARY_PATH", "${prootDir.absolutePath}:/system/lib64:/system/lib")
        // libandroid-shmem 把 SysV IPC key 映射到编译期写死的 Termux 路径
        // （/data/data/com.termux/...），本 App 写不进去 → 无限重试、卡死。
        // 置空后 proot 走自带的 memfd 实现。不开 --sysvipc 时该变量无人读取。
        put("PROOT_DONT_SHARE_LIBANDROID_SHMEM", "1")
        put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        put("HOME", "/root")
        put("TERM", "xterm-256color")
        put("LANG", "C.UTF-8")
        // 宿主 TMPDIR 指向的路径容器内不存在，必须覆盖。
        put("TMPDIR", "/tmp")
        // apt/git 在 FUSE 挂载的工作区上会因「属主与 euid 不一致」拒绝操作
        // （dubious ownership）。单用户沙盒，全量放行。
        put("GIT_CONFIG_COUNT", "1")
        put("GIT_CONFIG_KEY_0", "safe.directory")
        put("GIT_CONFIG_VALUE_0", "*")
      }

  /** proot 五件套是否都在 jniLibs 里（诊断用）。 */
  fun missingProotFiles(): List<String> = PROOT_FILES.filter { !File(prootDir, it).isFile }

  /**
   * 设置终端配色为深色。
   *
   * <p><b>为什么必须显式设</b>：`TerminalColors.COLOR_SCHEME` 是进程级静态单例，
   * 默认前景色是**近白色**。本 App 的窗口背景是浅色（系统 `Theme.Material.Light`），
   * 于是出现白字白底——实测整屏几乎读不出内容（只有极近的灰白差异）。
   *
   * <p>它必须在**建会话之前**调用：`TerminalEmulator` 构造时把当时的
   * `COLOR_SCHEME.mDefaultColors` 拷进自己的实例，改晚了不生效。
   *
   * <p>用 Termux 自带的 Default 深色值（与 Termux 官方 App 一致），而不是自创配色——
   * 用户对终端配色有自己的预期，照抄主流值最不容易出错。
   */
  fun applyDarkColorScheme() {
    val props = java.util.Properties()
    props["foreground"] = "#FFFFFF"
    props["background"] = "#000000"
    props["cursor"] = "#FFFFFF"
    // 标准 ANSI 16 色（与 Termux 默认主题同值）。
    val ansi =
        listOf(
            "#000000", "#CD0000", "#00CD00", "#CDCD00",
            "#0000EE", "#CD00CD", "#00CDCD", "#E5E5E5",
            "#7F7F7F", "#FF0000", "#00FF00", "#FFFF00",
            "#5C5CFF", "#FF00FF", "#00FFFF", "#FFFFFF",
        )
    ansi.forEachIndexed { index, color -> props["color$index"] = color }
    com.termux.terminal.TerminalColors.COLOR_SCHEME.updateWith(props)
  }
}

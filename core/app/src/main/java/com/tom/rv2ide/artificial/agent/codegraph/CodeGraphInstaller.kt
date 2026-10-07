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

package com.tom.rv2ide.artificial.agent.codegraph

import android.content.Context
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * CodeGraph 的安装布局与就绪判定。
 *
 * <p><b>为什么装在 Termux 前缀之外</b>：程序本体（约 134MB）与 Termux 的包管理无关，
 * 放进 `$PREFIX` 会被 `apt` 当成「不属于任何包的文件」——`apt-get upgrade` 时不冲突，
 * 但 `dpkg --verify` 之类的操作会一直报未知文件。放在 `$HOME/codegraph` 更干净，
 * 卸载也只是删一个目录。
 *
 * <p><b>为什么不带自带的 node</b>：官方包内含 116MB 的 node 运行时，但它是 glibc 链接
 * （`PT_INTERP: /lib/ld-linux-aarch64.so.1`），Android 的 bionic 加载不了。改用 Termux
 * 的 node 执行同一份 JS——已实测可行，且省下 116MB。同理 34MB 的 Rust kernel
 * （`codegraph-kernel.node`）也依赖 glibc，去掉后自动降级到 WASM 解析器。
 *
 * <p><b>索引放哪</b>：由 codegraph 自己放在**项目内**的 `.codegraph/`。这是它的默认行为，
 * 好处是索引随项目走、每个项目独立；代价是 `.codegraph/` 会出现在文件树里，因此本类
 * 提供 [ensureGitIgnore] 在项目创建/首次索引时把它加进 `.gitignore`。
 */
object CodeGraphInstaller {

  /** 程序目录：`$HOME/codegraph`。 */
  const val INSTALL_DIR_NAME = "codegraph"

  /** 解包后的包目录名（tar 内的顶层目录）。 */
  private const val PACKAGE_DIR_NAME = "package"

  /** 入口 JS 相对包根目录的路径。 */
  private const val ENTRY_RELATIVE = "lib/dist/bin/codegraph.js"

  /** wrapper 脚本名；AI 与用户都用这一个入口。 */
  const val WRAPPER_NAME = "acs-codegraph"

  /** 索引目录名（codegraph 自己在项目内创建）。 */
  const val INDEX_DIR_NAME = ".codegraph"

  /**
   * 运行 codegraph 所需的 Termux 包。
   *
   * <p>node 提供运行时；其余三个是 node 的动态库依赖（实测 `libicu`/`libsqlite`/`c-ares`
   * 在 ACS 的 Termux 环境里默认缺失，缺了 node 直接起不来）。
   */
  val REQUIRED_PACKAGES = listOf("nodejs", "c-ares", "libicu", "libsqlite")

  /** Termux 前缀：`/data/data/<pkg>/files/usr`。 */
  fun prefixDir(): File = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH)

  /** Termux home：`/data/data/<pkg>/files/home`。 */
  fun homeDir(): File = File(TermuxConstants.TERMUX_HOME_DIR_PATH)

  /** 安装根目录。 */
  fun installDir(): File = File(homeDir(), INSTALL_DIR_NAME)

  /** 解包后的包目录。 */
  fun packageDir(): File = File(installDir(), PACKAGE_DIR_NAME)

  /** 入口 JS 文件。 */
  fun entryScript(): File = File(packageDir(), ENTRY_RELATIVE)

  /** wrapper 脚本（放在 `$PREFIX/bin`，因此 `PATH` 里有它时可直接按名字调用）。 */
  fun wrapperFile(): File = File(prefixDir(), "bin/$WRAPPER_NAME")

  /** Termux 的 node 可执行文件。 */
  fun nodeBinary(): File = File(prefixDir(), "bin/node")

  /**
   * 是否已安装**且**可用。
   *
   * <p>三个条件缺一不可：入口 JS 存在、Termux 的 node 存在、wrapper 存在。
   * 只看入口 JS 会把「装了一半」（解包完成但 node 没装上）判成就绪，然后 AI 调用时失败。
   *
   * <p><b>为什么不检查补丁文件名</b>：补丁文件名不是契约。旧版本（以及 AI 在现场
   * 诊断时）可能生成过不同名字的补丁（实测设备上存在 `.cg-realpath-fix.cjs`），
   * 而 wrapper 引用的才是真正生效的那个。按固定文件名判定会让这些设备被判成
   * 「未安装」——比原问题更糟：工具被禁用，用户还看不出原因。
   *
   * <p>真正要保证的是「wrapper 能跑起来」。wrapper 缺失或不可执行时，
   * {@code --require} 指向什么文件都无所谓——调用会失败并报错，用户能看见。
   * 补丁的**安装**由 {@link #writeRealpathFix} 与自愈逻辑保证（见 AgentOrchestrator
   * 的 isReady）。
   */
  fun isInstalled(): Boolean =
      entryScript().isFile && nodeBinary().isFile && wrapperFile().isFile

  /** 缺失的依赖包（已装的会被排除）。用于安装前给用户看「还要装什么」。 */
  fun missingPackages(): List<String> = REQUIRED_PACKAGES.filterNot { isPackageInstalled(it) }

  /**
   * 某个 Termux 包是否已安装。
   *
   * <p>判据是 `$PREFIX/var/lib/dpkg/info/<pkg>.list` 存在——这是 dpkg 自己的安装清单，
   * 比「二进制在不在」更准（nodejs 的二进制叫 `node`，与包名不同）。
   */
  fun isPackageInstalled(pkg: String): Boolean =
      File(prefixDir(), "var/lib/dpkg/info/$pkg.list").isFile

  /** realpath 兜底补丁的文件名（放在 Termux home 下）。 */
  const val REALPATH_FIX_NAME = ".codegraph-realpath-fix.cjs"

  /** 补丁文件。 */
  fun realpathFixFile(): File = File(homeDir(), REALPATH_FIX_NAME)

  /**
   * realpath 兜底补丁的 JS 内容。
   *
   * <p><b>为什么需要它</b>：项目位于 `/storage/emulated/0/...`（外部存储，所有 ACS 项目
   * 的默认位置）时，Node 的 JS 版 `fs.realpathSync` 会失败——它逐级 lstat 路径分量，
   * 而 `/storage/emulated` 这一级在该进程的挂载命名空间里 lstat 返回 ENOENT
   * （Android 的 scoped storage 怪癖：`/storage/emulated/0` 本身可 stat，但中间层不行）。
   * realpath 失败 → codegraph 的目录遍历认为整个树不可达 → **索引扫到 0 个文件**，
   * 而 `status` 仍显示 "Index is up to date"（DB 是空的，与磁盘一致）。
   *
   * <p><b>为什么用 `.native` 兜底</b>：内核的 realpath(3) 走 VFS 路径解析，不经 JS 的
   * 逐级 lstat，因此能正常解析同一路径。实测（黑鲨 SKW-A0 / Android 10）打上补丁后
   * 索引从 0 文件变为 425 文件 / 7800 节点。
   *
   * <p>补丁同时覆盖同步与 Promise 两个 API：codegraph 的扫描路径用同步版，
   * 部分 MCP 入口用异步版。
   *
   * <p>只包一层 try/catch 兜底，不改变成功路径的行为——非外部存储的路径
   * （例如 `$HOME` 下的临时索引）行为与打补丁前完全一致。
   */
  fun realpathFixScript(): String {
    return buildString {
      append("'use strict';\n")
      append("// Android: Node 的 JS 版 fs.realpathSync 在 /storage/emulated 上 ENOENT,\n")
      append("// 内核 realpath(3)(realpathSync.native)正常。给 realpath 系列加 native 兜底。\n")
      append("// 由 AndroidCodeStudio 生成，请勿手改——重装或更新会覆盖它。\n")
      append("const fs = require('fs');\n")
      append("const nativeSync = fs.realpathSync.native.bind(fs);\n")
      append("const origSync = fs.realpathSync;\n")
      append("fs.realpathSync = function (p, options) {\n")
      append("  try { return origSync.call(fs, p, options); }\n")
      append("  catch (err) { return nativeSync(p, options); }\n")
      append("};\n")
      append("fs.realpathSync.native = nativeSync;\n")
      append("if (fs.promises && typeof fs.promises.realpath === 'function') {\n")
      append("  const origP = fs.promises.realpath;\n")
      append("  fs.promises.realpath = async function (p, options) {\n")
      append("    try { return await origP.call(fs.promises, p, options); }\n")
      append("    catch { return nativeSync(p, options); }\n")
      append("  };\n")
      append("}\n")
    }
  }

  /**
   * wrapper 是否已引用 realpath 补丁（用于「旧版安装」的自愈判定）。
   *
   * <p>判据是「wrapper 里有没有 `--require`」而不是「某个文件名是否存在」：
   * 补丁文件名不是契约，不同版本/手工修复可能用不同名字（实测设备上存在
   * `.cg-realpath-fix.cjs`）。只要 wrapper 引用了**任一** `--require`，
   * 就说明它已是打过补丁的版本，不必重写。
   *
   * <p>读不到 wrapper（不存在/无权限）时返回 false——那本来就会走重写路径。
   *
   * <p><b>不只看 `--require` 这个词，还要看它引用的文件是否存在</b>：wrapper 可能引用
   * 一个已被删除的补丁（AI 现场生成的 `.cg-realpath-fix.cjs` 被清理、或用户手工改过
   * wrapper）。那种情况下 node 会因 `Cannot find module` 直接退出，而只检查关键字的
   * 判定会认为「已修好」→ 不重写 → 工具被启用却永远失败，用户看不出原因。
   */
  fun wrapperReferencesRealpathFix(): Boolean {
    val wrapper = wrapperFile()
    if (!wrapper.isFile) {
      return false
    }
    return try {
      val text = wrapper.readText(Charsets.UTF_8)
      val marker = "--require \""
      val start = text.indexOf(marker)
      if (start < 0) {
        return false
      }
      val pathStart = start + marker.length
      val pathEnd = text.indexOf('"', pathStart)
      if (pathEnd <= pathStart) {
        return false
      }
      // 引用的补丁文件必须真实存在——否则视为「未修好」，交给自愈重写 wrapper。
      File(text.substring(pathStart, pathEnd)).isFile
    } catch (e: java.io.IOException) {
      false
    }
  }

  /** 写入 realpath 补丁。 */
  fun writeRealpathFix(): File {
    val file = realpathFixFile()
    file.parentFile?.mkdirs()
    FileOutputStream(file).use { it.write(realpathFixScript().toByteArray(Charsets.UTF_8)) }
    return file
  }

  /**
   * wrapper 脚本内容。
   *
   * <p><b>为什么必须有 wrapper</b>：AI 的 `shell_execute` 用 `/system/bin/sh -c` 执行，
   * **不设置任何环境变量**（实测 `$PREFIX` 为空、`node` 不在 `PATH`）。所以 AI 直接写
   * `node ...codegraph.js` 必然失败。wrapper 把四件事一次封住：
   *
   * <ul>
   *   <li>`PATH` / `LD_LIBRARY_PATH`：让 node 及其动态库找得到
   *   <li>`CODEGRAPH_KERNEL=0`：跳过 glibc 链接的 Rust kernel，走 WASM 解析
   *   <li>`CODEGRAPH_TELEMETRY=0`：关遥测。无网设备上遥测会阻塞到超时——
   *       实测 init 从 14s 降到 4s、sync 从 12s 降到 2s
   *   <li>`--require` realpath 补丁：否则外部存储上的项目索引恒为 0 文件
   *       （见 {@link #realpathFixScript}）
   * </ul>
   *
   * <p>用 `exec` 而不是直接调用：省掉一层 shell 进程，且信号能正确传递。
   */
  fun wrapperScript(): String {
    val prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH
    val home = TermuxConstants.TERMUX_HOME_DIR_PATH
    return buildString {
      append("#!/system/bin/sh\n")
      append("# CodeGraph 入口。由 AndroidCodeStudio 生成，请勿手改——\n")
      append("# 重装或更新会覆盖它。\n")
      append("PREFIX=").append(prefix).append('\n')
      append("export PATH=\"\$PREFIX/bin:\$PATH\"\n")
      append("export LD_LIBRARY_PATH=\"\$PREFIX/lib\"\n")
      append("export HOME=").append(home).append('\n')
      append("export TMPDIR=\"\$PREFIX/tmp\"\n")
      append("# Rust kernel 依赖 glibc，Android 的 bionic 加载不了；关掉后走 WASM 解析。\n")
      append("export CODEGRAPH_KERNEL=0\n")
      append("# 遥测在无网设备上会阻塞到超时（实测拖慢 10 秒）。\n")
      append("export CODEGRAPH_TELEMETRY=0\n")
      // --require 而非 NODE_OPTIONS：NODE_OPTIONS 会影响 node 启动的所有子进程，
      // 且部分 node 版本对它的解析更严格（含空格/路径需转义）。--require 只作用于
      // 本次调用，语义精确。
      append("exec \"\$PREFIX/bin/node\" --require \"")
          .append(home)
          .append('/')
          .append(REALPATH_FIX_NAME)
          .append("\" \"")
          .append(home)
          .append('/')
          .append(INSTALL_DIR_NAME)
          .append('/')
          .append(PACKAGE_DIR_NAME)
          .append('/')
          .append(ENTRY_RELATIVE)
          .append("\" \"\$@\"\n")
    }
  }

  /** 写入 wrapper 并置可执行位。 */
  fun writeWrapper(): File {
    val file = wrapperFile()
    file.parentFile?.mkdirs()
    FileOutputStream(file).use { it.write(wrapperScript().toByteArray(Charsets.UTF_8)) }
    file.setExecutable(true, false)
    return file
  }

  /**
   * 解包 codegraph 的 tar.gz。
   *
   * <p><b>只保留必要部分</b>：包内的 `node`（116MB，glibc）与 `lib/kernel`（34MB，glibc）
   * 在 Android 上都无法加载，跳过它们能把磁盘占用从 284MB 降到 134MB。跳过而不是
   * 「解出来再删」是为了不浪费那 150MB 的写入与擦除。
   *
   * @param archive 下载好的 tar.gz
   * @param onProgress 已解出的条目数回调，用于界面显示（tar 无总条目数，无法给百分比）
   * @return 解出的条目数
   */
  fun extract(
      archive: File,
      onProgress: ((Int) -> Unit)? = null,
  ): Int {
    val target = installDir()
    // 解包前清掉旧目录：否则旧版本残留的文件会与新版本混在一起，
    // 出现「删过的文件还在」这类难查的问题。
    target.deleteRecursively()
    target.mkdirs()

    var count = 0
    TarArchiveInputStream(GZIPInputStream(FileInputStream(archive))).use { tar ->
      // 用 getNextTarEntry() 而不是 getNextEntry()：后者返回基类 ArchiveEntry，
      // 没有 mode（可执行位）信息，解出来的脚本不可执行。
      var entry = tar.nextTarEntry
      while (entry != null) {
        val name = entry.name
        if (shouldSkip(name)) {
          entry = tar.nextTarEntry
          continue
        }
        val outFile = File(target, name)
        if (!isInside(target, outFile)) {
          // 目录穿越防护：归档里的 ../ 能写到目标之外。下载的包虽来自可信源，
          // 但解包逻辑本身不该假设输入可信。
          entry = tar.nextTarEntry
          continue
        }
        if (entry.isDirectory) {
          outFile.mkdirs()
        } else {
          outFile.parentFile?.mkdirs()
          FileOutputStream(outFile).use { out -> tar.copyTo(out) }
          // tar 里记录了可执行位；不解出来的话 bin/ 下的脚本不可执行。
          if ((entry.mode and 0b001_000_000) != 0) {
            outFile.setExecutable(true, false)
          }
          count++
          onProgress?.invoke(count)
        }
        entry = tar.nextTarEntry
      }
    }
    return count
  }

  /** 该归档条目是否应跳过（glibc 二进制与包元数据）。 */
  private fun shouldSkip(name: String): Boolean {
    val normalized = name.removePrefix("./")
    // 自带 node：glibc 链接，Android 加载不了。用 Termux 的 node 代替。
    if (normalized == "$PACKAGE_DIR_NAME/node") {
      return true
    }
    // Rust kernel：同样是 glibc。去掉后自动降级 WASM。
    if (normalized.startsWith("$PACKAGE_DIR_NAME/lib/kernel/")) {
      return true
    }
    return false
  }

  /** 路径是否落在根目录内（防目录穿越）。 */
  private fun isInside(root: File, candidate: File): Boolean {
    return try {
      val rootPath = root.canonicalPath
      val candidatePath = candidate.canonicalPath
      candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
    } catch (e: java.io.IOException) {
      false
    }
  }

  /**
   * 把索引目录写进项目的 `.gitignore`。
   *
   * <p>索引是**派生数据**，不该进版本库：它体积不小、每次索引都在变，提交进去会让
   * diff 噪声很大且必然产生冲突。codegraph 自己不看 `.gitignore` 决定放哪，所以这步
   * 得我们做。
   *
   * <p>已有 `.gitignore` 时**追加**而不是覆盖；已经包含时不动（避免每次索引都改文件，
   * 那会让用户的 git 工作区一直显示「已修改」）。
   *
   * @return 是否改动了文件
   */
  fun ensureGitIgnore(projectDir: File): Boolean {
    val gitignore = File(projectDir, ".gitignore")
    val line = "$INDEX_DIR_NAME/"
    if (gitignore.isFile) {
      val existing =
          try {
            gitignore.readText(Charsets.UTF_8)
          } catch (e: java.io.IOException) {
            return false
          }
      // 逐行比对：`indexOf` 会误判注释行里的同名字符串。
      if (existing.lineSequence().any { it.trim() == line || it.trim() == INDEX_DIR_NAME }) {
        return false
      }
      val separator = if (existing.endsWith("\n") || existing.isEmpty()) "" else "\n"
      gitignore.appendText("$separator$line\n", Charsets.UTF_8)
      return true
    }
    gitignore.writeText("# CodeGraph 索引（派生数据，不入版本库）\n$line\n", Charsets.UTF_8)
    return true
  }

  /** 已建索引的项目数（供管理界面显示）。 */
  fun indexedProjects(projectsRoot: File): List<File> {
    val children = projectsRoot.listFiles() ?: return emptyList()
    return children.filter { it.isDirectory && File(it, INDEX_DIR_NAME).isDirectory }
  }

  /** 删除程序目录。索引在各项目里，不在此列——索引由 [deleteIndex] 单独删。 */
  fun uninstall() {
    installDir().deleteRecursively()
    wrapperFile().delete()
  }

  /** 删除某个项目的索引。 */
  fun deleteIndex(projectDir: File): Boolean = File(projectDir, INDEX_DIR_NAME).deleteRecursively()

  /** 程序目录占用的字节数（供界面显示）。 */
  fun installedSizeBytes(): Long = installDir().walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}

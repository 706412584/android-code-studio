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
   */
  fun isInstalled(): Boolean = entryScript().isFile && nodeBinary().isFile && wrapperFile().isFile

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
      append("exec \"\$PREFIX/bin/node\" \"")
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

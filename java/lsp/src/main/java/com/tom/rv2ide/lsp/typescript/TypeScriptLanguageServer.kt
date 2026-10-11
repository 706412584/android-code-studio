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

package com.tom.rv2ide.lsp.typescript

import com.tom.rv2ide.lsp.common.ProcessLanguageServer
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.nio.file.Path

/**
 * TypeScript/JavaScript 语言服务器（React Native 模板的主用服务器）。
 *
 * <p>基于 `typescript-language-server`（TSLS）——它是 tsserver 的 LSP 包装，
 * 补全/诊断/跳转/格式化全部由 TypeScript 官方编译器提供，与 VSCode 的 TS 支持同源。
 *
 * <h3>启动链路（已在本机设备实测）</h3>
 *
 * ```
 * /data/data/com.tom.rv2ide/files/usr/bin/node   ← Termux 的 node（v24.7.0）
 *   <proj>/node_modules/typescript-language-server/lib/cli.mjs --stdio
 * ```
 *
 * <p>注意 cli.mjs 是 ES module，**不能直接 spawn 它**（实测 ENOENT / 无 shebang
 * 执行权），必须经 node 解释执行。npm 生成的 `.bin/typescript-language-server`
 * 是 shell 包装脚本，依赖 PATH 上的 node——设备端 Gradle/AI 环境不保证 PATH，
 * 直接走「node + cli.mjs」最稳。
 *
 * <h3>依赖从哪来</h3>
 *
 * 查找顺序（第一个存在的即用）：
 * 1. 项目本地 `<项目根>/node_modules/typescript-language-server`——RN 模板的
 *    package.json 已带 devDependencies，`npm install` 后即有，且**版本与项目
 *    锁定一致**（TSLS 会用工作区的 typescript 包，行为与用户 CI 环境相同）。
 * 2. 全局 `$PREFIX/lib/node_modules/typescript-language-server`——用户手动
 *    `npm install -g` 的情况。
 * 3. 都找不到 → [unavailableMessage] 给出可执行的安装命令（npmmirror）。
 *
 * <p>找不到时**不崩溃、不静默**：弹一次可读错误（含安装命令），编辑器保持纯文本
 * 模式工作。装好后重开项目即生效（进程随项目生命周期重建）。
 */
class TypeScriptLanguageServer : ProcessLanguageServer() {

  companion object {

    const val SERVER_ID = "typescript"

    /** TSLS 的入口脚本相对包目录的路径。6.x 与 5.x 一致。 */
    private const val CLI_RELATIVE_PATH = "lib/cli.mjs"

    /** TSLS 包名（项目本地 node_modules 与全局都按它查找）。 */
    private const val PACKAGE_NAME = "typescript-language-server"

    /**
     * 未安装时的用户提示。
     *
     * <p>写清三件事：装什么、在哪装（项目根）、用什么源（国内设备直连 npm 官方源
     * 常超时，npmmirror 是项目内已验证的镜像）。
     */
    const val INSTALL_HINT =
        "TypeScript 语言服务器未找到。请在项目根目录执行：\n" +
            "npm install -D typescript-language-server typescript " +
            "--registry=https://registry.npmmirror.com\n" +
            "（React Native 模板的 package.json 已包含这两个依赖，npm install 即可）"
  }

  override val serverId: String = SERVER_ID

  /** RN 项目常见扩展名全覆盖。mjs/cjs 是 Node ESM/CJS 的显式扩展。 */
  override val fileExtensions: Set<String> =
      setOf("js", "jsx", "ts", "tsx", "mjs", "cjs")

  override val unavailableMessage: String = INSTALL_HINT

  /**
   * languageId 按扩展名区分：TSLS 依据它决定 JSX 语义与编译选项。
   * 全用 "typescript" 会让 .jsx/.tsx 被当作非 JSX 处理，`<div>` 直接报语法错误。
   */
  override fun languageIdFor(file: Path): String {
    return when (file.fileName?.toString()?.substringAfterLast('.', "")?.lowercase()) {
      "js" -> "javascript"
      "jsx" -> "javascriptreact"
      "tsx" -> "typescriptreact"
      else -> "typescript"
    }
  }

  /**
   * 定位 TSLS 入口脚本。
   *
   * <p>从触发文件向上逐级找 node_modules——文件在子目录（app/src/main/...）时，
   * 只查项目根会漏掉 monorepo 中包级安装的场景。
   */
  private fun findCliScript(fallbackFile: Path?): File? {
    val candidates = mutableListOf<File>()

    // 1) 从触发文件向上逐级查找（覆盖项目本地与包级安装）
    var dir: File? = fallbackFile?.parent?.toFile() ?: projectRootDirOrNull()
    var depth = 0
    while (dir != null && depth < 8) {
      candidates.add(File(dir, "node_modules/$PACKAGE_NAME/$CLI_RELATIVE_PATH"))
      dir = dir.parentFile
      depth++
    }

    // 2) 全局安装（Termux 前缀）
    val prefix = Environment.PREFIX
    if (prefix != null) {
      candidates.add(
          File(prefix, "lib/node_modules/$PACKAGE_NAME/$CLI_RELATIVE_PATH"))
    }

    return candidates.firstOrNull { it.isFile }
  }

  /** Termux 的 node。缺失时回退 PATH 上的 node（CI/桌面环境）。 */
  private fun findNodeExecutable(): String {
    val prefix = Environment.PREFIX
    if (prefix != null) {
      val termuxNode = File(prefix, "bin/node")
      if (termuxNode.isFile) {
        return termuxNode.absolutePath
      }
    }
    return "node"
  }

  override fun createCommand(fallbackFile: Path?): List<String>? {
    val cli = findCliScript(fallbackFile) ?: return null
    // --log-level 1（error）：TSLS 默认 info 级会把大量 tsconfig 探测信息刷进
    // stderr，而 stderr 会进 logcat——设备上过滤日志时噪声太大。
    return listOf(findNodeExecutable(), cli.absolutePath, "--stdio", "--log-level", "1")
  }

  /**
   * 工作目录取项目根。
   *
   * <p>TSLS 用 cwd 与 rootUri 定位 tsconfig.json；指向触发文件的目录会让它把
   * 子目录当工作区根，`paths` 别名与 `include` 全部解析不到。projectRoot 未初始化
   * （未触发 ProjectInitializedEvent）时退回触发文件所在目录——比 HOME 更可能命中
   * 正确的 tsconfig。
   */
  override fun workingDirectory(fallbackFile: Path?): File {
    return projectRootDirOrNull() ?: fallbackFile?.parent?.toFile() ?: Environment.HOME
  }
}

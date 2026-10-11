/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.lsp.java.providers

import com.tom.rv2ide.lsp.java.JavaCompilerProvider
import com.tom.rv2ide.lsp.java.parser.Parser
import com.tom.rv2ide.lsp.java.visitors.FindSymbolsByName
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.util.StringSearch
import com.tom.rv2ide.utils.SourceClassTrie
import java.nio.file.Path
import openjdk.source.tree.ClassTree
import openjdk.source.tree.MethodTree
import openjdk.source.tree.Tree
import openjdk.source.tree.VariableTree
import openjdk.source.util.TreePath
import org.slf4j.LoggerFactory

/**
 * 工作区符号搜索：跨全部模块按名查找 Java 声明（类/接口/枚举/注解/方法/字段）。
 *
 * <p>两阶段：
 *  A) **精确阶段**——走 `ModuleProject.compileJavaSourceClasses`（活索引）+ `JavaCompilerProvider`
 *     的 FQN 匹配，零扫盘，直接定位到目标文件；
 *  B) **扫描阶段**——对候选文件（`StringSearch` Boyer-Moore 粗筛）逐文件 parse + visitor，
 *     覆盖成员、非 public 的次要顶层类、嵌套类型，以及索引未命中的情况。
 *
 * <p>搜索范围是当前工作区全部模块的 Java 源码根（不含 classpath/jar、不含 build 产物）。
 *
 * <p><b>必须单线程调用</b>：`Parser` 是静态单条缓存、`StringSearch` 用静态直连缓冲，
 * 都无同步（既有设计）。本类通过 GatedAnalyzer 的专属线程串行执行；另外每次 parse
 * 后校验 `parser.file` 与目标文件一致，不一致则跳过该文件——宁可少一条，不给错位置。
 *
 * <p>有 deadline：每处理完一个文件检查一次，超预算返回已找到的部分并标记截断
 * （比被外层一刀切成 0 结果对模型更有用）。
 */
class WorkspaceSymbolProvider(private val workspace: IWorkspace) {

  /** 本地种类枚举，与 `WorkspaceSymbolPort.Kind` 一一对应（本模块不依赖 ai-tool 端口）。 */
  enum class Kind {
    CLASS,
    INTERFACE,
    ENUM,
    ANNOTATION,
    METHOD,
    FIELD
  }

  /** 一条命中（行列 0 基）。 */
  data class Match(
      val name: String,
      val owner: String,
      val kind: Kind,
      val file: Path,
      val line: Int,
      val column: Int,
  )

  /**
   * 执行一次搜索。
   *
   * @param query 符号名；简单名（MainActivity）或全限定名（com.foo.MainActivity）均可
   * @param kinds 种类过滤；null 或空集合表示不过滤
   * @param limit 结果条数上限（>0）
   * @param deadlineNanos 单调时钟截止点（`System.nanoTime()` 基准）
   * @return (matches, truncatedReason)；truncatedReason 为空串表示完整扫描完成
   */
  fun search(
      query: String,
      kinds: Set<Kind>?,
      limit: Int,
      deadlineNanos: Long,
  ): Pair<List<Match>, String> {
    val text = query.trim()
    if (text.isEmpty() || limit <= 0) {
      return emptyList<Match>() to ""
    }
    val effective = if (kinds.isNullOrEmpty()) ALL_KINDS else kinds
    val simpleName = text.substringAfterLast('.')
    // 尾随点（"com.foo."）不是合法的 FQN：按简单名处理会得到空名，直接判为无结果。
    if (simpleName.isEmpty()) {
      return emptyList<Match>() to ""
    }
    val dotted = text.contains('.')
    val modules = modules()

    val hits = ArrayList<Hit>()
    val seen = HashSet<String>()
    var truncated = ""

    // ---- 阶段 A：精确索引（零扫盘）----
    if (effective.any { it in TYPE_KINDS }) {
      for (module in modules) {
        if (System.nanoTime() > deadlineNanos) {
          truncated = TIME_BUDGET_REASON
          break
        }
        for (file in exactFiles(module, text, simpleName, dotted)) {
          collect(file, text, simpleName, dotted, effective, hits, seen, true, limit)
          if (hits.size >= limit) {
            truncated = limitReason(limit)
            break
          }
        }
        if (truncated.isNotEmpty()) {
          break
        }
      }
    }

    // ---- 阶段 B：粗筛 + parse 扫描 ----
    if (truncated.isEmpty()) {
      for (file in candidateFiles(modules)) {
        if (System.nanoTime() > deadlineNanos) {
          truncated = TIME_BUDGET_REASON
          break
        }
        collect(file, text, simpleName, dotted, effective, hits, seen, false, limit)
        if (hits.size >= limit) {
          truncated = limitReason(limit)
          break
        }
      }
    }

    hits.sortWith(HIT_ORDER)
    return hits.take(limit).map { it.match } to truncated
  }

  // ---------------------------------------------------------------------------------------------
  // 阶段 A：精确索引
  // ---------------------------------------------------------------------------------------------

  /**
   * 精确阶段的候选文件。
   *
   * <p>简单名：`findQualifiedNames` 给出 FQN 列表（含 classpath/bootclasspath），但只接受能在
   * 模块源码索引里找到节点的那些——jar 里的类在这里天然落空，不污染「工作区符号」的语义，
   * 也避免 `findTypeDeclaration` 对 classpath 类走昂贵的「按包扫源码目录」慢路径。
   *
   * <p>全限定名：直接查 trie；嵌套类的 FQN（三段以上）不在 trie 里，落到扫描阶段按名覆盖。
   */
  private fun exactFiles(
      module: ModuleProject,
      query: String,
      simpleName: String,
      dotted: Boolean,
  ): List<Path> {
    return try {
      if (dotted) {
        val node = module.compileJavaSourceClasses.findNode(query)
        val source = node as? SourceClassTrie.SourceNode
        if (source != null && source.isClass) listOf(source.file) else emptyList()
      } else {
        val compiler = JavaCompilerProvider.get(module)
        val result = LinkedHashSet<Path>()
        for (fqn in compiler.findQualifiedNames(simpleName)) {
          val node = module.compileJavaSourceClasses.findNode(fqn) as? SourceClassTrie.SourceNode
          if (node != null && node.isClass) {
            result.add(node.file)
          }
        }
        result.toList()
      }
    } catch (t: Throwable) {
      log.debug("模块 {} 精确索引阶段失败：{}", module.path, t.message)
      emptyList()
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 阶段 B：候选集
  // ---------------------------------------------------------------------------------------------

  /**
   * 扫描阶段的候选文件 = 各模块源码索引的并集（跨模块去重）。
   *
   * <p>`getCompileSourceDirectories` 是传递闭包（含依赖模块），同一文件会在多个模块的索引里
   * 出现，必须去重，否则同一处声明会被 parse 多次。
   */
  private fun candidateFiles(modules: List<ModuleProject>): List<Path> {
    val seen = HashSet<Path>()
    val result = ArrayList<Path>()
    for (module in modules) {
      for (source in module.compileJavaSourceClasses.allSources()) {
        if (seen.add(source.file)) {
          result.add(source.file)
        }
      }
    }
    return result
  }

  // ---------------------------------------------------------------------------------------------
  // 单文件：粗筛 + parse + 收集
  // ---------------------------------------------------------------------------------------------

  private fun collect(
      file: Path,
      query: String,
      simpleName: String,
      dotted: Boolean,
      kinds: Set<Kind>,
      hits: MutableList<Hit>,
      seen: MutableSet<String>,
      exact: Boolean,
      limit: Int,
  ) {
    if (hits.size >= limit) {
      return
    }
    // 粗筛：Boyer-Moore 按词边界匹配（大小写敏感），把绝大多数无关文件挡在 parse 之外。
    // 全限定名查询也用简单名粗筛——源码里几乎不会字面出现 "com.foo.Outer.Inner"，
    // 精确性由 parse 后的 FQN 全等校验保证。
    val contains =
        try {
          StringSearch.containsWord(file, simpleName)
        } catch (t: Throwable) {
          log.debug("跳过粗筛失败的文件 {}：{}", file, t.message)
          return
        }
    if (!contains) {
      return
    }

    val parser =
        try {
          Parser.parseFile(file)
        } catch (t: Throwable) {
          log.debug("跳过无法解析的文件 {}：{}", file, t.message)
          return
        }
    // 静态单条缓存防御：并发或缓存顶替时拿回的可能不是请求的文件，宁可少一条也不给错位置。
    if (!isRequestedFile(parser, file)) {
      log.debug("Parser 缓存与请求文件不一致，跳过 {}", file)
      return
    }

    val found = ArrayList<TreePath>()
    try {
      FindSymbolsByName(
              simpleName, kinds.any { it in TYPE_KINDS }, Kind.METHOD in kinds, Kind.FIELD in kinds)
          .scan(parser.root, found)
    } catch (t: Throwable) {
      // 单个文件的 AST 遍历失败（畸形 AST 等）只跳过该文件——不能让它掀翻整次搜索，
      // 已收集的其他文件结果全部丢失（与「宁可少一条」的设计意图相悖）。
      log.debug("AST 遍历失败，跳过 {}：{}", file, t.message)
      return
    }
    if (found.isEmpty()) {
      return
    }

    for (path in found) {
      if (hits.size >= limit) {
        return
      }
      val leaf = path.leaf
      val kind = kindOf(leaf) ?: continue
      val name = nameOf(leaf, path)
      if (name.isEmpty()) {
        continue
      }
      // 展示用的 owner：类型自身为空串，成员给所在类型 FQN。
      // FQN 校验用的 enclosing：类型也要带上外层链（嵌套类的全名是 pkg.Outer.Inner）。
      val enclosing = enclosingTypeFqn(path)
      val owner = if (leaf is ClassTree) "" else enclosing
      // 构造器的「全名」就是所属类的 FQN（其声明名是 <init>，不是类的简单名）。
      val isConstructor = leaf is MethodTree && leaf.name.toString() == CONSTRUCTOR_NAME
      val qualified = if (isConstructor) enclosing else joinTypeFqn(enclosing, name)
      if (dotted && qualified != query) {
        continue
      }
      // 位置换算后**立刻**转成 Match，不跨文件持有 TreePath/Tree（见类注释的缓存防御）。
      val line: Int
      val column: Int
      try {
        val range = Parser.range(parser.task, parser.contents, path)
        line = range.start.line
        column = range.start.column
      } catch (t: Throwable) {
        log.debug("定位 {} 中 {} 的位置失败：{}", file, name, t.message)
        continue
      }
      if (line < 0 || column < 0) {
        // Range.NONE：标识符定位失败。宁可少一条，不给错位置。
        continue
      }
      val match = Match(name, owner, kind, file, line, column)
      if (seen.add(dedupeKey(match))) {
        hits.add(Hit(match, exact))
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Tree 辅助
  // ---------------------------------------------------------------------------------------------

  private fun kindOf(leaf: Tree): Kind? =
      when (leaf) {
        is ClassTree ->
            when (leaf.kind) {
              Tree.Kind.INTERFACE -> Kind.INTERFACE
              Tree.Kind.ENUM -> Kind.ENUM
              Tree.Kind.ANNOTATION_TYPE -> Kind.ANNOTATION
              else -> Kind.CLASS
            }
        is MethodTree -> Kind.METHOD
        is VariableTree -> Kind.FIELD
        else -> null
      }

  /** 声明的简单名；匿名类/匿名类构造器返回空串（调用方跳过）。 */
  private fun nameOf(leaf: Tree, path: TreePath): String =
      when (leaf) {
        is ClassTree -> leaf.simpleName.toString()
        is MethodTree -> {
          val raw = leaf.name.toString()
          if (raw == CONSTRUCTOR_NAME) nearestClassName(path) else raw
        }
        is VariableTree -> leaf.name.toString()
        else -> ""
      }

  /** 最近的所在类型简单名；匿名类（simpleName 为空）返回空串。 */
  private fun nearestClassName(path: TreePath): String {
    var current: TreePath? = path
    while (current != null) {
      val leaf = current.leaf
      if (leaf is ClassTree) {
        return leaf.simpleName.toString()
      }
      current = current.parentPath
    }
    return ""
  }

  /** 所在类型的全限定名（包 + 外层类链）；顶层无包时退化为简单名。 */
  private fun enclosingTypeFqn(path: TreePath): String {
    val names = ArrayList<String>()
    var current: TreePath? = path.parentPath
    while (current != null) {
      val leaf = current.leaf
      if (leaf is ClassTree) {
        val name = leaf.simpleName.toString()
        if (name.isNotEmpty()) {
          names.add(name)
        }
      }
      current = current.parentPath
    }
    names.reverse()
    val pkg = path.compilationUnit.packageName?.toString().orEmpty()
    val joined = names.joinToString(".")
    return when {
      pkg.isEmpty() -> joined
      joined.isEmpty() -> pkg
      else -> "$pkg.$joined"
    }
  }

  private fun joinTypeFqn(prefix: String, name: String): String =
      if (prefix.isEmpty()) name else "$prefix.$name"

  private fun isRequestedFile(parser: Parser, file: Path): Boolean {
    return try {
      parser.file.toUri() == file.toAbsolutePath().toUri()
    } catch (t: Throwable) {
      false
    }
  }

  private fun dedupeKey(match: Match): String =
      match.file.toString() + '\u0000' + match.line + ':' + match.column + ':' + match.name

  // ---------------------------------------------------------------------------------------------
  // 模块枚举
  // ---------------------------------------------------------------------------------------------

  /** 工作区的模块项目（跳过根项目，与 `JavaLanguageServer.setupWorkspace` 的枚举口径一致）。 */
  private fun modules(): List<ModuleProject> {
    val rootPath =
        try {
          workspace.getRootProject().path
        } catch (t: Throwable) {
          null
        }
    return workspace.getSubProjects()
        .filterIsInstance<ModuleProject>()
        .filter { it.path != rootPath }
  }

  private class Hit(val match: Match, val exact: Boolean)

  companion object {
    private val log = LoggerFactory.getLogger(WorkspaceSymbolProvider::class.java)

    private const val CONSTRUCTOR_NAME = "<init>"
    private const val TIME_BUDGET_REASON = "达到时间预算，结果可能不完整"
    private const val TYPE_RANK = 0
    private const val MEMBER_RANK = 1

    private val TYPE_KINDS = setOf(Kind.CLASS, Kind.INTERFACE, Kind.ENUM, Kind.ANNOTATION)
    private val ALL_KINDS: Set<Kind> = Kind.values().toSet()

    /** 排序：精确命中在前；类型优先于成员；再按 文件、行、列、名字 升序。 */
    private val HIT_ORDER: Comparator<Hit> =
        compareByDescending<Hit> { it.exact }
            .thenBy { if (it.match.kind in TYPE_KINDS) TYPE_RANK else MEMBER_RANK }
            .thenBy { it.match.file.toString() }
            .thenBy { it.match.line }
            .thenBy { it.match.column }
            .thenBy { it.match.name }

    private fun limitReason(limit: Int): String = "已达 $limit 条上限，可能还有更多"
  }
}

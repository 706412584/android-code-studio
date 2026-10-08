/*
 * This file is part of AndroidCodeStudio.
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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent

/**
 * 一行在 diff 结果里扮演的角色。
 *
 * <p>只有三种：这是行级 diff 的固有结论，不存在「修改」这种类型——「改了一行」在行级
 * 看来就是一行删除 + 一行新增，只不过随后会被[AssistantDiffBuilder]配对做行内高亮。
 */
enum class DiffLineType {
  EQUAL,
  INSERT,
  DELETE,
}

/**
 * 行内的一段文本，`changed` 表示这段是否属于本次改动的部分。
 *
 * <p>把行切成若干段（而不是只给一个「整行变了」的布尔）是为了让渲染层能只给变化的那几个
 * token 上色：`userId` → `accountId` 这种改名，用户想看到的是那一个词的差异，
 * 而不是整行刷红。
 */
data class DiffSegment(val text: String, val changed: Boolean)

/**
 * diff 结果里的一行。
 *
 * <p>行号用可空类型，是因为新增行没有旧行号、删除行没有新行号。这里刻意**不**用 0 或 -1
 * 表示「无」：行号是 1-based，用 0 当哨兵意味着渲染层要记住「0 是特殊值」，
 * 漏判一次就会在行号栏显示一个 "0"。
 *
 * @param oldNo 旧文件行号（1-based），无则 null
 * @param newNo 新文件行号（1-based），无则 null
 * @param segments 行内词级分段；仅当该行与对侧行配对成功时才非空
 */
data class DiffLine(
    val oldNo: Int?,
    val newNo: Int?,
    val type: DiffLineType,
    val text: String,
    val segments: List<DiffSegment> = emptyList(),
)

/**
 * 一次 diff 的完整结果。
 *
 * @param added 新增行数
 * @param removed 删除行数
 * @param truncated 是否因输入过大而走了降级路径。降级时对齐是「全删 + 全增」，
 *   数字仍准确，但行内高亮与配对信息丢失——渲染层可据此决定是否提示用户
 *   「改动过大，仅展示概要」。
 */
data class DiffResult(
    val lines: List<DiffLine>,
    val added: Int,
    val removed: Int,
    val truncated: Boolean,
)

/**
 * 纯 JVM 的行级 diff。
 *
 * <p><b>为什么不复用仓库里已有的 diff</b>：{@code com.tom.rv2ide.diffutils.DiffUtils}
 * （在 {@code composite-builds/build-deps/fuzzysearch/}）是**字符级** Levenshtein，
 * 它按 {@code new int[len2 * len1]} 一次性开表。对一个 3000 行的文件，字符数可达 9e4，
 * 于是要分配 9e4 × 9e4 的 int 矩阵——先 OOM，若侥幸没 OOM 也会因 {@code len2 * len1}
 * 的 int 乘法溢出而抛 {@code NegativeArraySizeException}。因此本类完全自包含，
 * 绝不调用它，也不为它引入任何 jar 依赖。
 *
 * <p><b>为什么要在客户端算</b>：数据层（{@code DiffRecord}）只存了改动前后的完整内容快照，
 * 没有存 diff 结果。快照是最省事也最可靠的形式（回滚需要精确的原文），代价就是 diff
 * 得在展示时现算——所以这个类必须是纯函数、无 Android 依赖，才能在 JVM 上单测。
 *
 * <p><b>规模守卫是硬要求</b>：LCS 的 DP 表是 O(行数 × 行数)，没有上限的话
 * 「打开一个 AI 刚生成的大文件」就会把手机搞崩。见 {@link #MAX_CELLS}。
 */
object AssistantDiffBuilder {

  /**
   * 单侧行数上限。
   *
   * <p>取 20000：正常源码文件远达不到（本仓库最大的自研文件也就几千行），
   * 超过这个量级基本是生成物或数据文件，此时「精确对齐」对用户已无意义——
   * 他要的是「这个文件被大改了」，而不是逐行对照。
   */
  const val MAX_LINES = 20_000

  /**
   * DP 表单元数上限（行数 × 行数）。
   *
   * <p>取 4_000_000（约 2000×2000）。DP 表用 {@code IntArray}，一个单元 4 字节，
   * 4e6 单元 ≈ 16MB 的**单次**分配。再往上加，低端机上这次分配本身就可能触发 OOM，
   * 而 2000 行以上的文件做逐行对齐的收益已经很低。
   *
   * <p>比较时必须先转 {@code Long}：两个 20000 的 int 相乘会溢出成负数，
   * 溢出后守卫失效，等于没守——这正是上面提到的 DiffUtils 踩过的坑。
   */
  const val MAX_CELLS = 4_000_000L

  /**
   * 行内 token 级 DP 的单元数上限。
   *
   * <p>「行内比较规模安全」只在正常代码行上成立。压缩过的 JS/CSS 会把几万字符塞进一行，
   * 这时 token 数同样能上千，两条这样的行做 LCS 又是 O(n×m)。所以行内也要设守卫，
   * 超限时放弃高亮（该行仍会显示，只是不做词级标注）。
   */
  const val MAX_TOKEN_CELLS = 250_000L

  /**
   * 计算 [oldContent] → [newContent] 的行级 diff。
   *
   * @param oldContent 改动前内容
   * @param newContent 改动后内容
   * @param oldExists 改动前文件是否存在。为 false 时表示这是一次新建，
   *   [oldContent] 会被忽略——否则「新建文件」会被渲染成一大堆删除
   */
  @JvmStatic
  @JvmOverloads
  fun build(oldContent: String, newContent: String, oldExists: Boolean = true): DiffResult {
    val oldLines = if (oldExists) splitLines(normalize(oldContent)) else emptyList()
    val newLines = splitLines(normalize(newContent))

    if (oldLines.size > MAX_LINES || newLines.size > MAX_LINES) {
      return degraded(oldLines, newLines)
    }
    // 先转 Long 再乘：int × int 溢出成负数会让守卫形同虚设。
    if (oldLines.size.toLong() * newLines.size.toLong() > MAX_CELLS) {
      return degraded(oldLines, newLines)
    }

    val ops = align(oldLines, newLines)
    val lines = ArrayList<DiffLine>(ops.size)
    var added = 0
    var removed = 0

    var k = 0
    while (k < ops.size) {
      val op = ops[k]
      if (op.type == DiffLineType.EQUAL) {
        lines.add(
            DiffLine(op.oldIndex + 1, op.newIndex + 1, DiffLineType.EQUAL, oldLines[op.oldIndex]))
        k++
        continue
      }

      // 把一段连续的 INSERT/DELETE 收集成一个「改动块」。LCS 的回溯可能让删除与新增
      // 交错出现（D I D I），但用户心里的一次改动就是「这几行换成了那几行」，
      // 所以先把块内的删除、新增各自归拢，再按位置配对。
      val deletes = ArrayList<Int>()
      val inserts = ArrayList<Int>()
      while (k < ops.size && ops[k].type != DiffLineType.EQUAL) {
        if (ops[k].type == DiffLineType.DELETE) {
          deletes.add(ops[k].oldIndex)
        } else {
          inserts.add(ops[k].newIndex)
        }
        k++
      }

      // 配对只到较短的一侧为止：多出来的删除/新增就是纯粹的增删，没有对侧可比较。
      val pairs = minOf(deletes.size, inserts.size)
      val delSegs = arrayOfNulls<List<DiffSegment>>(deletes.size)
      val insSegs = arrayOfNulls<List<DiffSegment>>(inserts.size)
      for (p in 0 until pairs) {
        val paired = diffLine(oldLines[deletes[p]], newLines[inserts[p]])
        delSegs[p] = paired.first
        insSegs[p] = paired.second
      }

      // 先输出删除再输出新增：与常见 diff 的「先减后加」一致，渲染层不必为交错顺序特判。
      for (p in deletes.indices) {
        val oi = deletes[p]
        lines.add(
            DiffLine(
                oi + 1, null, DiffLineType.DELETE, oldLines[oi], delSegs[p] ?: emptyList()))
        removed++
      }
      for (p in inserts.indices) {
        val ni = inserts[p]
        lines.add(
            DiffLine(
                null, ni + 1, DiffLineType.INSERT, newLines[ni], insSegs[p] ?: emptyList()))
        added++
      }
    }

    return DiffResult(lines, added, removed, truncated = false)
  }

  // ---- 对齐 ----

  /** 对齐结果里的一步。[oldIndex]/[newIndex] 只在对应类型下有意义。 */
  private class Op(val type: DiffLineType, val oldIndex: Int, val newIndex: Int)

  /**
   * 对两个序列做 LCS 对齐，产出 EQUAL/INSERT/DELETE 序列。
   *
   * <p>泛型是为了让行对齐（输入是行列表）与行内对齐（输入是 token 列表）共用同一份实现——
   * 两者的算法完全相同，各写一遍只会让「回溯的 tie-break 规则」这种细节在两处漂移。
   */
  private fun <T> align(old: List<T>, new: List<T>): List<Op> {
    val n = old.size
    val m = new.size
    if (n == 0) {
      return List(m) { Op(DiffLineType.INSERT, 0, it) }
    }
    if (m == 0) {
      return List(n) { Op(DiffLineType.DELETE, it, 0) }
    }

    val w = m + 1
    // dp[i][j] = old[i..] 与 new[j..] 的最长公共子序列长度。从右下往左上填，
    // 这样回溯时可以只向右下推进，不需要保存完整方向表。
    val dp = IntArray((n + 1) * w)
    for (i in n - 1 downTo 0) {
      val row = i * w
      val next = row + w
      val oldLine = old[i]
      for (j in m - 1 downTo 0) {
        dp[row + j] =
            if (oldLine == new[j]) {
              dp[next + j + 1] + 1
            } else {
              maxOf(dp[next + j], dp[row + j + 1])
            }
      }
    }

    val ops = ArrayList<Op>(n + m)
    var i = 0
    var j = 0
    while (i < n && j < m) {
      if (old[i] == new[j]) {
        ops.add(Op(DiffLineType.EQUAL, i, j))
        i++
        j++
      } else if (dp[(i + 1) * w + j] >= dp[i * w + j + 1]) {
        // 平局时优先删除：让「删除」尽量排在「新增」前面，配对逻辑（先归拢删除再归拢新增）
        // 得到的块更接近用户直觉。反过来选会让同一次改动的删除与新增被 EQUAL 拆开。
        ops.add(Op(DiffLineType.DELETE, i, 0))
        i++
      } else {
        ops.add(Op(DiffLineType.INSERT, 0, j))
        j++
      }
    }
    while (i < n) {
      ops.add(Op(DiffLineType.DELETE, i, 0))
      i++
    }
    while (j < m) {
      ops.add(Op(DiffLineType.INSERT, 0, j))
      j++
    }
    return ops
  }

  /**
   * 降级：不做精细对齐，旧内容全删、新内容全增。
   *
   * <p>行号与增删计数仍然准确——用户至少能知道「改了多少行」；丢掉的只是配对与行内高亮，
   * 而这两样在超大文件上本来也读不过来。
   */
  private fun degraded(old: List<String>, new: List<String>): DiffResult {
    val lines = ArrayList<DiffLine>(old.size + new.size)
    for (i in old.indices) {
      lines.add(DiffLine(i + 1, null, DiffLineType.DELETE, old[i]))
    }
    for (j in new.indices) {
      lines.add(DiffLine(null, j + 1, DiffLineType.INSERT, new[j]))
    }
    return DiffResult(lines, added = new.size, removed = old.size, truncated = true)
  }

  // ---- 行内词级 diff ----

  /**
   * 比较一对配对行，产出各自的词级分段。
   *
   * <p>返回 Pair 而非两个方法：两次调用会重复跑一遍 token 对齐，而配对行可能很多。
   */
  private fun diffLine(
      oldLine: String,
      newLine: String
  ): Pair<List<DiffSegment>, List<DiffSegment>> {
    val a = tokenize(oldLine)
    val b = tokenize(newLine)
    if (a.size.toLong() * b.size.toLong() > MAX_TOKEN_CELLS) {
      // 超长行：放弃高亮。返回空表而不是「整行 changed」——空表让渲染层走普通文本路径，
      // 而「整行 changed」会诱使渲染层去切分一个几万字符的字符串，得不偿失。
      return Pair(emptyList(), emptyList())
    }

    val ops = align(a, b)
    val del = ArrayList<DiffSegment>()
    val ins = ArrayList<DiffSegment>()
    for (op in ops) {
      when (op.type) {
        DiffLineType.EQUAL -> {
          appendSegment(del, a[op.oldIndex], changed = false)
          appendSegment(ins, b[op.newIndex], changed = false)
        }
        DiffLineType.DELETE -> appendSegment(del, a[op.oldIndex], changed = true)
        DiffLineType.INSERT -> appendSegment(ins, b[op.newIndex], changed = true)
      }
    }
    return Pair(del, ins)
  }

  /**
   * 追加一段，并把与上一段 `changed` 相同的合并。
   *
   * <p>不合并的话 `val userId = 1` 会产出 7 个分段（每个 token 一段），渲染层要为每个分段
   * 建一个 span——而其中 6 段其实是一回事。合并后正常只剩 2~3 段。
   */
  private fun appendSegment(list: MutableList<DiffSegment>, text: String, changed: Boolean) {
    if (text.isEmpty()) {
      return
    }
    val last = list.lastOrNull()
    if (last != null && last.changed == changed) {
      list[list.size - 1] = DiffSegment(last.text + text, changed)
    } else {
      list.add(DiffSegment(text, changed))
    }
  }

  /**
   * 把一行切成「标识符 / 空白 / 标点」三类 token。
   *
   * <p>按这三类边界切，而不是按字符切：`val userId = 1` 会切成
   * `val` ` ` `userId` ` ` `=` ` ` `1`，于是 `userId`→`accountId` 只会高亮这两个 token，
   * 而不是把整行标成改动。按字符切的话 `user` 与 `account` 的公共前缀会被判为相同，
   * 高亮出 `Id`→`accountId` 这种没有意义的残片。
   *
   * <p>标点单独成 token（不合并连续标点）：`==` 与 `=` 是两回事，合并了就看不出差异。
   */
  private fun tokenize(line: String): List<String> {
    val out = ArrayList<String>()
    var i = 0
    while (i < line.length) {
      val c = line[i]
      // 代理对（emoji、CJK 扩展区汉字）在 UTF-16 里占两个 char，必须整体作为一个 token，
      // 否则会被切成两个「标点」，渲染时变成两个乱码方块。
      if (Character.isHighSurrogate(c) && i + 1 < line.length &&
          Character.isLowSurrogate(line[i + 1])) {
        out.add(line.substring(i, i + 2))
        i += 2
        continue
      }
      val cat = category(c)
      if (cat == CAT_PUNCT) {
        out.add(c.toString())
        i++
      } else {
        var j = i + 1
        while (j < line.length && category(line[j]) == cat) {
          j++
        }
        out.add(line.substring(i, j))
        i = j
      }
    }
    return out
  }

  private const val CAT_SPACE = 1
  private const val CAT_WORD = 2
  private const val CAT_PUNCT = 3

  private fun category(c: Char): Int =
      when {
        c.isWhitespace() -> CAT_SPACE
        // `$` 单独列出：Kotlin/Java 的字符串模板与内部类名都靠它，且它不是 letter/digit。
        Character.isLetterOrDigit(c) || c == '_' || c == '$' -> CAT_WORD
        else -> CAT_PUNCT
      }

  // ---- 输入归一 ----

  /**
   * 统一换行并去掉 BOM。
   *
   * <p><b>不做这两步的后果是整文件刷红</b>：CRLF 文件按 `\n` 拆行后，每行末尾都残留一个
   * `\r`，于是每一行都与对侧「不相等」；带 BOM 的文件首行会多一个 `\uFEFF`，
   * 首行永远显示为改动。这两种情况用户会以为是 AI 把整个文件重写了。
   *
   * <p>先替换 `\r\n` 再替换孤立的 `\r`：顺序反了会把 CRLF 变成两个换行，凭空多出空行。
   */
  private fun normalize(raw: String): String {
    var s = raw
    if (s.isNotEmpty() && s[0] == '\uFEFF') {
      s = s.substring(1)
    }
    if (s.indexOf('\r') >= 0) {
      s = s.replace("\r\n", "\n").replace('\r', '\n')
    }
    return s
  }

  /**
   * 拆行。末尾的换行符是**行终止符**而非空行，因此 `"a\nb\n"` 是 2 行不是 3 行。
   *
   * <p>若不这样处理，文件末尾多一个换行（编辑器几乎都会自动补）就会被算成一次「新增空行」，
   * 每次保存都多出一条无意义的 diff。
   */
  private fun splitLines(s: String): List<String> {
    if (s.isEmpty()) {
      return emptyList()
    }
    val lines = ArrayList<String>()
    var start = 0
    var i = 0
    while (i < s.length) {
      if (s[i] == '\n') {
        lines.add(s.substring(start, i))
        start = i + 1
      }
      i++
    }
    if (start < s.length) {
      lines.add(s.substring(start))
    }
    return lines
  }
}

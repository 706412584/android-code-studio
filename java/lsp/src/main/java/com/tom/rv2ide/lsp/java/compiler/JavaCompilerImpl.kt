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

package com.tom.rv2ide.lsp.java.compiler

import com.tom.rv2ide.javac.services.compiler.ReusableContext
import com.tom.rv2ide.javac.services.compiler.ReusableJavaCompiler
import com.tom.rv2ide.lsp.java.parser.ts.TSJavaParser
import com.tom.rv2ide.lsp.java.parser.ts.TSMethodPruner.prune
import com.tom.rv2ide.utils.VMUtils
import com.tom.rv2ide.utils.withStopWatch
import jdkx.tools.JavaFileObject
import jdkx.tools.JavaFileObject.Kind.SOURCE
import kotlin.io.path.name
import openjdk.tools.javac.api.ClientCodeWrapper
import openjdk.tools.javac.tree.JCTree.JCCompilationUnit
import openjdk.tools.javac.util.Context

class JavaCompilerImpl(context: Context?) : ReusableJavaCompiler(context) {

  override fun parse(filename: JavaFileObject?, content: CharSequence?): JCCompilationUnit {

    if (VMUtils.isJvm()) {
      return super.parse(filename, content)
    }

    val file = ClientCodeWrapper.instance(context).unwrap(filename)
    val compilerConfig = JavaCompilerConfig.instance(context)

    // Preconditions
    if (
        content == null ||
            compilerConfig.files == null ||
            filename?.kind != SOURCE ||
            compilerConfig.files?.contains(file) == false
    ) {
      return super.parse(filename, content)
    }

    // 剪枝（把方法体清空成空白）只对**补全**有意义：补全只关心光标所在方法，清掉其余
    // 方法体能大幅加速编译。非补全请求（典型是诊断）必须保留完整方法体——否则非 void
    // 方法被清空后 javac 看到的是 `String ping() { }`，会误报 missing return statement。
    //
    // 旧条件额外要求 `isActive`，隐含假设"诊断只在编辑器已打开的活动文档上跑"。AI 的
    // diagnostics 工具会对**未在编辑器打开**的文件跑诊断（isActive=false），于是主文件
    // （compilerConfig.files 里的那个）被剪枝，每个非 void 方法都误报"缺少返回语句"——
    // 实测正确代码 `return "123"` 与错误代码 `return 123` 都报同一条假错误。
    //
    // 去掉 isActive：补全路径 completionInfo != null 不受影响（仍剪枝）；活动文档的诊断
    // completionInfo == null 同样跳过剪枝（行为不变）；依赖文件走上面的 files 预检提前
    // 返回、本就不剪枝。唯一变化是"对未打开文件的诊断"现在拿到完整方法体。
    if (compilerConfig.completionInfo == null) {
      return super.parse(filename, content)
    }

    val pruned =
        withStopWatch(
            "${if(file is SourceFileObject) "[${file.path.name}] " else ""}Prune method bodies"
        ) { watch ->
          val contentBuilder = StringBuilder(content)

          return@withStopWatch TSJavaParser.parse(file).use { parseResult ->
            prune(
                contentBuilder,
                parseResult.tree,
                compilerConfig.completionInfo?.cursor?.index ?: -1,
            )

            watch.log()

            return@use contentBuilder
          }
        }

    return super.parse(filename, pruned)
  }

  companion object {
    @JvmStatic
    fun preRegister(context: ReusableContext, replace: Boolean = false) {
      if (replace) {
        context.drop(compilerKey)
      }
      context.put(compilerKey, Context.Factory { JavaCompilerImpl(it) })
    }
  }
}

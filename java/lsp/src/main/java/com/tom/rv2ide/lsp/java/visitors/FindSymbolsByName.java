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

package com.tom.rv2ide.lsp.java.visitors;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import openjdk.source.tree.ClassTree;
import openjdk.source.tree.MethodTree;
import openjdk.source.tree.VariableTree;
import openjdk.source.util.TreePath;
import openjdk.source.util.TreePathScanner;

/**
 * 在一个 CompilationUnit 内按名收集 类型/方法/字段 声明（含嵌套类与匿名类成员）。
 *
 * <p>与 {@link FindTypeDeclarations} 的区别：后者收集本文件全部类的 FQN（无位置信息），
 * 本类用 {@link TreePathScanner} 保留 {@link TreePath}，供调用方经 {@code Parser.range}
 * 换算行列——这是「按名搜索符号」必须的位置来源（{@code MethodRangeScanner} 已验证该范式）。
 *
 * <p>匹配为**区分大小写的精确标识符匹配**（{@code contentEquals}），与 Java 语义一致；
 * 不做模糊/前缀匹配（那会显著放大扫描量）。
 *
 * <p>匿名类的 {@code getSimpleName()} 为空串，与任何非空 query 都不相等，天然不命中。
 */
public class FindSymbolsByName extends TreePathScanner<Void, List<TreePath>> {

  private final String query;
  private final boolean wantTypes;
  private final boolean wantMethods;
  private final boolean wantFields;

  /** 当前所在类型的简单名栈（最内层在末尾），用于构造器（{@code <init>}）的按类名匹配。 */
  private final Deque<String> classNames = new ArrayDeque<>();

  public FindSymbolsByName(
      String query, boolean wantTypes, boolean wantMethods, boolean wantFields) {
    this.query = query == null ? "" : query;
    this.wantTypes = wantTypes;
    this.wantMethods = wantMethods;
    this.wantFields = wantFields;
  }

  @Override
  public Void visitClass(ClassTree node, List<TreePath> found) {
    String simpleName = node.getSimpleName().toString();
    if (wantTypes && simpleName.contentEquals(query)) {
      found.add(getCurrentPath());
    }
    // 继续下钻：嵌套类/局部类/匿名类内的声明同样是「声明处」。
    classNames.addLast(simpleName);
    try {
      return super.visitClass(node, found);
    } finally {
      classNames.removeLast();
    }
  }

  @Override
  public Void visitMethod(MethodTree node, List<TreePath> found) {
    String name = node.getName().toString();
    if (wantMethods) {
      // 构造器的名字是 <init>：query 等于所属类简单名时命中
      // （与 FindHelper / ReferenceProvider 对 <init> 的处理口径一致）。
      boolean hit =
          name.contentEquals(query)
              || ("<init>".contentEquals(name) && query.contentEquals(currentClassName()));
      if (hit) {
        found.add(getCurrentPath());
      }
    }
    return super.visitMethod(node, found);
  }

  @Override
  public Void visitVariable(VariableTree node, List<TreePath> found) {
    // 只收字段：父节点是 ClassTree（方法内局部变量的父节点是 BlockTree/MethodTree）。
    if (wantFields && node.getName().contentEquals(query)) {
      TreePath parent = getCurrentPath().getParentPath();
      if (parent != null && parent.getLeaf() instanceof ClassTree) {
        found.add(getCurrentPath());
      }
    }
    return super.visitVariable(node, found);
  }

  /** 当前所在类型的简单名（最内层）；不在任何类型内时返回空串。 */
  public String currentClassName() {
    return classNames.isEmpty() ? "" : classNames.peekLast();
  }
}

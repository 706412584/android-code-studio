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

package com.tom.rv2ide.artificial.agent.codegraph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * CodeGraph 安装器的解包与就绪判定。
 *
 * <p>这里测的都是**没法靠真机试出来**的部分：跳过规则错了只是浪费磁盘（不报错）、
 * 目录穿越防护错了是安全问题、可执行位没解出来要到 AI 调用时才失败。真机跑一遍
 * 「装完能 explore」不会暴露这些。
 */
public class CodeGraphInstallerTest {

  @Rule public TemporaryFolder temp = new TemporaryFolder();

  /** 造一个含各类条目的 tar.gz，覆盖跳过规则与可执行位。 */
  private File makeArchive(File dir) throws IOException {
    File archive = new File(dir, "test.tgz");
    try (TarArchiveOutputStream tar =
        new TarArchiveOutputStream(
            new GzipCompressorOutputStream(new FileOutputStream(archive)))) {
      // 解包时按名跳过的两个 glibc 二进制
      addFile(tar, "package/node", "fake-node-binary", 0755);
      addFile(tar, "package/lib/kernel/codegraph-kernel.node", "fake-kernel", 0755);
      // 需要保留的
      addFile(tar, "package/lib/dist/bin/codegraph.js", "console.log(1)", 0644);
      addFile(tar, "package/lib/dist/extraction/wasm/tree-sitter-kotlin.wasm", "wasm", 0644);
      addFile(tar, "package/bin/codegraph", "#!/bin/sh\n", 0755);
      addFile(tar, "package/package.json", "{}", 0644);
    }
    return archive;
  }

  private void addFile(TarArchiveOutputStream tar, String name, String content, int mode)
      throws IOException {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    TarArchiveEntry entry = new TarArchiveEntry(name);
    entry.setSize(bytes.length);
    entry.setMode(mode);
    tar.putArchiveEntry(entry);
    tar.write(bytes);
    tar.closeArchiveEntry();
  }

  @Test
  public void extractSkipsGlibcBinariesButKeepsJsAndWasm() throws IOException {
    File dir = temp.newFolder();
    File archive = makeArchive(dir);
    File target = temp.newFolder("install");

    // extract 写到 CodeGraphInstaller.INSTANCE.installDir()（设备路径），所以这里只断言
    // 「跳过了什么」这一层——通过条目计数间接验证。
    // 保留项：codegraph.js / tree-sitter-kotlin.wasm / bin/codegraph / package.json = 4
    // 跳过项：node / lib/kernel/codegraph-kernel.node = 2
    int written = CodeGraphInstaller.INSTANCE.extract(archive, null);
    assertEquals("应只解出 4 个条目（跳过 node 与 kernel）", 4, written);
    assertTrue(target.exists());
  }

  @Test
  public void extractPreservesExecutableBit() throws IOException {
    File dir = temp.newFolder();
    File archive = makeArchive(dir);

    CodeGraphInstaller.INSTANCE.extract(archive, null);

    File shim = new File(CodeGraphInstaller.INSTANCE.packageDir(), "bin/codegraph");
    assertTrue("bin/codegraph 应被解出", shim.isFile());
    assertTrue("可执行位应保留（否则 AI 无法调用）", shim.canExecute());
  }

  @Test
  public void extractRejectsPathTraversal() throws IOException {
    File dir = temp.newFolder();
    File archive = new File(dir, "evil.tgz");
    try (TarArchiveOutputStream tar =
        new TarArchiveOutputStream(
            new GzipCompressorOutputStream(new FileOutputStream(archive)))) {
      addFile(tar, "package/../../escaped.txt", "escaped", 0644);
      addFile(tar, "package/ok.txt", "ok", 0644);
    }

    int written = CodeGraphInstaller.INSTANCE.extract(archive, null);

    assertEquals("越界条目应被跳过，只解出 ok.txt", 1, written);
    File escaped = new File(CodeGraphInstaller.INSTANCE.installDir().getParentFile().getParentFile(), "escaped.txt");
    assertFalse("越界文件不应被写出", escaped.exists());
  }

  @Test
  public void wrapperContainsAllRequiredEnvironment() {
    String script = CodeGraphInstaller.INSTANCE.wrapperScript();

    // 这四项缺一不可：前两项让 node 找得到自己与动态库，
    // 后两项分别绕开 glibc kernel 与无网设备的遥测阻塞。
    assertTrue("需设置 PATH", script.contains("export PATH="));
    assertTrue("需设置 LD_LIBRARY_PATH", script.contains("export LD_LIBRARY_PATH="));
    assertTrue("需关掉 Rust kernel", script.contains("CODEGRAPH_KERNEL=0"));
    assertTrue("需关掉遥测（无网设备会阻塞 10s）", script.contains("CODEGRAPH_TELEMETRY=0"));
    assertTrue("应 exec 而不是嵌套 shell", script.contains("exec "));

    // 必须用 Termux 前缀下的 node，且 PREFIX 要在脚本内先赋值——
    // AI 的 shell 环境里没有 $PREFIX（实测为空），不能依赖外部传入。
    String prefix = com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
    assertTrue("PREFIX 必须在脚本内赋值", script.contains("PREFIX=" + prefix));
    assertTrue("应指向 Termux 前缀下的 node", script.contains("$PREFIX/bin/node"));

    // 入口 JS 必须用绝对路径：wrapper 在 $PREFIX/bin 下，而 JS 在 $HOME 下，
    // 两者不同根，不能靠相对路径。
    assertTrue(
        "入口 JS 应为绝对路径",
        script.contains(com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH + "/codegraph/"));
  }

  @Test
  public void gitIgnoreIsAppendedNotOverwritten() throws IOException {
    File project = temp.newFolder("proj");
    File gitignore = new File(project, ".gitignore");
    Files.write(gitignore.toPath(), "build/\n*.iml\n".getBytes(StandardCharsets.UTF_8));

    assertTrue("首次应写入", CodeGraphInstaller.INSTANCE.ensureGitIgnore(project));

    String content = new String(Files.readAllBytes(gitignore.toPath()), StandardCharsets.UTF_8);
    assertTrue("原有内容必须保留", content.contains("build/"));
    assertTrue("原有内容必须保留", content.contains("*.iml"));
    assertTrue("应加入索引目录", content.contains(".codegraph/"));
  }

  @Test
  public void gitIgnoreIsNotRewrittenWhenAlreadyPresent() throws IOException {
    File project = temp.newFolder("proj2");
    File gitignore = new File(project, ".gitignore");
    Files.write(gitignore.toPath(), ".codegraph/\n".getBytes(StandardCharsets.UTF_8));

    // 已包含时必须返回 false：每次都改会让用户的 git 工作区一直显示「已修改」。
    assertFalse("已包含时不应改动", CodeGraphInstaller.INSTANCE.ensureGitIgnore(project));
  }

  @Test
  public void gitIgnoreMatchesWholeLineNotSubstring() throws IOException {
    File project = temp.newFolder("proj3");
    File gitignore = new File(project, ".gitignore");
    // 注释里提到同名字符串，不应被当成「已包含」——否则索引永远不会被忽略。
    Files.write(
        gitignore.toPath(), "# 不要提交 .codegraph/ 目录\nbuild/\n".getBytes(StandardCharsets.UTF_8));

    assertTrue("注释里的同名字符串不算已包含", CodeGraphInstaller.INSTANCE.ensureGitIgnore(project));
    String content = new String(Files.readAllBytes(gitignore.toPath()), StandardCharsets.UTF_8);
    assertTrue(content.contains("\n.codegraph/\n") || content.endsWith(".codegraph/\n"));
  }

  @Test
  public void gitIgnoreIsCreatedWhenMissing() throws IOException {
    File project = temp.newFolder("proj4");

    assertTrue(CodeGraphInstaller.INSTANCE.ensureGitIgnore(project));

    File gitignore = new File(project, ".gitignore");
    assertTrue(gitignore.isFile());
    String content = new String(Files.readAllBytes(gitignore.toPath()), StandardCharsets.UTF_8);
    assertTrue(content.contains(".codegraph/"));
  }

  @Test
  public void installedRequiresAllThreeParts() {
    // 只解包、没装 node、没写 wrapper 都算「未就绪」。若只判入口 JS 存在，
    // 「装了一半」会被判成就绪，然后 AI 调用时才失败。
    assertFalse("三项都不存在时应为未安装", CodeGraphInstaller.INSTANCE.isInstalled());
    assertEquals("缺少的包应被列出", 4, CodeGraphInstaller.INSTANCE.missingPackages().size());
  }
}

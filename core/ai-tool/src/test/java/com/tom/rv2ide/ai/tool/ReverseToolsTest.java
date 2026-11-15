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

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * 逆向工具链（jadx/apktool/aapt/radare2/frida）的命令拼装、参数校验与错误诊断。
 *
 * <p>fake backend 按命令关键词匹配返回：探测命令（command -v）给路径或空，
 * 候选检测（test -x）默认失败，实际反编译/反汇编命令给成功或失败。
 */
final class ReverseToolsTest {

  /** 按命令关键词匹配返回预设结果；记录所有请求。 */
  private static final class FakeBackend implements ShellBackend {
    final List<ShellRequest> requests = new ArrayList<>();
    private final Map<String, ShellRequest.ShellResult> byKeyword;
    private final ShellRequest.ShellResult fallback;

    FakeBackend(Map<String, ShellRequest.ShellResult> byKeyword, ShellRequest.ShellResult fallback) {
      this.byKeyword = byKeyword;
      this.fallback = fallback;
    }

    @Override
    public String id() {
      return "fake";
    }

    @Override
    public String displayName() {
      return "假后端";
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public String unavailableReason() {
      return "";
    }

    @Override
    public ShellRequest.ShellResult execute(ShellRequest request, ShellRequest.ShellOutputSink sink) {
      requests.add(request);
      String cmd = request.getCommand();
      for (Map.Entry<String, ShellRequest.ShellResult> e : byKeyword.entrySet()) {
        if (cmd.contains(e.getKey())) {
          return e.getValue();
        }
      }
      return fallback;
    }
  }

  private static ShellRequest.ShellResult ok(String out) {
    return ShellRequest.ShellResult.success(out);
  }

  private static ShellRequest.ShellResult fail(int code, String err) {
    return new ShellRequest.ShellResult(code, "", err, false, false);
  }

  private static ToolContext ctx() {
    return ToolContext.builder().homePath("/proj").build();
  }

  private static ShellBackendRegistry registry(FakeBackend backend) {
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(backend);
    return registry;
  }

  private static FakeBackend backend(Map<String, ShellRequest.ShellResult> byKeyword) {
    // 默认：候选路径检测（test -x）与未匹配命令都失败（视为不存在）。
    return new FakeBackend(byKeyword, fail(1, ""));
  }

  private static Map<String, ShellRequest.ShellResult> map(Object... kv) {
    Map<String, ShellRequest.ShellResult> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], (ShellRequest.ShellResult) kv[i + 1]);
    }
    return m;
  }

  // ---- jadx 反编译 ----

  @Test
  void jadxBuildsDecompileCommandAndReportsOutputDir() throws Exception {
    FakeBackend b =
        backend(
            map(
                "command -v jadx", ok("/usr/bin/jadx\n"),
                "-d", ok(""),
                "find", ok("sources\nJAVA_FILES=42")));
    ToolResult r =
        new ApkDecompileTool(registry(b))
            .execute(new JSONObject().put("apk_path", "app.apk"), ctx());

    assertFalse(r.isError(), r.getContent());
    // 探测到绝对路径后，以其调用并 quote 路径。
    String cmd = b.requests.get(1).getCommand();
    assertTrue(cmd.startsWith("'/usr/bin/jadx' -d "), cmd);
    assertTrue(cmd.contains("'app.apk.jadx'"), "默认输出目录为 <apk>.jadx: " + cmd);
    assertTrue(cmd.endsWith("'app.apk'"), cmd);
    assertTrue(r.getContent().contains("app.apk.jadx"), r.getContent());
    assertTrue(r.getContent().contains("JAVA_FILES=42"), r.getContent());
  }

  @Test
  void jadxRejectsEmptyApkPath() throws Exception {
    FakeBackend b = backend(map());
    ToolResult r = new ApkDecompileTool(registry(b)).execute(new JSONObject(), ctx());
    assertTrue(r.isError());
    assertEquals(0, b.requests.size(), "空路径不应执行任何命令");
  }

  @Test
  void jadxNotInstalledGivesInstallHint() throws Exception {
    // command -v 与候选 test -x 全部失败 → probeExecutable 返回 null。
    FakeBackend b = backend(map("command -v jadx", fail(1, "")));
    ToolResult r =
        new ApkDecompileTool(registry(b))
            .execute(new JSONObject().put("apk_path", "app.apk"), ctx());

    assertTrue(r.isError());
    assertTrue(r.getContent().contains("未检测到可用的 jadx"), r.getContent());
    assertTrue(r.getContent().contains("安装"), r.getContent());
  }

  @Test
  void jadxFailureIncludesExitCodeAndStderr() throws Exception {
    FakeBackend b =
        backend(
            map(
                "command -v jadx", ok("/usr/bin/jadx\n"),
                "-d", fail(2, "java.lang.OutOfMemoryError")));
    ToolResult r =
        new ApkDecompileTool(registry(b))
            .execute(new JSONObject().put("apk_path", "app.apk"), ctx());

    assertTrue(r.isError());
    assertTrue(r.getContent().contains("退出码 2"), r.getContent());
    assertTrue(r.getContent().contains("OutOfMemoryError"), r.getContent());
  }

  @Test
  void jadxPathWithSingleQuoteIsEscaped() throws Exception {
    FakeBackend b =
        backend(
            map(
                "command -v jadx", ok("/usr/bin/jadx\n"),
                "-d", ok(""),
                "find", ok("")));
    new ApkDecompileTool(registry(b))
        .execute(new JSONObject().put("apk_path", "it's.apk"), ctx());
    // 单引号必须被转义，否则提前闭合引号造成注入。
    assertTrue(b.requests.get(1).getCommand().contains("it'\\''s.apk"), b.requests.get(1).getCommand());
  }

  // ---- apktool 解包 ----

  @Test
  void apktoolBuildsCommandAndExtractsManifest() throws Exception {
    FakeBackend b =
        backend(
            map(
                "command -v apktool", ok("/usr/bin/apktool\n"),
                "d -o", ok(""),
                "grep", ok("package=\"com.example\"\nuses-permission android.permission.INTERNET")));
    ToolResult r =
        new ApkResourcesTool(registry(b))
            .execute(new JSONObject().put("apk_path", "app.apk"), ctx());

    assertFalse(r.isError(), r.getContent());
    String cmd = b.requests.get(1).getCommand();
    assertTrue(cmd.startsWith("'/usr/bin/apktool' d -o "), cmd);
    assertTrue(r.getContent().contains("AndroidManifest.xml"), r.getContent());
    assertTrue(r.getContent().contains("com.example"), r.getContent());
  }

  // ---- aapt 包信息 ----

  @Test
  void aaptReadsBadging() throws Exception {
    FakeBackend b =
        backend(
            map(
                "command -v aapt", ok("/usr/bin/aapt\n"),
                "dump badging", ok("package: name='com.example' versionCode='7'\nuses-permission: INTERNET")));
    ToolResult r =
        new ApkInfoTool(registry(b)).execute(new JSONObject().put("apk_path", "app.apk"), ctx());

    assertFalse(r.isError(), r.getContent());
    assertTrue(b.requests.get(1).getCommand().contains("dump badging"), b.requests.get(1).getCommand());
    assertTrue(r.getContent().contains("com.example"), r.getContent());
  }

  @Test
  void aaptFallsBackToSdkBuildTools() throws Exception {
    // PATH 找不到 aapt，但 SDK build-tools 有。
    FakeBackend b =
        backend(
            map(
                "command -v aapt", fail(1, ""),
                "build-tools", ok("/sdk/build-tools/35.0.0/aapt\n"),
                "dump badging", ok("package: name='com.example'")));
    ToolResult r =
        new ApkInfoTool(registry(b)).execute(new JSONObject().put("apk_path", "app.apk"), ctx());

    assertFalse(r.isError(), r.getContent());
    assertTrue(
        b.requests.get(b.requests.size() - 1).getCommand().contains("/sdk/build-tools/35.0.0/aapt"),
        "应回退到 SDK 的 aapt");
  }

  @Test
  void aaptPrefersPackageManagerProvider() {
    // 注入 app 层 provider（PackageManager）：应优先用它，不调 aapt/shell。
    ApkInfoTool.setProvider(apkPath -> "package: com.example\nversionCode: 7");
    try {
      FakeBackend b = backend(map());
      ToolResult r =
          new ApkInfoTool(registry(b)).execute(new JSONObject().put("apk_path", "app.apk"), ctx());
      assertFalse(r.isError(), r.getContent());
      assertTrue(r.getContent().contains("com.example"), r.getContent());
      assertEquals(0, b.requests.size(), "provider 可用时不应调 aapt/shell");
    } finally {
      ApkInfoTool.setProvider(null); // 复位，避免影响其它 aapt 测试
    }
  }

  // ---- radare2 反汇编 ----

  @Test
  void r2BuildsPdfCommandWithAnalysis() throws Exception {
    FakeBackend b =
        backend(
            map(
                "command -v r2", ok("/usr/bin/r2\n"),
                "-c", ok("  push rbp\n  mov rbp,rsp")));
    ToolResult r =
        new BinaryDisasmTool(registry(b))
            .execute(
                new JSONObject().put("file_path", "lib.so").put("action", "pdf").put("function", "main"),
                ctx());

    assertFalse(r.isError(), r.getContent());
    String cmd = b.requests.get(1).getCommand();
    assertTrue(cmd.contains("'/usr/bin/r2'"), cmd);
    assertTrue(cmd.contains("-A -q"), "pdf 需要 -A 自动分析: " + cmd);
    assertTrue(cmd.contains("'s main;pdf'"), cmd);
    assertTrue(cmd.endsWith("'lib.so'"), cmd);
  }

  @Test
  void r2AflDoesNotRequireFunction() throws Exception {
    FakeBackend b = backend(map("command -v r2", ok("/usr/bin/r2\n"), "-c", ok("0x1000 main")));
    ToolResult r =
        new BinaryDisasmTool(registry(b))
            .execute(new JSONObject().put("file_path", "lib.so").put("action", "afl"), ctx());
    assertFalse(r.isError(), r.getContent());
    assertTrue(b.requests.get(1).getCommand().contains("'afl'"), b.requests.get(1).getCommand());
  }

  @Test
  void r2RejectsIllegalFunctionChars() throws Exception {
    FakeBackend b = backend(map());
    ToolResult r =
        new BinaryDisasmTool(registry(b))
            .execute(
                new JSONObject().put("file_path", "lib.so").put("action", "pdf").put("function", "main;rm -rf"),
                ctx());
    assertTrue(r.isError(), "含 r2 命令分隔符的 function 应被拒绝");
    assertEquals(0, b.requests.size(), "非法 function 不应执行命令");
  }

  @Test
  void r2PdfRequiresFunction() throws Exception {
    FakeBackend b = backend(map());
    ToolResult r =
        new BinaryDisasmTool(registry(b))
            .execute(new JSONObject().put("file_path", "lib.so").put("action", "pdf"), ctx());
    assertTrue(r.isError());
    assertEquals(0, b.requests.size());
  }

  // ---- frida 动态插桩 ----

  @Test
  void fridaPsBuildsCommand() throws Exception {
    FakeBackend b = backend(map("command -v frida-ps", ok("/usr/bin/frida-ps\n"), "-U", ok("PID Name\n123 com.example")));
    ToolResult r = new FridaHookTool(registry(b)).execute(new JSONObject(), ctx());
    assertFalse(r.isError(), r.getContent());
    assertTrue(b.requests.get(0).getCommand().contains("frida-ps"), b.requests.get(0).getCommand());
  }

  @Test
  void fridaIsSensitiveAndRequiresConfirmation() {
    FridaHookTool tool = new FridaHookTool(new ShellBackendRegistry());
    assertTrue(tool.isSensitiveCall(new JSONObject()), "动态插桩必须逐次确认");
    assertFalse(tool.isAllowedInReadonlyMode(), "只读模式不应放行动态插桩");
    assertEquals(com.tom.rv2ide.ai.tool.api.ToolCategory.SYSTEM, tool.getCategory());
  }

  @Test
  void fridaSpawnRequiresPackageAndScript() throws Exception {
    FakeBackend b = backend(map());
    ToolResult r =
        new FridaHookTool(registry(b))
            .execute(new JSONObject().put("action", "spawn"), ctx());
    assertTrue(r.isError());
    assertEquals(0, b.requests.size());
  }
}

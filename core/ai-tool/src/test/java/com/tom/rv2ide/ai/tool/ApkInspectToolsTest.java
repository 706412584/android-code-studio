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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * 四个纯 Java APK 内省工具 + 两个解析器的基本行为测试。
 *
 * <p>不依赖真实的 Android APK：直接构造只含所需条目的 ZIP，覆盖「能找到 / 找不到 /
 * 畸形数据不崩」三类路径。DEX/ARSC 用最小的合法头部构造空表，验证解析器对零表也能正常返回。
 */
class ApkInspectToolsTest {

  /** 生成一个临时 zip（apk）文件。 */
  private static File makeZip(String suffix, String[][] entries) throws Exception {
    File file = Files.createTempFile("acs-test-", suffix).toFile();
    file.deleteOnExit();
    try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
      for (String[] pair : entries) {
        out.putNextEntry(new ZipEntry(pair[0]));
        out.write(pair[1].getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
    return file;
  }

  private static ToolResult run(BaseTool tool, JSONObject input) {
    return tool.execute(input, null);
  }

  // ---- apk_search ----

  @Test
  void apkSearchFindsEntryNamesAndText() throws Exception {
    File apk =
        makeZip(
            ".apk",
            new String[][] {
              {"com/example/Foo.class", "x"},
              {"assets/config.txt", "hello WORLD inside"},
            });
    ApkSearchTool tool = new ApkSearchTool();

    ToolResult byEntry =
        run(tool, new JSONObject().put("apk_path", apk.getAbsolutePath()).put("query", "foo")
            .put("target", "entries"));
    assertFalse(byEntry.isError(), byEntry.getContent());
    assertTrue(byEntry.getContent().contains("com/example/Foo.class"), byEntry.getContent());

    // 文本搜索：大小写不敏感（world 命中 WORLD）。
    ToolResult byText =
        run(tool, new JSONObject().put("apk_path", apk.getAbsolutePath()).put("query", "world")
            .put("target", "text"));
    assertFalse(byText.isError(), byText.getContent());
    assertTrue(byText.getContent().contains("config.txt"), byText.getContent());
  }

  @Test
  void apkSearchRequiresQuery() throws Exception {
    File apk = makeZip(".apk", new String[][] {{"a.txt", "x"}});
    ToolResult result =
        run(new ApkSearchTool(), new JSONObject().put("apk_path", apk.getAbsolutePath()));
    assertTrue(result.isError());
  }

  // ---- apk_dex ----

  @Test
  void apkDexErrorsWhenNoDex() throws Exception {
    File apk = makeZip(".apk", new String[][] {{"assets/a.txt", "x"}});
    ToolResult result =
        run(new ApkDexTool(), new JSONObject().put("apk_path", apk.getAbsolutePath())
            .put("action", "list_classes"));
    assertTrue(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("dex"), result.getContent());
  }

  // ---- apk_signature ----

  @Test
  void apkSignatureReportsUnsigned() throws Exception {
    File apk = makeZip(".apk", new String[][] {{"AndroidManifest.xml", "not really axml"}});
    ToolResult result =
        run(new ApkSignatureTool(), new JSONObject().put("apk_path", apk.getAbsolutePath()));
    assertTrue(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("签名"), result.getContent());
  }

  // ---- apk_arsc ----

  @Test
  void apkArscErrorsWhenMissing() throws Exception {
    File apk = makeZip(".apk", new String[][] {{"classes.dex", "x"}});
    ToolResult result =
        run(new ApkArscTool(), new JSONObject().put("apk_path", apk.getAbsolutePath()));
    assertTrue(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("resources.arsc"), result.getContent());
  }

  // ---- apk_browse：overview / extract ----

  @Test
  void apkBrowseOverviewAndExtract() throws Exception {
    File apk =
        makeZip(
            ".apk",
            new String[][] {
              {"classes.dex", "x"},
              {"lib/arm64-v8a/libdemo.so", "hello-native-string-here"},
              {"assets/a.txt", "abcdef"},
            });
    ApkBrowseTool tool = new ApkBrowseTool();

    ToolResult overview =
        run(tool, new JSONObject().put("apk_path", apk.getAbsolutePath()).put("action", "overview"));
    assertFalse(overview.isError(), overview.getContent());
    assertTrue(overview.getContent().contains("dex: 1"), overview.getContent());
    assertTrue(overview.getContent().contains("native"), overview.getContent());

    File dest = Files.createTempFile("acs-extract-", ".bin").toFile();
    dest.delete();
    ToolResult extract =
        run(tool, new JSONObject().put("apk_path", apk.getAbsolutePath()).put("action", "extract")
            .put("entry", "assets/a.txt").put("to", dest.getAbsolutePath()));
    assertFalse(extract.isError(), extract.getContent());
    assertEquals("abcdef", new String(Files.readAllBytes(dest.toPath()), StandardCharsets.UTF_8));

    // 原生字符串搜索
    ToolResult nativeSearch =
        run(new ApkSearchTool(), new JSONObject().put("apk_path", apk.getAbsolutePath())
            .put("query", "native-string").put("target", "native"));
    assertFalse(nativeSearch.isError(), nativeSearch.getContent());
    assertTrue(nativeSearch.getContent().contains("libdemo.so"), nativeSearch.getContent());
  }

  @Test
  void apkEditPatchNativeString() throws Exception {
    File apk = makeZip(".apk", new String[][] {{"lib/arm64-v8a/libx.so", "hello-world"}});
    ApkEditTool tool = new ApkEditTool(null);
    File out = Files.createTempFile("acs-npatch-", ".apk").toFile();
    ToolResult r =
        tool.execute(
            new JSONObject()
                .put("apk_path", apk.getAbsolutePath())
                .put("action", "patch_native_string")
                .put("entry", "lib/arm64-v8a/libx.so")
                .put("old", "hello")
                .put("new", "HELLO")
                .put("output", out.getAbsolutePath()),
            null);
    assertFalse(r.isError(), r.getContent());
    assertEquals(
        "HELLO-world",
        new String(entryBytes(out, "lib/arm64-v8a/libx.so"), StandardCharsets.UTF_8));

    // 长度不等应被拒绝
    File out2 = Files.createTempFile("acs-npatch2-", ".apk").toFile();
    ToolResult bad =
        tool.execute(
            new JSONObject()
                .put("apk_path", apk.getAbsolutePath())
                .put("action", "patch_native_string")
                .put("entry", "lib/arm64-v8a/libx.so")
                .put("old", "hello")
                .put("new", "hi")
                .put("output", out2.getAbsolutePath()),
            null);
    assertTrue(bad.isError(), bad.getContent());
  }

  @Test
  void apkArscEditErrorsWhenMissing() throws Exception {
    File apk = makeZip(".apk", new String[][] {{"classes.dex", "x"}});
    ToolResult r =
        new ApkArscEditTool(null)
            .execute(
                new JSONObject().put("apk_path", apk.getAbsolutePath())
                    .put("id", "0x7f120000").put("value", "x"),
                null);
    assertTrue(r.isError(), r.getContent());
  }

  @Test
  void axmlDecoderRejectsNonAxml() {
    assertNull(AxmlDecoder.decode("not axml".getBytes(StandardCharsets.UTF_8)));
    assertNull(AxmlDecoder.decode(null));
  }

  @Test
  void apkNativeXrefAndSymbolsErrorPaths() throws Exception {
    File apk = makeZip(".apk", new String[][] {{"assets/a.txt", "x"}});
    ApkNativeTool tool = new ApkNativeTool();

    ToolResult xref =
        run(tool, new JSONObject().put("apk_path", apk.getAbsolutePath())
            .put("action", "xref").put("symbol", "foo"));
    assertFalse(xref.isError(), xref.getContent());
    assertTrue(xref.getContent().contains("未在"), xref.getContent());

    ToolResult symbols =
        run(tool, new JSONObject().put("apk_path", apk.getAbsolutePath())
            .put("action", "symbols").put("lib", "lib/arm64-v8a/libx.so"));
    assertTrue(symbols.isError(), symbols.getContent());
  }

  // ---- 解析器：零表与垃圾数据 ----

  @Test
  void dexParserAcceptsEmptyTableAndRejectsGarbage() {
    byte[] emptyDex = new byte[0x80];
    emptyDex[0] = 'd';
    emptyDex[1] = 'e';
    emptyDex[2] = 'x';
    emptyDex[3] = '\n';
    DexParser dex = DexParser.open(emptyDex);
    assertNotNull(dex);
    assertEquals(0, dex.classCount());
    assertTrue(dex.classDescriptors().isEmpty());
    assertTrue(dex.allStrings().isEmpty());

    assertNull(DexParser.open("not a dex".getBytes(StandardCharsets.UTF_8)));
    assertFalse(DexParser.looksLikeDex(new byte[] {1, 2, 3}));
  }

  @Test
  void arscParserAcceptsEmptyTableAndRejectsGarbage() {
    byte[] emptyArsc = new byte[16];
    emptyArsc[0] = 0x02; // RES_TABLE_TYPE
    emptyArsc[1] = 0x00;
    ArscParser arsc = ArscParser.open(emptyArsc);
    assertNotNull(arsc);
    assertTrue(arsc.entries().isEmpty());

    assertNull(ArscParser.open("not arsc".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void mutf8DecodesSurrogatePair() {
    // U+1F600 😀 的 MUTF-8：D83D -> ED A0 BD，DE00 -> ED B8 80
    byte[] b = {
      (byte) 0xED, (byte) 0xA0, (byte) 0xBD,
      (byte) 0xED, (byte) 0xB8, (byte) 0x80, 0
    };
    assertEquals("\uD83D\uDE00", ParseUtil.mutf8(b, 0, b.length));
  }

  @Test
  void parseUtilUleb128() {
    byte[] data = {(byte) 0xE5, (byte) 0x8E, (byte) 0x26}; // 624485
    int[] cursor = {0};
    assertEquals(624485, ParseUtil.uleb128(data, cursor));
    assertEquals(3, cursor[0]);
  }

  // ---- apk_edit（不解压重写） ----

  private static byte[] entryBytes(File zipFile, String name) throws Exception {
    try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipFile)) {
      java.util.zip.ZipEntry e = zip.getEntry(name);
      if (e == null) {
        return null;
      }
      try (java.io.InputStream in = zip.getInputStream(e)) {
        return ParseUtil.readAll(in);
      }
    }
  }

  @Test
  void apkEditReplacePatchDeleteWithoutExtract() throws Exception {
    File apk =
        makeZip(
            ".apk",
            new String[][] {{"a.txt", "hello"}, {"b.bin", new String(new char[] {0, 1, 2, 3})}});
    ApkEditTool tool = new ApkEditTool(null);

    File out1 = Files.createTempFile("acs-edit1-", ".apk").toFile();
    ToolResult r1 =
        tool.execute(
            new JSONObject()
                .put("apk_path", apk.getAbsolutePath())
                .put("action", "replace_text")
                .put("entry", "a.txt")
                .put("content", "hi")
                .put("output", out1.getAbsolutePath()),
            null);
    assertFalse(r1.isError(), r1.getContent());
    assertEquals("hi", new String(entryBytes(out1, "a.txt"), StandardCharsets.UTF_8));
    assertEquals(4, entryBytes(out1, "b.bin").length, "其他条目应原样透传");

    File out2 = Files.createTempFile("acs-edit2-", ".apk").toFile();
    ToolResult r2 =
        tool.execute(
            new JSONObject()
                .put("apk_path", apk.getAbsolutePath())
                .put("action", "patch_bytes")
                .put("entry", "b.bin")
                .put("patches", new org.json.JSONArray()
                    .put(new JSONObject().put("offset", 1).put("expected_hex", "01")
                        .put("replacement_hex", "ff")))
                .put("output", out2.getAbsolutePath()),
            null);
    assertFalse(r2.isError(), r2.getContent());
    assertEquals((byte) 0xff, entryBytes(out2, "b.bin")[1]);

    File out3 = Files.createTempFile("acs-edit3-", ".apk").toFile();
    ToolResult r3 =
        tool.execute(
            new JSONObject()
                .put("apk_path", apk.getAbsolutePath())
                .put("action", "delete")
                .put("entry", "b.bin")
                .put("output", out3.getAbsolutePath()),
            null);
    assertFalse(r3.isError(), r3.getContent());
    assertNull(entryBytes(out3, "b.bin"));
    assertNotNull(entryBytes(out3, "a.txt"));
  }
}

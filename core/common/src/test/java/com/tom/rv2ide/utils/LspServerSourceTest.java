/*
 * This file is part of AndroidCodeStudio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.tom.rv2ide.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/**
 * 语言服务器清单解析与候选地址。
 *
 * <p><b>为什么钉住</b>：清单来自两个源（Gitee 镜像 / GitHub 上游），
 * 而 Gitee 副本额外带 `sha256`、上游没有。解析必须两者兼容；
 * 候选顺序错乱（GitHub 在前）等于没做国内加速。
 */
public class LspServerSourceTest {

  private static final String GITEE_MANIFEST =
      "{\"Servers\":[{\"id\":\"Kotlin\",\"version\":\"1.3.15\","
          + "\"link\":\"https://gitee.com/owner/repo/releases/download/lsp-kotlin-v1.3.15/server.zip\","
          + "\"sha256\":\"a8b2e5f4a97939bc7a0c8c79d02aec74ceef1235b405b0df07058025dfd3e462\"},"
          + "{\"id\":\"C_CPP\",\"version\":\"18.0.2\",\"link\":\"null\"}]}";

  private static final String GITHUB_MANIFEST =
      "{\"Servers\":[{\"id\":\"Kotlin\",\"version\":\"1.3.15\","
          + "\"link\":\"https://github.com/AndroidCSOfficial/acs-language-servers/releases/download/v1.3.15/server.zip\"},"
          + "{\"id\":\"C_CPP\",\"version\":\"18.0.2\",\"link\":\"null\"}]}";

  @Test
  public void parsesGiteeManifestWithChecksum() {
    LspServerSource.Entry entry = LspServerSource.parse(GITEE_MANIFEST, "Kotlin");
    assertTrue(entry != null);
    assertEquals("1.3.15", entry.getVersion());
    assertTrue(entry.getLink().startsWith("https://gitee.com/"));
    assertEquals(
        "a8b2e5f4a97939bc7a0c8c79d02aec74ceef1235b405b0df07058025dfd3e462", entry.getSha256());
  }

  @Test
  public void parsesUpstreamManifestWithoutChecksum() {
    LspServerSource.Entry entry = LspServerSource.parse(GITHUB_MANIFEST, "Kotlin");
    assertTrue(entry != null);
    assertEquals("1.3.15", entry.getVersion());
    assertNull("上游清单没有 sha256，必须是 null 而不是空白串", entry.getSha256());
  }

  @Test
  public void normalizesNullLiteralLink() {
    LspServerSource.Entry entry = LspServerSource.parse(GITHUB_MANIFEST, "C_CPP");
    assertTrue(entry != null);
    assertNull("字面量 \"null\" 必须归一化为无链接", entry.getLink());
  }

  @Test
  public void filtersByIdNotByPosition() {
    // 顺序变化时不能取到别的服务器（旧实现在这里有 bug：直接取第一个）。
    assertNull(LspServerSource.parse(GITHUB_MANIFEST, "Python"));
  }

  @Test
  public void giteeLinkFirstThenGithubFallback() {
    LspServerSource.Entry entry = LspServerSource.parse(GITEE_MANIFEST, "Kotlin");
    List<String> candidates = LspServerSource.downloadCandidates(entry);
    assertEquals(2, candidates.size());
    assertTrue("Gitee 必须在前", candidates.get(0).startsWith("https://gitee.com/"));
    assertEquals(
        "https://github.com/AndroidCSOfficial/acs-language-servers/releases/download/v1.3.15/server.zip",
        candidates.get(1));
  }

  @Test
  public void missingLinkYieldsGithubOnly() {
    LspServerSource.Entry entry = LspServerSource.parse(GITHUB_MANIFEST, "C_CPP");
    List<String> candidates = LspServerSource.downloadCandidates(entry);
    assertEquals(1, candidates.size());
    assertEquals(
        "https://github.com/AndroidCSOfficial/acs-language-servers/releases/download/v18.0.2/server.zip",
        candidates.get(0));
  }

  @Test
  public void verifySkipsWhenChecksumAbsent() throws Exception {
    java.io.File tmp = java.io.File.createTempFile("lsp", ".zip");
    tmp.deleteOnExit();
    assertTrue("无摘要时应放行", LspServerSource.verify(tmp, null));
    assertFalse(
        "摘要不符时应失败并删除文件",
        LspServerSource.verify(tmp, "0000000000000000000000000000000000000000000000000000000000000000"));
    assertFalse(tmp.exists());
  }
}
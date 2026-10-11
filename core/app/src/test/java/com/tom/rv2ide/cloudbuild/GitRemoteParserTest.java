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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.cloudbuild;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * remote URL 解析的回归测试。
 *
 * <p><b>为什么这些用例值得逐条覆盖</b>：真实用户的 remote URL 形态五花八门
 * （HTTPS 带凭据、SSH 的两种写法、.git 后缀、非 GitHub 平台），解析错误的后果是
 * 「云打包按钮对用户的项目失效」而用户看不出原因。这里把已知形态固定下来。
 */
public class GitRemoteParserTest {

  @Test
  public void parsesHttpsUrl() {
    GitRemoteParser.RepoSlug slug = GitRemoteParser.parse("https://github.com/706412584/android-code-studio.git");
    assertTrue(slug.isValid());
    assertEquals("706412584", slug.owner);
    assertEquals("android-code-studio", slug.repo);
  }

  @Test
  public void parsesHttpsUrlWithoutGitSuffix() {
    GitRemoteParser.RepoSlug slug = GitRemoteParser.parse("https://github.com/owner/repo");
    assertTrue(slug.isValid());
    assertEquals("owner", slug.owner);
    assertEquals("repo", slug.repo);
  }

  @Test
  public void ignoresCredentialsInHttpsUrl() {
    GitRemoteParser.RepoSlug slug =
        GitRemoteParser.parse("https://user:ghp_secret@github.com/owner/repo.git");
    assertTrue(slug.isValid());
    assertEquals("owner", slug.owner);
    assertEquals("repo", slug.repo);
  }

  @Test
  public void parsesScpStyleSshUrl() {
    GitRemoteParser.RepoSlug slug = GitRemoteParser.parse("git@github.com:owner/repo.git");
    assertTrue(slug.isValid());
    assertEquals("owner", slug.owner);
    assertEquals("repo", slug.repo);
  }

  @Test
  public void parsesSshSchemeUrl() {
    GitRemoteParser.RepoSlug slug = GitRemoteParser.parse("ssh://git@github.com/owner/repo.git");
    assertTrue(slug.isValid());
    assertEquals("owner", slug.owner);
    assertEquals("repo", slug.repo);
  }

  @Test
  public void parsesGitSchemeUrl() {
    GitRemoteParser.RepoSlug slug = GitRemoteParser.parse("git://github.com/owner/repo.git");
    assertTrue(slug.isValid());
    assertEquals("owner", slug.owner);
    assertEquals("repo", slug.repo);
  }

  @Test
  public void rejectsNonGitHubHosts() {
    // 云打包只支持 GitHub Actions——Gitee 等项目必须被拒绝而不是误报成功。
    assertFalse(GitRemoteParser.parse("https://gitee.com/owner/repo.git").isValid());
    assertFalse(GitRemoteParser.parse("git@gitlab.com:owner/repo.git").isValid());
    assertFalse(GitRemoteParser.parse("https://bitbucket.org/owner/repo.git").isValid());
  }

  @Test
  public void rejectsMalformedInput() {
    assertFalse(GitRemoteParser.parse(null).isValid());
    assertFalse(GitRemoteParser.parse("").isValid());
    assertFalse(GitRemoteParser.parse("   ").isValid());
    assertFalse(GitRemoteParser.parse("github.com").isValid());
    assertFalse(GitRemoteParser.parse("https://github.com/owner").isValid());
  }

  @Test
  public void toStringReturnsSlug() {
    GitRemoteParser.RepoSlug slug = GitRemoteParser.parse("https://github.com/owner/repo.git");
    assertEquals("owner/repo", slug.toString());
  }
}

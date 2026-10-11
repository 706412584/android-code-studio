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

/**
 * 从 git remote URL 解析 GitHub 的 owner/repo。
 *
 * <p><b>为什么单独成类</b>：remote URL 的形态比想象中多——HTTPS 带/不带凭据、
 * SSH 的 {@code git@} 形式、{@code ssh://} 形式、带 {@code .git} 后缀与否。
 * 解析规则独立出来才能被 JVM 单测覆盖（不需要真仓库），而云打包入口的
 * 「自动识别当前项目仓库」正是靠它。
 */
public final class GitRemoteParser {

  private GitRemoteParser() {
  }

  /** 解析结果：owner 与 repo。 */
  public static final class RepoSlug {
    public final String owner;
    public final String repo;

    RepoSlug(String owner, String repo) {
      this.owner = owner;
      this.repo = repo;
    }

    public boolean isValid() {
      return owner != null && !owner.isEmpty() && repo != null && !repo.isEmpty();
    }

    @Override
    public String toString() {
      return owner + "/" + repo;
    }
  }

  /**
   * 解析 remote URL 为 owner/repo。
   *
   * <p>支持的形式：
   * <ul>
   *   <li>{@code https://github.com/owner/repo.git}</li>
   *   <li>{@code https://user:pass@github.com/owner/repo}（凭据被忽略）</li>
   *   <li>{@code git@github.com:owner/repo.git}</li>
   *   <li>{@code ssh://git@github.com/owner/repo.git}</li>
   *   <li>{@code git://github.com/owner/repo.git}</li>
   * </ul>
   *
   * @return 解析结果；无法识别或不是 GitHub 时返回一个 {@code isValid()==false} 的对象
   */
  public static RepoSlug parse(String remoteUrl) {
    if (remoteUrl == null || remoteUrl.trim().isEmpty()) {
      return new RepoSlug("", "");
    }
    String url = remoteUrl.trim();

    // 统一转成 "host/path" 形式再做提取。
    String hostAndPath;

    // scp 风格：git@github.com:owner/repo.git
    int atIndex = url.indexOf('@');
    int colonIndex = url.indexOf(':', atIndex + 1);
    if (url.startsWith("git@") || (atIndex > 0 && colonIndex > atIndex && !url.contains("://"))) {
      // 把 "git@github.com:owner/repo.git" 转成 "github.com/owner/repo.git"
      hostAndPath = url.substring(atIndex + 1, colonIndex) + "/" + url.substring(colonIndex + 1);
    } else {
      // URL 形式：先剥 scheme
      int schemeIndex = url.indexOf("://");
      if (schemeIndex > 0) {
        hostAndPath = url.substring(schemeIndex + 3);
      } else {
        hostAndPath = url;
      }
      // 剥离 user:pass@ 凭据段
      int credentialAt = hostAndPath.indexOf('@');
      int firstSlash = hostAndPath.indexOf('/');
      if (credentialAt > 0 && (firstSlash < 0 || credentialAt < firstSlash)) {
        hostAndPath = hostAndPath.substring(credentialAt + 1);
      }
    }

    // 现在 hostAndPath 形如 "github.com/owner/repo.git"
    String[] segments = hostAndPath.split("/");
    if (segments.length < 3) {
      return new RepoSlug("", "");
    }
    String host = segments[0].toLowerCase(java.util.Locale.ROOT);
    if (!host.equals("github.com") && !host.endsWith(".github.com")) {
      // 云打包只支持 GitHub Actions。Gitee 等其它平台不是本功能的覆盖面。
      return new RepoSlug("", "");
    }
    String owner = segments[1];
    String repo = segments[2];
    if (repo.endsWith(".git")) {
      repo = repo.substring(0, repo.length() - 4);
    }
    // 防御：owner/repo 不应为空或含空白。
    if (owner.trim().isEmpty() || repo.trim().isEmpty()) {
      return new RepoSlug("", "");
    }
    return new RepoSlug(owner.trim(), repo.trim());
  }
}

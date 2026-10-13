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

import org.json.JSONObject;

/**
 * 一次 run 产出的 artifact（构建产物 zip）。
 *
 * <p>{@code archiveDownloadUrl} 是 GitHub API 的下载端点——**不是**直链，
 * 需要带 Authorization 头请求，且会 302 到对象存储。下载逻辑见
 * {@code GitHubActionsClient#downloadArtifact}。
 */
public final class WorkflowArtifact {

  public final long id;
  public final String name;
  /** 压缩后大小（字节）。GitHub 在 artifact 未过期时提供，过期后为 0。 */
  public final long sizeInBytes;
  public final boolean expired;
  public final String archiveDownloadUrl;

  public WorkflowArtifact(long id, String name, long sizeInBytes, boolean expired,
                          String archiveDownloadUrl) {
    this.id = id;
    this.name = name == null ? "" : name;
    this.sizeInBytes = sizeInBytes;
    this.expired = expired;
    this.archiveDownloadUrl = archiveDownloadUrl == null ? "" : archiveDownloadUrl;
  }

  /** 人类可读的大小（KB/MB）。 */
  public String sizeText() {
    if (sizeInBytes <= 0) {
      return "未知大小";
    }
    if (sizeInBytes < 1024 * 1024) {
      return (sizeInBytes / 1024) + " KB";
    }
    return String.format(java.util.Locale.ROOT, "%.1f MB", sizeInBytes / 1024.0 / 1024.0);
  }

  static WorkflowArtifact fromJson(JSONObject json) {
    return new WorkflowArtifact(
        json.optLong("id"),
        json.optString("name", ""),
        json.optLong("size_in_bytes"),
        json.optBoolean("expired", false),
        json.optString("archive_download_url", ""));
  }
}

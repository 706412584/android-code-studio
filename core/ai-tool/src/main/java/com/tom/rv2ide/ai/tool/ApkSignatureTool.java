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

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.List;
import org.json.JSONObject;

/**
 * 离线读取 APK 签名证书：输出签名方案、证书指纹（SHA-256/SHA-1）、主体、颁发者、有效期。
 *
 * <p>与 {@code apk_info} 的区别：后者用 {@code PackageManager.getPackageArchiveInfo}，
 * 只能拿到已安装包的签名摘要，且不区分方案、不给证书主体/有效期；本工具对**磁盘上任意
 * APK** 直接读 v1/v2/v3 证书，纯 Java、无需 shell，也不需要 apksigner。
 *
 * <p>只读证书、<b>不校验签名完整性</b>（见 {@link ApkSignatureReader}）。
 */
public final class ApkSignatureTool extends BaseTool {

  @Override
  public String getName() {
    return ToolNames.APK_SIGNATURE;
  }

  @Override
  public String getDescription() {
    return "离线读取 APK 的签名证书（v1/v2/v3）：签名方案、证书 SHA-256/SHA-1 指纹、主体、"
        + "颁发者、有效期、序列号、密钥算法。参数：apk_path。只读证书，不校验签名是否被篡改。"
        + "用于「这个包是谁签的、和另一个包是不是同一把密钥」。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.READ;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    return true;
  }

  @Override
  public boolean isConcurrencySafe() {
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put("apk_path", new JSONObject().put("type", "string").put("description", "APK 文件路径")))
        .put("required", new org.json.JSONArray().put("apk_path"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String apkPath = input.optString("apk_path", "").trim();
    if (apkPath.isEmpty()) {
      return error("apk_path 不能为空");
    }
    String home = context == null ? "" : context.getHomePath();
    File file = new File(apkPath);
    if (!file.isAbsolute() && !home.isEmpty()) {
      file = new File(home, apkPath);
    }
    if (!file.isFile()) {
      return error("APK 文件不存在或不可读：" + file.getAbsolutePath());
    }

    ApkSignatureReader.Result result = ApkSignatureReader.read(file);
    if (result.certificates.isEmpty()) {
      return error(result.error.isEmpty() ? "未读取到签名证书。" : result.error);
    }

    StringBuilder sb = new StringBuilder();
    sb.append("APK：").append(file.getName()).append('\n');
    sb.append("签名方案：")
        .append(result.schemes.isEmpty() ? "（未知）" : join(result.schemes))
        .append('\n');
    sb.append("证书数：").append(result.certificates.size()).append('\n');
    int index = 0;
    for (X509Certificate cert : result.certificates) {
      index++;
      sb.append("\n── 证书 ").append(index).append(" ──\n");
      sb.append("SHA-256：").append(fingerprint(cert, "SHA-256")).append('\n');
      sb.append("SHA-1：").append(fingerprint(cert, "SHA-1")).append('\n');
      sb.append("主体：").append(cert.getSubjectX500Principal().getName()).append('\n');
      sb.append("颁发者：").append(cert.getIssuerX500Principal().getName()).append('\n');
      sb.append("有效期：").append(formatDate(cert.getNotBefore()))
          .append(" ~ ").append(formatDate(cert.getNotAfter()));
      if (cert.getNotAfter().getTime() < System.currentTimeMillis()) {
        sb.append("（已过期）");
      }
      sb.append('\n');
      sb.append("序列号：").append(cert.getSerialNumber().toString(16)).append('\n');
      sb.append("签名算法：").append(cert.getSigAlgName()).append('\n');
      sb.append("公钥算法：").append(cert.getPublicKey().getAlgorithm()).append('\n');
    }
    return ok(sb.toString());
  }

  private static String fingerprint(X509Certificate cert, String algorithm) {
    try {
      MessageDigest md = MessageDigest.getInstance(algorithm);
      return ParseUtil.hexColon(md.digest(cert.getEncoded()));
    } catch (Exception e) {
      return "（计算失败：" + e.getMessage() + "）";
    }
  }

  private static String formatDate(java.util.Date date) {
    SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT);
    return fmt.format(date);
  }

  private static String join(List<String> items) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) {
        sb.append(" + ");
      }
      sb.append(items.get(i));
    }
    return sb.toString();
  }
}

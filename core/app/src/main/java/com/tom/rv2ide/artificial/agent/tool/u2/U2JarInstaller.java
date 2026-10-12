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

package com.tom.rv2ide.artificial.agent.tool.u2;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * 把打包在 APK assets 里的 u2.jar 提取到设备外部私有目录，供 Shizuku 启动的 server 加载。
 *
 * <p><b>为什么必须是 externalFilesDir 而不是 filesDir</b>：Shizuku 启动的进程
 * （shell uid 2000 / root）读不到 app 内部私有目录（{@code /data/data/<pkg>/files/}
 * 是 {@code drwx------}）；而外部私有目录 {@code /sdcard/Android/data/<pkg>/files/}
 * 的读取权限由挂载层 + 进程组集（{@code sdcard_rw}）决定，adb 启动的 shell 进程
 * 实测可读（黑鲨实测，2026-10-12）。
 *
 * <p><b>为什么不做 chmod</b>：{@code /sdcard}（sdcardfs）上 chmod 完全无效——
 * 实测 {@code chmod 777} 后 stat 仍是 {@code 660}；权限由挂载层控制。
 *
 * <p>对账策略：文件存在且 sha256 匹配即复用，否则覆盖写。sha256 而不是大小/时间戳——
 * jar 更新后必须让设备侧加载新版本，静默用旧版本会让「升级了却没生效」难以排查。
 */
final class U2JarInstaller {

  /** assets 内的路径。 */
  private static final String ASSET_PATH = "u2/u2.jar";

  /**
   * u2.jar 的预期 sha256（uiautomator2 3.7.0 的 assets/u2.jar，
   * 与设备实测版本 md5 31a97dd43d8bb343b417e6f195f5b95a 一致）。
   * 升级 jar 时必须同步更新（对账靠它识别旧版本）。
   */
  static final String EXPECTED_SHA256 =
      "0b74e83c55f443539a9f76f5ce023a51466b764b1100e4097a897053fdfc0eb6";

  private U2JarInstaller() {}

  /**
   * 确保 jar 已提取到目标位置。
   *
   * @return 提取后的文件；失败返回 null（原因由调用方结合 {@link #lastError()} 报告）
   */
  static File install(Context context) {
    File base = context.getExternalFilesDir(null);
    if (base == null) {
      // 存储未挂载（USB 挂载到 PC 等）——明确失败，而不是退回读不到的 filesDir。
      lastError = "外部存储不可用（getExternalFilesDir 为 null）";
      return null;
    }
    File dir = new File(base, "u2");
    File jar = new File(dir, ASSET_PATH.substring("u2/".length()));
    if (jar.exists() && EXPECTED_SHA256.equalsIgnoreCase(sha256(jar))) {
      return jar; // 已是最新版本，复用
    }
    if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
      lastError = "无法创建目录：" + dir.getAbsolutePath();
      return null;
    }
    try (InputStream in = context.getAssets().open(ASSET_PATH);
        FileOutputStream out = new FileOutputStream(jar)) {
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
    } catch (IOException e) {
      lastError = "提取 " + ASSET_PATH + " 失败: " + e.getMessage();
      return null;
    }
    String actual = sha256(jar);
    if (!EXPECTED_SHA256.equalsIgnoreCase(actual)) {
      // 提取过程被截断/污染——删掉脏文件，避免下次误用。
      //noinspection ResultOfMethodCallIgnored
      jar.delete();
      lastError = "提取后的 jar 校验失败（期望 " + EXPECTED_SHA256 + "，实际 " + actual + "）";
      return null;
    }
    return jar;
  }

  /** 最近一次 install 失败的原因；成功时为空串。 */
  private static volatile String lastError = "";

  static String lastError() {
    return lastError;
  }

  /** 计算文件 sha256；失败返回空串。 */
  static String sha256(File file) {
    if (file == null || !file.isFile()) {
      return "";
    }
    try (FileInputStream in = new FileInputStream(file)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = in.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
      byte[] hash = digest.digest();
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (Exception e) {
      return "";
    }
  }
}

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

package com.tom.rv2ide.artificial.agent.tool;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import com.tom.rv2ide.ai.tool.ApkInfoTool;

/**
 * {@link ApkInfoTool.Provider} 的 Android 实现：用 {@link PackageManager#getPackageArchiveInfo}
 * 解析 APK 包信息。
 *
 * <p><b>为什么用它而不是 aapt</b>：aapt 是桌面（x86_64）工具，Android 设备上跑不了；
 * 而 {@code getPackageArchiveInfo} 是设备原生 API，无需安装任何外部工具，
 * 直接给出包名、版本、权限与四大组件。
 */
public final class ApkPackageInfoProvider implements ApkInfoTool.Provider {

  private final Context appContext;

  public ApkPackageInfoProvider(Context appContext) {
    this.appContext = appContext.getApplicationContext();
  }

  @Override
  public String readApkInfo(String apkPath) {
    try {
      PackageManager pm = appContext.getPackageManager();
      int flags =
          PackageManager.GET_PERMISSIONS
              | PackageManager.GET_ACTIVITIES
              | PackageManager.GET_SERVICES
              | PackageManager.GET_RECEIVERS
              | PackageManager.GET_PROVIDERS;
      PackageInfo info = pm.getPackageArchiveInfo(apkPath, flags);
      if (info == null) {
        return null;
      }
      StringBuilder sb = new StringBuilder();
      sb.append("package: ").append(info.packageName).append('\n');
      sb.append("versionCode: ").append(info.versionCode).append('\n');
      sb.append("versionName: ").append(info.versionName).append('\n');
      if (info.applicationInfo != null) {
        CharSequence label = info.applicationInfo.loadLabel(pm);
        if (label != null && label.length() > 0) {
          sb.append("label: ").append(label).append('\n');
        }
        sb.append("minSdk: ").append(info.applicationInfo.minSdkVersion).append('\n');
        sb.append("targetSdk: ").append(info.applicationInfo.targetSdkVersion).append('\n');
      }
      appendPermissions(sb, info);
      appendComponents(sb, "activities", info.activities);
      appendComponents(sb, "services", info.services);
      appendComponents(sb, "receivers", info.receivers);
      appendComponents(sb, "providers", info.providers);
      return sb.toString();
    } catch (Exception e) {
      return null;
    }
  }

  private static void appendPermissions(StringBuilder sb, PackageInfo info) {
    PermissionInfo[] permissions = info.permissions;
    if (permissions == null || permissions.length == 0) {
      return;
    }
    sb.append("\npermissions (").append(permissions.length).append("):\n");
    for (PermissionInfo p : permissions) {
      sb.append("  ").append(p.name).append('\n');
    }
  }

  private static void appendComponents(StringBuilder sb, String title, ActivityInfo[] components) {
    if (components == null || components.length == 0) {
      return;
    }
    sb.append("\n").append(title).append(" (").append(components.length).append("):\n");
    for (ActivityInfo c : components) {
      sb.append("  ").append(c.name).append('\n');
    }
  }
}

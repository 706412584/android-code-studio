/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 *
 */
package com.tom.rv2ide.managers;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.FileUtils;
import com.blankj.utilcode.util.ResourceUtils;
import com.tom.rv2ide.app.BaseApplication;
import com.tom.rv2ide.app.configuration.IDEBuildConfigProvider;
import com.tom.rv2ide.app.configuration.IJdkDistributionProvider;
import com.tom.rv2ide.utils.ElfArch;
import com.tom.rv2ide.utils.Environment;

import org.jetbrains.annotations.Contract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

import kotlin.io.ConstantsKt;
import kotlin.io.FilesKt;

public class ToolsManager {

  private static final Logger LOG = LoggerFactory.getLogger(ToolsManager.class);

  public static String COMMON_ASSET_DATA_DIR = "data/common";

  public static void init(@NonNull BaseApplication app, Runnable onFinish) {

    if (!IDEBuildConfigProvider.getInstance().supportsCpuAbi()) {
      LOG.error("Device not supported");
      return;
    }

    CompletableFuture.runAsync(() -> {
      // Load installed JDK distributions
      IJdkDistributionProvider.getInstance().loadDistributions();

      writeNoMediaFile();
      extractAapt2();
      extractToolingApi();
      extractAndroidJar();
      extractColorScheme(app);
      writeInitScript();

      deleteIdeenv();
    }).whenComplete((__, error) -> {
      if (error != null) {
        LOG.error("Error extracting tools", error);
      }

      if (onFinish != null) {
        onFinish.run();
      }
    });
  }

  private static void extractColorScheme(final BaseApplication app) {
    final var defPath = "editor/schemes";
    final var dir = new File(Environment.ANDROIDIDE_UI, defPath);
    try {
      for (final String asset : app.getAssets().list(defPath)) {

        final var prop = new File(dir, asset + "/" + "scheme.prop");
        if (prop.exists() && !shouldExtractScheme(app, new File(dir, asset),
            defPath + "/" + asset)) {
          continue;
        }

        final File schemeDir = new File(dir, asset);
        if (schemeDir.exists()) {
          schemeDir.delete();
        }

        ResourceUtils.copyFileFromAssets(defPath + "/" + asset, schemeDir.getAbsolutePath());
      }
    } catch (IOException e) {
      LOG.error("Failed to extract color schemes", e);
    }
  }

  private static boolean shouldExtractScheme(final BaseApplication app, final File dir,
      final String path) throws IOException {

    final var schemePropFile = new File(dir, "scheme.prop");
    if (!schemePropFile.exists()) {
      return true;
    }

    final var files = app.getAssets().list(path);
    if (Arrays.stream(files).noneMatch("scheme.prop"::equals)) {
      // no scheme.prop file
      return true;
    }

    try {
      final var props = new Properties();
      Reader reader = new InputStreamReader(app.getAssets().open(path + "/scheme.prop"));
      props.load(reader);
      reader.close();

      final var version = Integer.parseInt(props.getProperty("scheme.version", "0"));
      if (version == 0) {
        return true;
      }

      props.clear();

      reader = new FileReader(schemePropFile);
      props.load(reader);
      reader.close();

      final var fileVersion = Integer.parseInt(props.getProperty("scheme.version", "0"));
      if (fileVersion < 0) {
        return true;
      }

      return version > fileVersion;
    } catch (Throwable err) {
      LOG.error("Failed to read color scheme version for scheme '{}'", path, err);
      return false;
    }
  }

  private static void writeNoMediaFile() {
    final var noMedia = new File(BaseApplication.getBaseInstance().getProjectsDir(), ".nomedia");
    if (!noMedia.exists()) {
      try {
        if (!noMedia.createNewFile()) {
          LOG.error("Failed to create .nomedia file in projects directory");
        }
      } catch (IOException e) {
        LOG.error("Failed to create .nomedia file in projects directory");
      }
    }
  }

  private static void extractAndroidJar() {
    if (!Environment.ANDROID_JAR.exists()) {
      ResourceUtils.copyFileFromAssets(getCommonAsset("android.jar"),
          Environment.ANDROID_JAR.getAbsolutePath());
    }
  }

  private static void deleteIdeenv() {
    final var file = new File(Environment.BIN_DIR, "ideenv");
    if (file.exists() && !file.delete()) {
      LOG.warn("Unable to delete file: {}", file);
    }
  }

  @NonNull
  @Contract(pure = true)
  public static String getCommonAsset(String name) {
    return COMMON_ASSET_DATA_DIR + "/" + name;
  }

  private static void extractAapt2() {
    final var context = BaseApplication.getBaseInstance();
    final var nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
    final var sourceAapt2 = new File(nativeLibraryDir, "libaapt2.so");

    // Re-extract when the installed binary is missing, empty, not executable, or built for
    // a CPU architecture this device cannot run. Without the architecture check a wrong-ABI
    // binary (e.g. x86_64 left on an ARM device) would survive forever and fail with
    // "Exec format error" on every build. See upstream issue #370.
    //
    // The source must be usable too, otherwise a device that cannot run its own packaged
    // binary (e.g. a translated host) would delete and re-copy it on every launch for nothing.
    final var installed = Environment.AAPT2;
    final var sourceUsable =
        sourceAapt2.exists()
            && sourceAapt2.isFile()
            && sourceAapt2.length() > 0
            && isCompatibleAapt2(sourceAapt2);

    final var needsExtract = !installed.exists()
                                 || installed.length() == 0
                                 || !installed.canExecute()
                                 || !isCompatibleAapt2(installed);

    if (needsExtract && sourceUsable) {
      try {
        if (installed.exists() && !installed.delete()) {
          LOG.warn("Unable to delete outdated AAPT2 binary: {}", installed);
        }
        FilesKt.copyTo(sourceAapt2, installed, true, ConstantsKt.DEFAULT_BUFFER_SIZE);
        LOG.info(
            "Installed AAPT2 from {} ({} bytes, compatible={})",
            sourceAapt2,
            installed.length(),
            isCompatibleAapt2(installed));
      } catch (Throwable err) {
        LOG.error("Failed to copy AAPT2 from {}", sourceAapt2, err);
      }
    } else if (needsExtract) {
      LOG.error(
          "AAPT2 is unusable and {} cannot replace it (exists={}, compatible={})."
              + " processDebugResources may fail.",
          sourceAapt2,
          sourceAapt2.exists(),
          isCompatibleAapt2(sourceAapt2));
    }

    if (installed.exists() && !installed.canExecute() && !installed.setExecutable(true)) {
      LOG.error("Cannot set executable permissions to AAPT2 binary");
    }
  }

  /**
   * Checks whether the given AAPT2 binary was built for a CPU architecture the device supports.
   *
   * <p>Note: this deliberately reads {@link android.os.Build#SUPPORTED_ABIS} (what this device
   * can execute) rather than {@link IDEBuildConfigProvider#getSupportedAbis()} (which is a fixed
   * list of ABIs the IDE can target). Using the latter would make the check ineffective.
   *
   * @param aapt2 The AAPT2 binary to inspect.
   * @return {@code true} if the binary is compatible. Missing, unreadable, or non-ELF files are
   *     reported as incompatible so that callers replace them.
   */
  private static boolean isCompatibleAapt2(@NonNull File aapt2) {
    return ElfArch.isCompatibleWith(ElfArch.machineOf(aapt2), android.os.Build.SUPPORTED_ABIS);
  }

  private static void extractToolingApi() {
    if (Environment.TOOLING_API_JAR.exists()) {
      FileUtils.delete(Environment.TOOLING_API_JAR);
    }

    ResourceUtils.copyFileFromAssets(getCommonAsset("tooling-api-all.jar"),
        Environment.TOOLING_API_JAR.getAbsolutePath());
  }

  private static void writeInitScript() {
    final var initScript = Environment.INIT_SCRIPT;
    final var initScriptBak = new File(initScript.getParentFile(), initScript.getName() + ".bak");
    final var contents = readInitScript();

    FilesKt.writeText(initScriptBak, contents, StandardCharsets.UTF_8);
    if (!initScript.exists()) {
      FilesKt.writeText(initScript, contents, StandardCharsets.UTF_8);
    }
  }

  @NonNull
  private static String readInitScript() {
    return ResourceUtils.readAssets2String(getCommonAsset("androidide.init.gradle"));
  }

}

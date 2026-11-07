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

package com.tom.androidcodestudio.acsprovider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.tom.androidcodestudio.acsprovider.models.ACSConfig;
import com.tom.androidcodestudio.acsprovider.models.PackageEntry;
import java.io.File;
import org.junit.Test;

/**
 * {@link ACSProvider#findPackageEntry} 的选包回归测试。
 *
 * <p>回归的是 CN 版「安装 CMake 失败：tar extraction failed / This does not look like a
 * tar archive」：镜像 manifest 给同一 (android-cmake, arm64-v8a, 4.1.2) 同时列了
 * 「加密自解压 bundle」与「普通 tar.xz」两条，bundle 排在前面，而旧实现按顺序取第一条，
 * 于是稳定选中无法解包的 bundle。
 *
 * <p>测试全部走真实 JSON 文本（manifest 结构原样），不 mock——被测算法的输入就是 JSON。
 */
public class ACSProviderPickTest {

  /** manifest 里真实出现过的两条同组条目：坏的 bundle 在前，好的 tar.xz 在后。 */
  private static final String CMakeShadowedManifest =
      "{\n"
          + "  \"manifest_version\": \"1.0-mirror\",\n"
          + "  \"packages\": [\n"
          + "    {\n"
          + "      \"id\": \"android-cmake\",\n"
          + "      \"architecture\": \"arm64-v8a\",\n"
          + "      \"filename\": \"android-cmake-build-tools-arm64-bundle-v1\",\n"
          + "      \"version\": \"4.1.2\",\n"
          + "      \"bundle_version\": \"1\",\n"
          + "      \"url\": \"https://example.invalid/android-cmake-build-tools-arm64-bundle-v1\",\n"
          + "      \"encrypted\": true,\n"
          + "      \"sha256\": \"08055d160e592d838af4d57ce5561792712b0ab1ced18cd250dd16fc65b25898\"\n"
          + "    },\n"
          + "    {\n"
          + "      \"id\": \"android-cmake\",\n"
          + "      \"architecture\": \"arm64-v8a\",\n"
          + "      \"filename\": \"cmake-aarch64-linux-musl.tar.xz\",\n"
          + "      \"version\": \"4.1.2\",\n"
          + "      \"bundle_version\": \"1\",\n"
          + "      \"url\": \"https://example.invalid/cmake-aarch64-linux-musl.tar.xz\",\n"
          + "      \"encrypted\": false,\n"
          + "      \"sha256\": \"d412df999891e5c3e4c2bff0d26827c5c309542c485e11c3810c1ddd01edde72\"\n"
          + "    }\n"
          + "  ]\n"
          + "}";

  private static ACSProvider provider() {
    // Kotlin 默认参数未加 @JvmOverloads，Java 看不到单参与双参重载，必须传满三个参数。
    // tempDir 只用于 mkdirs，传同一目录即可（不涉及任何 Android API）。
    File dir = new File(System.getProperty("java.io.tmpdir"), "acs-pick-test");
    return new ACSProvider(dir, dir, false);
  }

  private static ACSConfig versioned(String arch, String id, String version) {
    return new ACSConfig(null, arch, id, version, null, null, true, null);
  }

  private static PackageEntry find(String json, ACSConfig config) {
    return provider().findPackageEntry(json, config);
  }

  /**
   * 核心回归：同组里坏的 bundle 排在前面，也必须选出后面的 tar.xz。
   *
   * <p>旧实现在此返回 bundle（filename = android-cmake-build-tools-arm64-bundle-v1）→ 红。
   */
  @Test
  public void picksPlainArchiveOverEncryptedBundleInSameGroup() {
    PackageEntry picked = find(CMakeShadowedManifest, versioned("arm64-v8a", "android-cmake", "4.1.2"));

    assertEquals("cmake-aarch64-linux-musl.tar.xz", picked.getFilename());
    assertFalse(picked.getEncrypted());
  }

  /** 不指定版本（走 versions.last() 那条路径）时也要跳过 bundle。 */
  @Test
  public void picksPlainArchiveWhenVersionNotSpecified() {
    PackageEntry picked = find(CMakeShadowedManifest, versioned("arm64-v8a", "android-cmake", null));

    assertEquals("cmake-aarch64-linux-musl.tar.xz", picked.getFilename());
    assertFalse(picked.getEncrypted());
  }

  /** manifest 显式写 encrypted: false / 缺字段，都应解析成「可用」，不能被误判为 bundle。 */
  @Test
  public void parsesEncryptedFlag() {
    assertFalse(find(CMakeShadowedManifest, versioned("arm64-v8a", "android-cmake", "4.1.2")).getEncrypted());

    String noFlag =
        "{\"packages\":[{\"id\":\"android-cmake\",\"architecture\":\"arm64-v8a\","
            + "\"filename\":\"cmake-aarch64-linux-musl.tar.xz\",\"version\":\"4.1.2\","
            + "\"url\":\"https://example.invalid/x.tar.xz\",\"sha256\":\"aa\"}]}";
    assertFalse(find(noFlag, versioned("arm64-v8a", "android-cmake", "4.1.2")).getEncrypted());
  }

  /**
   * 只有 bundle 可用时必须原样返回它，而不是抛错——此时确实没有替代品，
   * 上层应走原有错误路径（让用户看到真实失败原因），而不是误导为「版本不存在」。
   */
  @Test
  public void fallsBackToBundleWhenNoPlainArchiveExists() {
    String onlyBundle =
        "{\"packages\":[{\"id\":\"android-cmake\",\"architecture\":\"arm64-v8a\","
            + "\"filename\":\"android-cmake-build-tools-arm64-bundle-v1\",\"version\":\"4.1.2\","
            + "\"url\":\"https://example.invalid/b\",\"encrypted\":true,\"sha256\":\"bb\"}]}";

    PackageEntry picked = find(onlyBundle, versioned("arm64-v8a", "android-cmake", "4.1.2"));

    assertEquals("android-cmake-build-tools-arm64-bundle-v1", picked.getFilename());
    assertTrue(picked.getEncrypted());
  }

  /** 版本不存在仍要抛 IllegalArgumentException（旧行为不变）。 */
  @Test
  public void throwsWhenVersionMissing() {
    assertThrows(
        IllegalArgumentException.class,
        () -> find(CMakeShadowedManifest, versioned("arm64-v8a", "android-cmake", "9.9.9")));
  }

  /**
   * 非 CN 版的形状：上游 manifest 同一 (包名,架构,版本) 只有一条纯 tar.xz。
   * 加过滤后仍必须原样选中它——这是「不影响非 CN 版本」的守卫。
   */
  @Test
  public void upstreamSingleEntryStillSelected() {
    String upstreamCmake =
        "{\"packages\":["
            + "{\"id\":\"android-cmake\",\"architecture\":\"arm64-v8a\","
            + "\"filename\":\"cmake-aarch64-linux-musl.tar.xz\",\"version\":\"4.1.2\","
            + "\"url\":\"https://example.invalid/u.tar.xz\",\"encrypted\":false,\"sha256\":\"cc\"},"
            + "{\"id\":\"android-cmake\",\"architecture\":\"arm64-v8a\","
            + "\"filename\":\"cmake-v3.31.6-aarch64.tar.xz\",\"version\":\"3.31.6\","
            + "\"url\":\"https://example.invalid/v.tar.xz\",\"encrypted\":false,\"sha256\":\"dd\"}"
            + "]}";

    assertEquals(
        "cmake-aarch64-linux-musl.tar.xz",
        find(upstreamCmake, versioned("arm64-v8a", "android-cmake", "4.1.2")).getFilename());
    assertEquals(
        "cmake-v3.31.6-aarch64.tar.xz",
        find(upstreamCmake, versioned("arm64-v8a", "android-cmake", "3.31.6")).getFilename());
  }
}

/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

/*
 * Copyright 2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tom.rv2ide.plugins.tasks;

import java.util.Arrays;
import java.util.List;
import org.gradle.api.GradleException;
import org.gradle.util.GradleVersion;

class GradleVersionResolver {

  /**
   * 动态版本占位符常量。
   *
   * <p>原先从 Gradle 内部类 {@code org.gradle.api.tasks.wrapper.internal
   * .DefaultWrapperVersionsResources} 静态导入。**Gradle 9 删除了该类**（连
   * `org.gradle.api.tasks.wrapper.WrapperVersionsResources` 接口一起），其动态版本
   * 解析改由内置的 `org.gradle.api.tasks.wrapper.internal.GradleVersionResolver`
   * 承担，占位符收进 `DynamicVersion` 枚举。
   *
   * <p>这里改为内联字面量：值与 Gradle 8 的常量逐字相同（实测 javap 读出
   * latest / nightly / release-candidate / release-nightly），语义不变。
   * 本类只被 [IDEWrapperGenerator] 使用，而调用方传的是硬编码版本号（"7.4.2"），
   * 这些占位符分支在我们的构建里不会被走到。
   */
  private static final String LATEST = "latest";

  private static final String NIGHTLY = "nightly";
  private static final String RELEASE_NIGHTLY = "release-nightly";
  private static final String RELEASE_CANDIDATE = "release-candidate";

  /** 与 Gradle 8 `DefaultWrapperVersionsResources.PLACE_HOLDERS` 对应的集合。 */
  private static final List<String> PLACE_HOLDERS =
      Arrays.asList(LATEST, RELEASE_CANDIDATE, NIGHTLY, RELEASE_NIGHTLY);

  private GradleVersion gradleVersion;
  private String gradleVersionString = GradleVersion.current().getVersion();

  String resolve(String version) {
    if (version == null) {
      return GradleVersion.current().getVersion();
    }
    if (PLACE_HOLDERS.contains(version)) {
      // 占位符（latest / nightly / release-candidate / release-nightly）过去由
      // Gradle 注入的 TextResource 提供实际版本号，而该注入机制（WrapperVersionsResources）
      // 在 Gradle 9 已移除。本仓库的调用方传的始终是硬编码版本号（"7.4.2"），
      // 因此这里直接报错而不是静默返回占位符——宁可失败得明确。
      throw new GradleException(
          "Gradle 占位符版本 '" + version + "' 已不再支持（Gradle 9 移除了 WrapperVersionsResources"
              + " 注入机制）。请在 GradleWrapperGeneratorTask 里传入具体版本号。");
    }
    return version;
  }

  static boolean isPlaceHolder(String version) {
    return PLACE_HOLDERS.contains(version);
  }

  GradleVersion getGradleVersion() {
    if (gradleVersion == null) {
      gradleVersion = GradleVersion.version(resolve(gradleVersionString));
    }
    return gradleVersion;
  }

  void setGradleVersionString(String gradleVersionString) {
    if (!isPlaceHolder(gradleVersionString)) {
      this.gradleVersion = GradleVersion.version(gradleVersionString);
    }
    if (this.gradleVersionString != gradleVersionString) {
      this.gradleVersionString = gradleVersionString;
      this.gradleVersion = null;
    }
  }
}
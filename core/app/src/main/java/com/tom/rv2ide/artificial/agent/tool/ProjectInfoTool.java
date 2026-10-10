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

package com.tom.rv2ide.artificial.agent.tool;

import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import com.tom.rv2ide.projects.IProjectManager;
import com.tom.rv2ide.projects.IWorkspace;
import com.tom.rv2ide.projects.GradleProject;
import com.tom.rv2ide.projects.android.AndroidModule;
import com.tom.rv2ide.tooling.api.models.AndroidArtifactMetadata;
import com.tom.rv2ide.tooling.api.models.BasicAndroidVariantMetadata;
import com.tom.rv2ide.tooling.api.models.GradleTask;
import java.util.List;
import org.json.JSONObject;

/**
 * 查询项目结构：模块、变体、以及每个变体的**精确 Gradle 任务名**。
 *
 * <p><b>为什么需要它</b>：AI 调 {@code gradle_build} 时任务名只能靠猜（{@code :app:assembleDebug}
 * 对不对取决于模块名与变体），猜错就白跑一次构建。这里直接从 Gradle 同步下来的项目模型里
 * 取**真实存在的**任务名（{@code AndroidArtifactMetadata.assembleTaskName}）与变体列表，
 * 让模型一次拿对。
 *
 * <p>app 层工具（同 {@link GradleBuildTool}）：需要 {@code IWorkspace}，而 ai-tool 模块
 * 零 Android 依赖。
 */
public final class ProjectInfoTool extends BaseTool {

  /** 列出任务时的上限，避免把整个任务图倒进上下文。 */
  private static final int MAX_TASKS = 300;

  @Override
  public String getName() {
    return ToolNames.PROJECT_INFO;
  }

  @Override
  public String getDescription() {
    return "查询当前项目的结构：模块列表（Gradle 路径、类型）、Android 模块的构建变体、"
        + "每个变体的**精确 assemble 任务名**与 applicationId。"
        + "改代码/构建前先调它，可以把 module 名与任务名一次拿对，不用靠猜（猜错 gradle_build 会白跑）。"
        + "可选 tasks=true 额外列出任务名（较多，默认不列）。只读。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.GENERIC;
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
                .put(
                    "tasks",
                    new JSONObject()
                        .put("type", "boolean")
                        .put("description", "是否列出任务名（默认 false，任务可能很多）")));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    IWorkspace workspace;
    try {
      workspace = IProjectManager.getInstance().getWorkspace();
    } catch (RuntimeException e) {
      return error("无法获取项目工作区：" + e.getMessage());
    }
    if (workspace == null) {
      return error("当前没有已打开/已同步的项目。请先在编辑器中打开项目，等 Gradle 同步完成。");
    }

    boolean includeTasks = input.optBoolean("tasks", false);
    StringBuilder sb = new StringBuilder();
    try {
      GradleProject root = workspace.getRootProject();
      sb.append("根项目: ").append(root.getName()).append('\n');
      sb.append("目录: ").append(root.getProjectDir().getAbsolutePath()).append("\n\n");
    } catch (RuntimeException e) {
      // 项目模型未就绪时 root 取不到——如实说明而不是崩。
      return error("项目模型尚未就绪（是否还在 Gradle 同步中？）：" + e.getMessage());
    }

    List<GradleProject> subprojects;
    try {
      subprojects = workspace.getSubProjects();
    } catch (RuntimeException e) {
      return error("无法读取子项目列表：" + e.getMessage());
    }

    sb.append("模块（").append(subprojects.size()).append(" 个）：\n");
    for (GradleProject project : subprojects) {
      appendModule(sb, project, includeTasks);
    }
    return ok(ToolResult.truncateContent(sb.toString()));
  }

  private static void appendModule(StringBuilder sb, GradleProject project, boolean includeTasks) {
    String path = project.getPath() == null || project.getPath().isEmpty() ? project.getName() : project.getPath();
    sb.append("- ").append(path);
    if (project instanceof AndroidModule) {
      AndroidModule module = (AndroidModule) project;
      sb.append(" [").append(module.isApplication() ? "app" : "library").append("]");
      if (module.getNamespace() != null && !module.getNamespace().isEmpty()) {
        sb.append(" namespace=").append(module.getNamespace());
      }
      sb.append('\n');
      appendAndroidVariants(sb, module);
    } else {
      sb.append(" [gradle]\n");
    }
    if (includeTasks) {
      appendTasks(sb, project);
    }
  }

  private static void appendAndroidVariants(StringBuilder sb, AndroidModule module) {
    List<BasicAndroidVariantMetadata> variants = module.getVariants();
    if (variants == null || variants.isEmpty()) {
      sb.append("    变体: （尚未解析，可能还在同步）\n");
      return;
    }
    BasicAndroidVariantMetadata selected = module.getSelectedVariant();
    for (BasicAndroidVariantMetadata variant : variants) {
      AndroidArtifactMetadata artifact = variant.getMainArtifact();
      sb.append("    变体 ").append(variant.getName());
      if (selected != null && variant.getName().equals(selected.getName())) {
        sb.append("（当前选中）");
      }
      sb.append('\n');
      if (artifact != null) {
        if (artifact.getAssembleTaskName() != null) {
          // 直接给出真实任务名，模型可原样交给 gradle_build。
          sb.append("      assemble 任务: ").append(artifact.getAssembleTaskName()).append('\n');
        }
        if (artifact.getApplicationId() != null) {
          sb.append("      applicationId: ").append(artifact.getApplicationId()).append('\n');
        }
        sb.append("      minSdk: ").append(artifact.getMinSdkVersion()).append('\n');
      }
    }
  }

  private static void appendTasks(StringBuilder sb, GradleProject project) {
    List<GradleTask> tasks = project.getTasks();
    if (tasks == null || tasks.isEmpty()) {
      sb.append("    任务: （无）\n");
      return;
    }
    sb.append("    任务（").append(tasks.size()).append(" 个");
    if (tasks.size() > MAX_TASKS) {
      sb.append("，仅列前 ").append(MAX_TASKS);
    }
    sb.append("）: ");
    int shown = Math.min(tasks.size(), MAX_TASKS);
    for (int i = 0; i < shown; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(tasks.get(i).getName());
    }
    sb.append('\n');
  }
}

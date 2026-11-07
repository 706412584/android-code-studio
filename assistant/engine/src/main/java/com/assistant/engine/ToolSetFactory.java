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

package com.assistant.engine;

import com.tom.rv2ide.ai.tool.ArchiveTool;
import com.tom.rv2ide.ai.tool.AskUserQuestionTool;
import com.tom.rv2ide.ai.tool.FileCopyTool;
import com.tom.rv2ide.ai.tool.FileDeleteTool;
import com.tom.rv2ide.ai.tool.FileEditTool;
import com.tom.rv2ide.ai.tool.FileReadTool;
import com.tom.rv2ide.ai.tool.FileWriteTool;
import com.tom.rv2ide.ai.tool.GlobTool;
import com.tom.rv2ide.ai.tool.GrepTool;
import com.tom.rv2ide.ai.tool.HttpRequestTool;
import com.tom.rv2ide.ai.tool.ListDirectoryTool;
import com.tom.rv2ide.ai.tool.ShellBackendRegistry;
import com.tom.rv2ide.ai.tool.ShellExecuteTool;
import com.tom.rv2ide.ai.tool.TaskOutputTool;
import com.tom.rv2ide.ai.tool.TaskStopTool;
import com.tom.rv2ide.ai.tool.TodoUpdateTool;
import com.tom.rv2ide.ai.tool.ToolRegistry;
import com.tom.rv2ide.ai.tool.ToolSearchTool;
import com.tom.rv2ide.ai.tool.WebFetchTool;
import com.tom.rv2ide.ai.tool.memory.MemoryStore;
import com.tom.rv2ide.ai.tool.memory.MemoryUpdateTool;
import com.tom.rv2ide.ai.tool.skill.SkillRegistry;
import com.tom.rv2ide.ai.tool.skill.SkillTool;
import java.io.File;

/**
 * 组装本 App 可用的工具集。
 *
 * <p><b>只注册「不依赖 ACS 基础设施」的工具</b>。ACS 那边注册了 30+ 个工具，
 * 其中一半是构建 / 诊断 / 编辑器 / 设备控制——那些需要 Gradle Tooling、LSP、
 * Shizuku、uiautomator2，全在「暂不移植」范围内。这里注册的是纯文件 / 网络 /
 * shell / 记忆 / 技能类工具，它们只依赖 {@code core/ai-tool} 自己的实现。
 *
 * <p><b>为什么是一个静态工厂而不是分散在各处 new</b>：工具集是「这个 App 能做什么」
 * 的唯一真相。散落注册会让「提示词里列的工具」与「实际注册的工具」漂移，
 * 而漂移的表现是模型调用一个不存在的工具（静默失败）。
 */
public final class ToolSetFactory {

  private ToolSetFactory() {}

  /**
   * 构建工具注册表。
   *
   * @param home 工作区根目录（模型能访问的文件范围）
   * @param memoryFile 长期记忆存储文件；null 时不注册记忆工具
   * @param skillRoot 技能目录；null 时不注册技能工具
   * @param http 网络端口（网页抓取 / 通用 HTTP 请求）
   */
  public static ToolRegistry create(
      File home, File memoryFile, File skillRoot, AppHttpPort http) {
    return create(home, memoryFile, skillRoot, http, null, null);
  }

  /**
   * 带生成端点的完整版本。
   *
   * @param imageEndpoint 图片生成端点；null 时不注册 image_generation
   * @param videoEndpoint 视频生成端点；null 时不注册 video_generation
   */
  public static ToolRegistry create(
      File home,
      File memoryFile,
      File skillRoot,
      AppHttpPort http,
      com.tom.rv2ide.ai.tool.ImageGenerationEndpoint imageEndpoint,
      com.tom.rv2ide.ai.tool.VideoGenerationEndpoint videoEndpoint) {
    ToolRegistry registry = new ToolRegistry();

    // ---- 文件读写 ----
    // 这六个是 agent 干活的主力：读、写、改、删、移动、复制。
    registry.register(new FileReadTool());
    registry.register(new FileWriteTool());
    registry.register(new FileEditTool());
    registry.register(new FileDeleteTool());
    registry.register(new FileCopyTool());
    registry.register(new com.tom.rv2ide.ai.tool.FileMoveTool());

    // ---- 检索 ----
    registry.register(new GlobTool());
    registry.register(new GrepTool());
    registry.register(new ListDirectoryTool());

    // ---- 归档 ----
    registry.register(new ArchiveTool());

    // ---- shell ----
    ShellBackendRegistry shellBackends = new ShellBackendRegistry();
    shellBackends.register(new ProcessShellBackend());
    registry.register(new ShellExecuteTool(shellBackends));
    // 后台任务：shell_execute 支持 background=true 后，这两个用来读输出与停止。
    registry.register(new TaskOutputTool());
    registry.register(new TaskStopTool());

    // ---- 网络 ----
    if (http != null) {
      registry.register(new WebFetchTool(http));
      registry.register(new HttpRequestTool(http));
    }

    // ---- 待办清单 ----
    // 存储落在工作区根下的隐藏文件；与 ACS 的 FileTodoStateStore 同一思路
    // （按会话隔离的文件，避免两个会话互相覆盖）。
    if (home != null) {
      registry.register(
          new TodoUpdateTool(
              new com.tom.rv2ide.ai.tool.FileTodoStateStore(new File(home, ".assistant"))));
    }

    // ---- 长期记忆 ----
    if (memoryFile != null) {
      registry.register(new MemoryUpdateTool(new MemoryStore(memoryFile)));
    }

    // ---- 技能 ----
    if (skillRoot != null) {
      registry.register(new SkillTool(SkillRegistry.load(skillRoot)));
    }

    // ---- 提问 ----
    // 必须注册：模型在需求有歧义时需要它，否则只能瞎猜或反复确认文本。
    registry.register(new AskUserQuestionTool());

    // ---- 生成类 ----
    // 端点未配置齐全时不注册：注册了但调用必然报「未配置」，模型会反复尝试，
    // 浪费轮次。不注册时模型根本看不到这两个工具。
    if (http != null && imageEndpoint != null) {
      registry.register(new com.tom.rv2ide.ai.tool.ImageGenerationTool(http, http));
    }
    if (http != null && videoEndpoint != null) {
      registry.register(new com.tom.rv2ide.ai.tool.VideoGenerationTool(http));
    }

    // ---- 工具检索 ----
    // 工具数一多，模型容易被工具清单淹没。tool_search 让它按需查工具用法。
    registry.register(new ToolSearchTool(registry));

    return registry;
  }
}

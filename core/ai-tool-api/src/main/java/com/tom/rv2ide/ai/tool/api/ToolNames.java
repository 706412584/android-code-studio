/*
 * This file is part of AndroidCodeStudio.
 *
 * Ported from LineCode Pro (https://github.com/LangLang03/LineCodePro),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
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

package com.tom.rv2ide.ai.tool.api;

/**
 * 内置工具名称常量集中定义，供 AI 解析层和工具层共用，
 * 避免 AI 模块直接引用工具具体实现类。
 */
public final class ToolNames {
    private ToolNames() {}

    public static final String FILE_READ = "file_read";
    public static final String FILE_WRITE = "file_write";
    public static final String FILE_EDIT = "file_edit";
    public static final String FILE_DELETE = "file_delete";
    public static final String LIST_DIR = "list_dir";
    public static final String GLOB = "glob";
    /**
     * 按正则搜索文件内容。
     *
     * <p>与 {@link #GLOB} 的分工：glob 按**文件名**找文件，grep 按**内容**找。
     * 两者都属检索类，但一个回答「哪些文件叫这个名字」，另一个回答「哪一行写了这个」。
     */
    public static final String GREP = "grep";
    public static final String SHELL_EXECUTE = "shell_execute";
    public static final String AGENT = "agent";
    public static final String AGENT_PIPELINE = "agent_pipeline";
    public static final String AGENT_OUTPUT = "agent_output";
    public static final String TODO_UPDATE = "todo_update";
    public static final String MEMORY_UPDATE = "memory_update";
    /**
     * 向用户提选择题。
     *
     * <p>与其它工具的根本差异：它的执行结果是**用户给的**，不是程序算的。因此它必须
     * 阻塞 agent 循环直到用户作答——异步返回一个「稍后再说」对模型毫无意义，它要的是
     * 一个能据以继续的决定。
     */
    public static final String ASK_USER_QUESTION = "ask_user_question";
    /** 读取 skill 全文（渐进披露的按需加载端）。 */
    public static final String SKILL = "skill";
    /** 写入 / 删除 skill，让模型能自己沉淀踩过的坑。 */
    public static final String SKILL_WRITE = "skill_write";
    public static final String WEB_SEARCH = "web_search";
    public static final String WEB_FETCH = "web_fetch";
    /** 通用 HTTP 请求（任意方法/头/体），供调试接口、上传、带鉴权的调用使用。 */
    public static final String HTTP_REQUEST = "http_request";
    public static final String IMAGE_UNDERSTANDING = "image_understanding";
    public static final String IMAGE_GENERATION = "image_generation";
    /** 文生视频（异步任务 + 轮询，协议照 cc-haha media-gen）。 */
    public static final String VIDEO_GENERATION = "video_generation";
    // ---- 手机控制（真机测试闭环，经 Shizuku 走 adb 级权限，不依赖无障碍服务） ----
    /** 截取设备屏幕。 */
    public static final String PHONE_SCREENSHOT = "phone_screenshot";
    /** 按坐标点击。 */
    public static final String PHONE_CLICK = "phone_click";
    /** 按视图选择器点击（基于节点树定位）。 */
    public static final String PHONE_CLICK_VIEW = "phone_click_view";
    /** 滑动/拖拽。 */
    public static final String PHONE_SWIPE = "phone_swipe";
    /** 长按。 */
    public static final String PHONE_LONG_PRESS = "phone_long_press";
    /** dump 视图节点树。 */
    public static final String PHONE_VIEW_HIERARCHY = "phone_view_hierarchy";
    /** 全局动作（返回/主页/最近任务等）。 */
    public static final String PHONE_GLOBAL_ACTION = "phone_global_action";
    /** 向当前聚焦输入框输入文本。 */
    public static final String PHONE_INPUT_TEXT = "phone_input_text";
    /** 查询当前前台 Activity。 */
    public static final String PHONE_CURRENT_ACTIVITY = "phone_current_activity";
    /** 等待某个界面条件成立（如某 Activity/视图出现）。 */
    public static final String PHONE_WAIT_FOR = "phone_wait_for";
    /** 清除应用数据（`pm clear`）。 */
    public static final String PHONE_CLEAR_DATA = "phone_clear_data";
    /** 读取设备内存与帧统计（`meminfo` / `gfxinfo`）。 */
    public static final String PHONE_MEM_INFO = "phone_meminfo";
    /** 执行多步测试场景（步骤数组 + 期望断言）。 */
    public static final String PHONE_TEST_SCENARIO = "phone_test_scenario";
    /** 保存/比对截图基线。 */
    public static final String PHONE_BASELINE = "phone_baseline";
    /** 执行一个动作并连拍多帧（动作级截图回归）。 */
    public static final String PHONE_ACTION_CAPTURE = "phone_action_capture";

    /** CodeGraph 代码知识图谱：按符号查源码 / 调用关系 / 影响面（`acs-codegraph`）。 */
    public static final String CODEGRAPH = "codegraph";
    /** 两张截图逐像素对比并生成差异图。 */
    public static final String PHONE_SCREENSHOT_COMPARE = "phone_screenshot_compare";

    // ---- 项目 git 仓库（app 层工具，JGit 实现；读写分成两个工具）----
    /**
     * 只读查询：状态 / diff / 历史 / 分支 / 远程。
     *
     * <p>与 {@link #GIT_WRITE} 分开是因为权限层按**工具**决定分级：
     * {@code needsConfirmation} 无参数、只读模式的放行也只看分类，
     * 单个工具内部无法对部分 action 收紧，混在一起只能整包按写类处理。
     */
    public static final String GIT = "git";
    /** 写操作：暂存 / 提交 / 分支 / 丢弃 / 远程同步。授权粒度按 action 细分。 */
    public static final String GIT_WRITE = "git_write";

    private static final String CUSTOM_AGENT_PREFIX = "agentx_";
    private static final String CUSTOM_MCP_PREFIX = "mcpx_";

    /** 判断是否为扩展工具名称（自定义 Agent 或 MCP） */
    public static boolean isExtensionToolName(String name) {
        return name != null && (name.startsWith(CUSTOM_AGENT_PREFIX) || name.startsWith(CUSTOM_MCP_PREFIX));
    }

    /** 判断是否为自定义 Agent 扩展工具名称 */
    public static boolean isCustomAgentToolName(String name) {
        return name != null && name.startsWith(CUSTOM_AGENT_PREFIX);
    }

    /** 判断是否为自定义 MCP 扩展工具名称 */
    public static boolean isCustomMcpToolName(String name) {
        return name != null && name.startsWith(CUSTOM_MCP_PREFIX);
    }
}

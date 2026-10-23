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
    /**
     * 移动 / 重命名文件或目录。
     *
     * <p><b>为什么需要</b>：此前重命名只能 read → write → delete 三步，中间任一步失败
     * 都会留下两个半成品（或丢内容）。移动是重构里最高频的动作之一，必须是一个原子操作。
     */
    public static final String FILE_MOVE = "file_move";
    /** 复制文件或目录（目录递归）。 */
    public static final String FILE_COPY = "file_copy";
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
    /**
     * 读取内置终端（Termux）会话屏幕内容。
     *
     * <p>与 {@link #SHELL_EXECUTE} 互补：后者起独立进程、返回本次输出；本工具读的是
     * 用户在终端里手动跑的东西。只读。
     */
    public static final String TERMINAL_READ = "terminal_read";
    /**
     * 取回后台命令的输出与状态。
     *
     * <p>与 {@link #SHELL_EXECUTE} 的 {@code run_in_background} 配对：启动返回任务 id，
     * 本工具按 id 取回（可阻塞等待或非阻塞查看）。照 cc-haha 的 TaskOutput 语义。
     */
    public static final String TASK_OUTPUT = "task_output";
    /**
     * 停止一个运行中的后台任务。
     *
     * <p>照 cc-haha 的 TaskStop 语义：仅在任务确实失控/无用时才停，不要因为
     * 「已经看够了输出」就停——那会浪费一次已经投入的等待。
     */
    public static final String TASK_STOP = "task_stop";
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
    /**
     * 按需加载工具的发现入口：在延迟加载目录（当前是 MCP 工具）里按关键词检索，
     * 命中后把这些工具的完整定义放进后续每轮请求的 tools 数组。
     *
     * <p>照搬 Aharou {@code ToolSearchTool} 的语义：MCP 工具的定义每轮全量注入时，
     * 几个 server 就能吃掉几十万 token，而单轮实际只会用到其中一两个。
     */
    public static final String TOOL_SEARCH = "tool_search";
    /** 读取 skill 全文（渐进披露的按需加载端）。 */
    public static final String SKILL = "skill";
    /** 写入 / 删除 skill，让模型能自己沉淀踩过的坑。 */
    public static final String SKILL_WRITE = "skill_write";
    public static final String WEB_SEARCH = "web_search";
    public static final String WEB_FETCH = "web_fetch";
    /** 通用 HTTP 请求（任意方法/头/体），供调试接口、上传、带鉴权的调用使用。 */
    public static final String HTTP_REQUEST = "http_request";
    // 注：曾有一个 IMAGE_UNDERSTANDING = "image_understanding" 常量（LCP 移植时带过来的），
    // 但 ACS 从未实现该工具——图片理解走的是「主模型直接接收 image block」（file_read 图片、
    // phone_screenshot、浏览器截图都经 ToolResult.withImage），不需要独立工具。已删除该死声明。
    public static final String IMAGE_GENERATION = "image_generation";
    /**
     * 图像处理（游戏素材制作）：色键/亮度键抠图、裁剪、缩放、主色提取。
     *
     * <p>单工具 + action 分派（对齐 archive 的形态）：五个 action 共用一条
     * 「读图 → 像素处理 → 写 PNG → 回传」通路，拆成多个工具会各占一个工具位、
     * 且模型在「该用哪个」上徒增歧义。
     */
    public static final String IMAGE = "image_tool";
    /** 文生视频（异步任务 + 轮询，协议照 cc-haha media-gen）。 */
    public static final String VIDEO_GENERATION = "video_generation";
    // ---- 手机控制（真机测试闭环，经 Shizuku 走 adb 级权限，不依赖无障碍服务） ----
    /**
     * 影子屏：宿主上的无头虚拟显示屏（不在设备屏幕显示）。
     *
     * <p>移植自 Aharou（GPL-3.0）：在虚拟屏里启动 App、截图、点按、滑动、输入，
     * 全程不占用用户主屏。与 phone_* 工具的分工：那些操作**用户当前屏幕**，
     * 本工具操作离屏虚拟屏——做第三方 App 自动化测试优先用它。
     */
    public static final String VSCREEN = "vscreen";
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
    /** 对屏幕截图做 OCR，返回文字与屏幕像素包围盒（Canvas/游戏/WebView 等取不到文本节点的场景）。 */
    public static final String PHONE_OCR = "phone_ocr";
    /** uiautomator2 代理：Unicode 输入 / 等待消失 / 多点触控 / 滚动到元素 / 通用 jsonrpc 逃生舱。 */
    public static final String PHONE_UIAUTOMATOR = "phone_uiautomator";

    // ---- 无障碍（免 Shizuku/adb 的屏幕读取与操作）----
    /**
     * 用无障碍服务读取屏幕（元素树 / 找节点 / 截图 / 窗口 / 前台应用）。
     *
     * <p>与 `phone_screenshot` / `phone_view_hierarchy` 等的分工：那些经 shell 后端执行
     * （`screencap`、`uiautomator dump`），**需要 Shizuku**；本工具走无障碍服务，
     * **不需要任何 adb 级权限**，只需用户在系统设置里开启服务。
     * 能力取舍：shell 系覆盖任意应用且输出完整 XML；本工具受无障碍 API 限制
     * （如系统截图需 Android 11+）。两者都能用时优先 shell 系。
     */
    public static final String PHONE_A11Y = "phone_a11y";
    /**
     * 用无障碍服务操作屏幕（点击 / 滑动 / 系统键 / 输入文字）。
     *
     * <p>与只读的 {@link #PHONE_A11Y} 拆开的原因同 {@link #GIT}/{@link #GIT_WRITE}：
     * 权限层按工具分级，混在一起会让每次读屏都弹确认框。
     * 写动作会改变设备状态，需逐次确认。
     */
    public static final String PHONE_A11Y_WRITE = "phone_a11y_write";

    // ---- LogWire（被构建应用主动上报的日志通道，无 adb 权限也能读）----
    /**
     * 读取 LogWire 通道的日志（被构建应用经 AIDL 主动上报）。
     *
     * <p>与 `logcat_read` 的分工：本工具不执行任何 shell 命令，因此**不需要
     * Shizuku/Termux 权限**，但只覆盖「开启了 Logsender 且由本 IDE 构建安装」的应用；
     * `logcat_read` 覆盖任意应用但需要 shell 后端权限。只读，无副作用。
     */
    public static final String LOGWIRE_READ = "logwire_read";
    /**
     * 查询 LogWire 通道状态（Logsender 开关、连接状态、已知应用）。只读，免确认。
     *
     * <p>与 {@link #LOGWIRE_SET_ENABLED} 拆开的原因同 {@link #GIT}/{@link #GIT_WRITE}：
     * 权限层按工具分级，混在一起会让只读查询也弹确认框。
     */
    public static final String LOGWIRE_STATUS = "logwire_status";
    /**
     * 开关 Logsender（日志通道总开关）。
     *
     * <p>改变的是**后续所有构建的产物**（是否注入日志库），属持续影响，需确认。
     */
    public static final String LOGWIRE_SET_ENABLED = "logwire_set_enabled";

    // ---- 云编译（GitHub Actions；app 层工具，服务层在 cloudbuild 包）----
    /**
     * 触发 GitHub Actions 云编译并等待到终态（消耗 CI 资源，需确认）。
     *
     * <p>与 {@link #GITHUB_ACTIONS_STATUS} 拆开的原因同 {@link #GIT}/{@link #GIT_WRITE}：
     * 权限层按工具分级，查询应当免确认、只读放行，而触发必须逐次确认。
     */
    public static final String GITHUB_ACTIONS_BUILD = "github_actions_build";
    /** 查询最近一次 Actions 运行的状态（只读，免确认）。 */
    public static final String GITHUB_ACTIONS_STATUS = "github_actions_status";

    // ---- GitHub Issue / Pull Request（app 层工具，REST 在 cloudbuild 包）----
    /**
     * Issue 只读查询：列表 / 详情 / 评论。
     *
     * <p>与 {@link #GITHUB_ISSUE_WRITE} 拆开的理由同 {@link #GIT}/{@link #GIT_WRITE}：
     * 权限层按**工具**分级，混在一起会让「看一眼有哪些 issue」也弹确认框。
     */
    public static final String GITHUB_ISSUE = "github_issue";
    /** Issue 写操作：创建 / 评论 / 关闭 / 重新打开。需确认。 */
    public static final String GITHUB_ISSUE_WRITE = "github_issue_write";
    /** PR 只读查询：列表 / 详情。 */
    public static final String GITHUB_PR = "github_pr";
    /**
     * PR 写操作：创建 / 评论 / 关闭 / 合并。需确认。
     *
     * <p><b>合并单独说明</b>：merge 会把提交并进目标分支且无法用本地 git 回滚，
     * 是这几个动作里唯一不可逆的；权限规则按 action 区分，不靠「整个工具已确认」
     * 顺带放行。
     */
    public static final String GITHUB_PR_WRITE = "github_pr_write";

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

    // ---- IDE 自省（编辑器/语言服务器内部状态，通用编码 agent 无法获得）----
    /**
     * 编译诊断：经 IDE 语言服务器对文件做静态分析，返回带行列号的错误/警告。
     *
     * <p>通用 agent 只能靠跑一次构建才知道改对没有（分钟级）；这里复用 IDE 已建立的
     * 符号索引，一次分析是秒级。只读，无副作用。
     */
    public static final String DIAGNOSTICS = "diagnostics";
    /**
     * 读取 IDE 自身日志（进程内 logback 环形缓冲），用于排查 AI 工具层被吞掉的异常。
     *
     * <p>不是读文件也不是读设备 logcat：logback 只接了 `LogcatAppender`（无稳定日志文件），
     * 而经 shell 后端跑 logcat 是另一个进程、读不到本应用自身的行。
     */
    public static final String IDE_LOG_READ = "ide_log_read";
    /**
     * 内存分析：进程内存画像 + AI 助手各子系统规模（消息数/缓存/队列）。
     *
     * <p>用于卡顿与 OOM 排查（用户实测的 Markdown 表格 OOM 之后加的诊断口）。
     * 只读，无副作用。
     */
    public static final String MEMORY_ANALYSIS = "memory_analysis";
    /**
     * 布局预览：把 `res/layout/*.xml` 经真实 View inflate 渲染成图片回传模型。
     *
     * <p>让 AI 改完 UI 后能自己看一眼渲染结果（app 层工具，需 Android 与模块依赖）。
     */
    public static final String LAYOUT_PREVIEW = "layout_preview";
    /**
     * 内置浏览器：用系统 WebView 加载网页并读取内容/截图。
     *
     * <p>与 {@link #WEB_FETCH} 的差异：web_fetch 走 HTTP 客户端只拿 HTML 源文本
     * （不含 JS 渲染结果），本工具执行真实渲染后可取 innerText、跑 JS、截图——
     * 面向 SPA 与需要视觉确认的场景。只读浏览，不支持点击/表单交互。
     */
    public static final String BROWSER = "browser";
    /** 压缩 / 解压 zip（手机上处理 SDK/依赖包、打包、备份）。 */
    public static final String ARCHIVE = "archive";
    /**
     * 代码导航：经语言服务器跳定义 / 找引用。
     *
     * <p>按符号语义匹配，胜过文本 grep（同名符号不会被误判）。语言服务器已实现这两项，
     * 复用其符号索引即可。只读。
     */
    public static final String LSP_NAVIGATE = "lsp_navigate";
    /**
     * 项目结构查询：模块、Android 变体、每个变体的真实 Gradle 任务名。
     *
     * <p>让模型不用猜模块名/任务名（猜错会让 gradle_build 白跑一次）。只读。
     */
    public static final String PROJECT_INFO = "project_info";
    /**
     * 测试覆盖率查询：跑 kover 任务、解析报告，返回各模块覆盖率与未覆盖的类。
     *
     * <p>补的是「AI 改完代码后不知道哪里没测到」这个缺口。{@code gradle_build} 能跑
     * 测试任务，但只回「成功/失败 + 输出尾部」——覆盖率数字（数十万行 XML）读不到。
     */
    public static final String TEST_COVERAGE = "test_coverage";
    /**
     * 运行单元测试并解析 JUnit XML，返回「通过/失败数 + 失败用例的类名.方法名 + 断言消息」。
     *
     * <p>与 {@link #TEST_COVERAGE} 的分工：覆盖率回答「测到了多少」，本工具回答
     * 「挂了哪几个、为什么挂」。与 {@code gradle_build} 的分工：后者的结果只带
     * Failure 枚举与输出尾部，模型拿到「BUILD FAILED」后还得自己翻
     * {@code build/test-results/} 下的 XML。
     */
    public static final String RUN_TESTS = "run_tests";
    /**
     * 代码格式化：经语言服务器格式化源文件（就地写回）。
     *
     * <p>手机上无法手动对齐格式，用语言服务器的 formatter 比让模型猜缩进可靠。
     */
    public static final String FORMAT_CODE = "format_code";

    // ---- 编辑器会话（与用户编辑器状态交互，通用编码 agent 无法获得）----

    /**
     * 读取编辑器当前状态：当前文件、光标、选区、已打开文件、未保存修改。
     *
     * <p>只读。通用 agent 只能假设「磁盘内容就是用户看到的内容」，而编辑器缓冲可能
     * 领先磁盘（未保存修改）——这正是「构建用了旧内容」类问题的根源。
     */
    public static final String EDITOR_STATE = "editor_state";
    /**
     * 打开文件并定位到行/列（可选中范围），把用户带到代码位置。
     *
     * <p>只改编辑器视图状态（当前文件/光标/选区），不改文件内容。
     */
    public static final String FILE_OPEN = "file_open";
    /**
     * 保存全部编辑器缓冲到磁盘（与 {@link #EDITOR_STATE} 的「读」互补）。
     *
     * <p>写盘但不改内容，属工程状态操作；只读模式下拦截（磁盘写）。
     */
    public static final String SAVE_ALL = "save_all";

    // ---- IDE 原生能力（类型系统感知 / 项目模型）----

    /**
     * IDE 原生 Java 代码动作：类型系统感知的代码生成/重构（就地写回）。
     *
     * <p>比模型手写文本可靠：import / 泛型 / 缩进由编译器信息生成。
     */
    public static final String CODE_ACTIONS = "code_actions";
    /**
     * 构建变体：列出 Android 模块的变体 / 切换指定模块的变体（切换会触发重新同步）。
     *
     * <p>{@link #PROJECT_INFO} 只读报告变体；本工具补上「切换」。
     */
    public static final String BUILD_VARIANT = "build_variant";
    /**
     * 工作区符号搜索：按名称查找 Java 符号声明位置（file:line）。
     *
     * <p>与 {@link #LSP_NAVIGATE}（给定位置做导航）互补；与 {@link #CODEGRAPH}
     * （外部 CLI，依赖用户装索引）互补，走 IDE 内建能力。
     */
    public static final String WORKSPACE_SYMBOL = "workspace_symbol";

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

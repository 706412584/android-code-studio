# Android Code Studio — 项目规则

## 代码检索：优先使用 CodeGraph

本项目已建立 CodeGraph 语义索引（`.codegraph/`，7094 文件 / 14.6 万节点 / 41.1 万边）。
**检索代码时优先调用 CodeGraph MCP 工具，而不是 Grep/Glob/Read 逐个翻文件。**

理由：CodeGraph 返回符号级别的源码 + 调用关系，一次调用即可替代多轮 grep + read，
且结果基于已解析的引用图，比文本匹配准确。

### 两条硬性要求

1. **把 CodeGraph 返回的源码视为已读**——不要再 Read 同一文件去"确认"，那是重复劳动。
2. **不要用 grep 复核 CodeGraph 的结果**。它是预建索引，grep 循环只是在重做它已经做过的事。
   仅当怀疑索引过期时才验证（工具结果会带 staleness 提示）。

### 工具选择

| 需求 | 工具 |
|---|---|
| 按名字找符号（类/方法/字段） | `codegraph_search` |
| 理解一个功能区域：相关符号源码 + 调用路径 | `codegraph_explore` |
| 单个符号的源码 + 调用者/被调用者 | `codegraph_node` |
| 谁调用了它 | `codegraph_callers` |
| 它调用了谁 | `codegraph_callees` |
| 改这个符号会影响什么 | `codegraph_impact` |
| 项目结构总览 | `codegraph_files` |
| 索引状态 | `codegraph_status` |

### 典型场景

- 找实现位置 → 先 `codegraph_search`，拿到 `file:line` 后再按需 `Read` 该文件
- 理解模块如何协作 → `codegraph_explore "<自然语言描述>"`
- 改动前评估影响面 → `codegraph_impact <符号名>`，再 `codegraph_callers` 确认调用点
- 读某符号完整定义 + 上下文 → `codegraph_node <符号名>`（无需再 Read 整个文件）

### 何时不用 CodeGraph

以下情况直接用 Grep/Glob/Read：

- 搜索**非代码内容**：字符串资源、XML 属性值、注释文本、配置值、日志文案
- 按**文件路径/文件名**模式查找（用 Glob）
- 已知确切的 `file:line`，直接 Read
- 索引未覆盖的文件类型（`.pro`、`.properties` 等文本文件）
- 需要**逐字精确匹配**而非语义检索时

### 降级方案

若 MCP 工具不可用（会话启动时未加载），改用 CLI。
注意 CLI 子命令与 MCP 工具名不完全对应——**找符号是 `query` 不是 `search`**：

```bash
/d/npm-global/codegraph.cmd query <符号名>      # 对应 codegraph_search
/d/npm-global/codegraph.cmd explore <自然语言>  # 对应 codegraph_explore
/d/npm-global/codegraph.cmd node <符号名>       # 对应 codegraph_node
/d/npm-global/codegraph.cmd callers <符号名>    # 对应 codegraph_callers
/d/npm-global/codegraph.cmd callees <符号名>    # 对应 codegraph_callees
/d/npm-global/codegraph.cmd impact <符号名>     # 对应 codegraph_impact
/d/npm-global/codegraph.cmd affected <文件...>  # 无 MCP 对应，找受影响测试
/d/npm-global/codegraph.cmd files               # 对应 codegraph_files
/d/npm-global/codegraph.cmd status              # 对应 codegraph_status
```

> `codegraph` 不在 PATH 中，必须用上述完整路径。

## 已知陷阱

### vendored 依赖污染检索结果

`composite-builds/` 下约 3814 个文件是 vendored 的第三方源码
（javac、jdk-compiler、google-java-format、appintro、logback-android、javapoet 等），
它们**也在索引中**。核心自研代码只有约 1500 个文件。

后果：检索常见符号名（如 `onCreate`、`Builder`）时，结果会被第三方源码淹没。

应对：
- 关注**核心代码**时，在查询中带上模块前缀限定，例如
  `codegraph_search "rv2ide <符号名>"`，或查询后忽略 `composite-builds/` 路径的结果
- `codegraph_impact` / `affected` 的结果若大量落在 `composite-builds/`，属正常现象，需人工过滤

## 项目结构速查

- 包名 `com.tom.rv2ide`，Kotlin 多模块 + Gradle composite build
- `core/app` — 主 App（`IDEApplication.kt:82` 为入口），含 AI Agent、构建服务
- `core/{projects,indexing-*,lsp-*,resources,common}` — 项目模型、索引、LSP 抽象
- `editor/{api,impl,lexers,treesitter}` — 代码编辑器（sora-editor + tree-sitter）
- `java/lsp`、`xml/lsp` — Java / XML 语言服务器
- `tooling/*` — Gradle Tooling API 集成（`BuildService` 在 `core/projects/.../BuildService.kt:36`）
- `termux/*` — 内置终端
- `composite-builds/build-logic` — 构建逻辑；`composite-builds/build-deps`、`external` — vendored 依赖

## 构建

- AGP 8.13.0 / Kotlin 2.1.0 / Gradle 8.13 / JDK 17
- 依赖版本集中在 `gradle/libs.versions.toml`
- 本机无 Android SDK 完整环境时，优先依赖 CodeGraph 静态分析而非实际编译验证
- 构建命令必须带 `JAVA_HOME="C:/Users/70641/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2"`
  与 `--offline`；中文 Windows 控制台是 GBK，输出要过 `iconv -f GBK -t UTF-8 -c`

### 打包到实机（必读，别再重复踩）

`core/app/build.gradle.kts:90` 从**环境变量**读签名密码，而仓库内
`signing/signing-key.jks` 的密码只存在于 GitHub secret（`SIGNING_STORE_PASSWORD`），
本机无法还原。不带环境变量直接 `assembleDebug` 会在 `:core:app:packageDebug`
报 `keystore password was incorrect`。

**本机实机测试用的密钥**（黑鲨设备上装的包就是用它签的）：

```
C:/Users/70641/AppData/Local/Temp/debug-signing.jks
别名 AndroidCS / 密码 android / SHA-1 83abb7381685a06dbdae877ded4a207f4919c1da
```

```bash
JAVA_HOME="C:/Users/70641/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2" \
SIGNING_STORE_FILE="C:/Users/70641/AppData/Local/Temp/debug-signing.jks" \
SIGNING_STORE_PASSWORD=android SIGNING_KEY_PASSWORD=android SIGNING_KEY_ALIAS=AndroidCS \
./gradlew :core:app:assembleDebug --offline
```

产出 `core/app/build/outputs/apk/debug/` 下按 ABI 分包的 APK；黑鲨是 `arm64-v8a`。

**可以覆盖安装，不要卸载**（卸载会丢 3.7G 数据：2.3G Android SDK + 626M Termux rootfs
+ 配置）。判断签名是否一致**必须用 `apksigner verify --print-certs` 比对 APK 证书**，
不能看 `dumpsys package` 里的 `signatures=[xxxxxxxx]`——那是系统内部哈希，
与证书指纹无关，照它判断会得出「签名不同、必须卸载」的错误结论。

```bash
/d/android/platform-tools/adb.exe -s 9c18cb30 install -r -d <apk>
```

Git Bash 下 adb 的远端路径会被 MSYS 改写，涉及 `/sdcard` 等路径时先
`export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'`。

### 设备

| 设备 | adb serial | 系统 |
|---|---|---|
| 黑鲨 SKW-A0 | `9c18cb30` | Android 10 / SDK 29（**实机测试用这台**） |
| 另一台 | `adb-ad843de-OS8vOG._adb-tls-connect._tcp` | Android 16 / SDK 36 |

`minSdk=26`，`targetSdk=28`，`compileSdk=34`。

## AI Agent 模块

**存在两代实现并存，改动前必须先确认你要改的是哪一代。** 计划与进度见
`docs/ai-agent-port/{PLAN,PROGRESS,DETAILS,P0-1-design}.md`（PROGRESS.md 是最新的进度锚点）。

### 旧路径（经典模式，仍是默认）

`core/app/src/main/java/com/tom/rv2ide/artificial/`

- 6 个 provider（Gemini/OpenAI/Anthropic/Grok/DeepSeek/LocalLLM），各为独立类实现 `AIAgent` 接口，
  由 `AIAgentRegistry` 注册、`AIAgentManager` 调度
- 协议核心：`WritingRules.kt` 定义的 `FILE_TO_MODIFY:` 标记 + `SnippetParser` 解析
- 上下文注入为**全项目文件全量灌入**（`ProjectData.showProjectTree` + `readRelevantFiles`），
  无检索、无裁剪、无 token 预算控制
- 权限开关在 `AIAgentManager` 构造时被强制设为 `write=true, confirm=false`（`AIAgentManager.kt:57-58`），
  `AIPermissionDialog` 的确认弹窗当前无调用点
- 一次请求只生成文本并按标记写文件，无多轮工具调用

### 新路径（工具调用 agent，架构上已取代旧路径）

四个**纯 Java、零 Android 依赖**模块，因此可在 JVM 上单测（563 条测试全绿）：

| 模块 | 职责 |
|---|---|
| `core/ai-tool-api` | 工具契约：`ToolCall`/`ToolResult`/`ToolInfo`/`ToolNames`/`ToolCallTextParser`/`ErrorLog`（含脱敏） |
| `core/ai-protocol` | 协议层：OpenAI 兼容 + Anthropic Messages、重试、流式解析、reasoning 策略 |
| `core/ai-tool` | 工具执行：注册表、执行器、权限判定、文件工具、shell 抽象、MCP/memory/skill |
| `core/ai-agent` | 循环本体 `AgentSession` + 会话持久化 + 上下文裁剪与压缩 |

app 层接入点：

- `artificial/agent/AgentOrchestrator.java` — 装配全部工具并驱动一次运行
- `artificial/agent/ProviderPresets.java` — **14 个服务商预设，新增同类服务商只需加一行**
- `artificial/agent/AgentToolSettings.java` — 权限/后端/模式偏好；默认 `PERMISSION_CONFIRM`
- `artificial/agent/FloatingAssistantView.kt` — 项目界面悬浮助手（直接走新路径）
- `handlers/AgentRequestHandler.kt` — ChatFragment 的 agent 模式渲染器
- `preferences/aiAgentPrefExts.kt` — 设置项

新路径已具备（旧路径没有的）：多轮工具调用循环、构建→安装→启动→读日志闭环、
token 预算与历史压缩、JSONL append-only 会话持久化、三级危险工具授权（全局/规则/本次运行）、
diff 回滚、MCP 客户端、长期记忆、Skill、子 agent、自定义 agent、斜杠命令、提示词模板、4 种对话模式。

### 关键陷阱

- **新路径默认未启用**：`ChatFragment.kt:184` 的发送按钮走旧 `AIRequestHandler`，
  需**长按**执行按钮才切到 agent 模式（`AgentToolSettings.KEY_AGENT_MODE`）。
  主屏悬浮助手则直接用新路径。
- **`ConversationStore` 用 JSONL 而非 SQLite**：ACS 全仓库无任何数据库设施，
  会话日志是 append-only JSONL（`filesDir/ai/conversations`），刻意不引入 Room。
- **上下文裁剪在循环内、压缩在入口**：裁剪每轮都做（消息随轮次增长），
  压缩需条目序号且结果必须落盘，只能做一次。两条消息不可裁：系统提示词与**本次用户请求**。
- **`ProviderPresets.DEFAULT_PROVIDER_ID` 是 `deepseek`**：协议层只实现了
  `OPENAI_COMPATIBLE` 与 `ANTHROPIC_MESSAGES` 两种，Gemini 自有协议未实现，
  因此不能把默认值指向它（否则首次使用者配好密钥也发不出消息）。

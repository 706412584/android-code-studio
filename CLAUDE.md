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

## 移植前必做：先查有没有现成实现

**这条是硬性要求，跳过它已经造成过实际损失。**

背景：移植 cc-haha 的重试机制时，我没先检索就派 agent 从头实现了一套
（`BackoffPolicy` / `RetryPolicy` / `StreamIdleWatchdog`）。做完才发现仓库里
**早就有一版**（`RetryBackoff` / `ModelRetryDecision` / `StreamWatchdog`）——
虽然是未完成品（无调用点、无测试），但 `StreamWatchdog` 已经接线在用。
结果多出一套重复实现，还得回头判断该留哪套。

**动手写（或派 agent 写）任何"新"组件之前，先做这三步检索：**

```bash
# 1) 按功能关键词搜符号名（不只搜英文，也搜中文注释里的说法）
/d/npm-global/codegraph.cmd query <Retry|Backoff|Watchdog|Compress|Attachment...>

# 2) 按可能的名字直接列目录，看有没有同族文件
ls core/<模块>/src/main/java/**/ | grep -i <关键词>

# 3) 找"看起来已实现但没人调用"的半成品
grep -rn "<候选类名>" --include=*.java core/ | grep -v "\.java:"
```

第 3 步最容易漏，也最值钱：**移植半途而废的代码往往没有调用点**，
光看目录名容易以为是完整的。判断标准是「有没有调用点 + 有没有测试」，
两者皆无就是死代码。

**复用优先于重写**：参考项目（LCP / cc-haha）已有对应实现的，优先照搬其语义，
只改必要的适配层。自己重写不仅浪费，还容易在细节上踩参考项目已经踩过的坑
（例如 `RetryBackoff` 把 `Retry-After` 截断到 32s 上限——而 cc-haha 的语义是
`Retry-After` 优先且**绕过**上限）。

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
D:/android/keys/acs-debug-signing.jks          <- 正式存放位置（持久）
C:/Users/70641/AppData/Local/Temp/debug-signing.jks  <- 原位置（临时目录，会被清理）
别名 AndroidCS / 密码 android / SHA-1 83abb7381685a06dbdae877ded4a207f4919c1da
```

⚠️ **这把密钥全盘只有这一份，丢了就无法再构建能覆盖安装的 APK**，
只能卸载重装并丢掉设备上 3.7G 数据。它原先只存在于 Windows 临时目录
（`AppData/Local/Temp`），会被磁盘清理/存储感知删除，因此已复制到
`D:/android/keys/`（该目录不在任何 git 仓库内，不会被误提交）。

它**不是** `signing/signing-key.jks`（仓库内那把是 upstream 官方密钥，密码只在
GitHub secret 里，本机无法还原），也**不是** `orangeplayer/app/smlieapp.jks`
（那把 SHA-1 是 `B1F50B31…`，是多个无关项目共用的模板密钥）。

```bash
JAVA_HOME="C:/Users/70641/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2" \
SIGNING_STORE_FILE="D:/android/keys/acs-debug-signing.jks" \
SIGNING_STORE_PASSWORD=android SIGNING_KEY_PASSWORD=android SIGNING_KEY_ALIAS=AndroidCS \
./gradlew :core:app:assembleDebug --offline
```

Release 包同理，但要额外跳过 lint 并补一个缺失目录（CI 里也有这一步）：

```bash
mkdir -p core/app/build/intermediates/l8_art_profile/release/l8DexDesugarLibRelease && \
touch core/app/build/intermediates/l8_art_profile/release/l8DexDesugarLibRelease/baseline-prof.txt
# 然后 ./gradlew :core:app:assembleRelease --offline -x lintVitalRelease
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

## 仓库与推送

**GitHub 优先且默认**：

```
origin  https://github.com/706412584/android-code-studio.git   ← 主仓库，日常推这里
gitee   https://gitee.com/wu-yongchengsvip_admin/android-code-studio.git   ← 镜像
```

`dev` 的上游是 `origin/dev`，直接 `git push` 即可。Gitee 只在需要时手动推
（`git push gitee dev`），**不要配 Gitee 的网页端镜像同步**——那会与本地
`gitee` remote 的推送互相打架。

推 `dev` 会触发 `asm_build.yml`（构建 APK 并上传 artifact，无发布动作），
属正常。

### Gitee 的两个用途

1. **代码镜像**（上面那个仓库）
2. **CodeGraph 组件包托管** —— 应用内下载走 Gitee Release 而非 GitHub：
   国内设备常挂代理，实测 Termux 直连 GitHub 超时。Gitee Release 附件
   **单文件上限 100MB**，精简包 12.8MB 余量充足。
   上传：`tools/publish-codegraph-package.ps1 -Repo wu-yongchengsvip_admin/android-code-studio`

**Gitee 令牌**：`%USERPROFILE%\.gitee_token`。注意 Gitee 的令牌可被**限定到单个
仓库**，此时访问范围外的仓库 API 会返回 **404**（不是 403），而网页仍是 200 ——
别被这个误导成「仓库不存在」。诊断方法：同一 URL 带令牌 404、不带令牌 200，
就是令牌范围问题。

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

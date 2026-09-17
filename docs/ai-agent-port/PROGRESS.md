# AI Agent 移植 — 进度快照

> **用途**：跨会话的进度锚点。每次开工先读本文确认当前状态，收工前更新。
> 计划与缺口清单见 [`PLAN.md`](./PLAN.md)，逐项实现细节见 [`DETAILS.md`](./DETAILS.md)。
>
> **本次快照**：2026-09-17（分支 `dev`，最近提交 `e32b20a`）

---

## 1. 一句话状态

**工具循环已跑通（W0–W5 完成），UI 渲染层与数据层地基未开始。**
agent 已能在真机上完成「读文件 → 改文件 → 构建 → 安装 → 启动 → 读日志」闭环，
但对话界面仍是纯文本 `statusText`，无消息模型、无持久化、无工具卡片。

覆盖率约 **25%**（已移植约 115 / LCP 相关 440–470 文件）。

---

## 2. 已完成（W0–W5）

### 2.1 四个纯 Java 模块（不依赖 Android，可 JVM 单测）

| 模块 | 主源码 | 行数 | 内容 |
|---|---|---|---|
| `core/ai-tool-api` | 15 | 1517 | 工具契约：`ToolCall`/`ToolResult`(50KB 中段截断)/`ToolInfo`/`ToolNames`/`ToolCallTextParser`/`ErrorLog` |
| `core/ai-protocol` | 50 | 5461 | 协议层：OpenAI 兼容 + Anthropic Messages、重试、流式解析、7 个 reasoning 策略、`SimpleHttpClient`/`UrlPolicy` |
| `core/ai-tool` | 27 | 3506 | 工具执行：注册表(RW 锁)、执行器(错误即结果)、权限判定、6 个文件工具、shell 抽象、`BuildErrorExtractor`、`ApkFreshnessCheck` |
| `core/ai-agent` | 4 | 636 | 循环本体：`AgentEvent`/`AgentSession`/`AgentRunResult`/`AgentPromptBuilder` |

**合计 96 个主源文件 / 11120 行**，全部零 Android 依赖——这是移植期能快速验证的关键。

### 2.2 app 层接入

`core/app/src/main/java/com/tom/rv2ide/`：

| 文件 | 作用 |
|---|---|
| `artificial/agent/AgentOrchestrator.java` | 装配 10 个工具（6 文件 + shell + 构建闭环四件套） |
| `artificial/agent/AgentModelConfigs.java` | 6 个 provider 端点（**硬编码**，见 P1-12） |
| `artificial/agent/AgentToolSettings.java` | SharedPreferences `ai_agent_tools`；默认 `PERMISSION_CONFIRM`（比旧的强制 `write=true` 更安全） |
| `artificial/agent/TermuxShellBackend.java` | 复用内置终端；`bindService` + `LocalBinder`；轮询 `err` 文件作完成信号 |
| `artificial/agent/ShizukuShellBackend.java` | adb 级权限；`IShizukuService.newProcess` |
| `artificial/agent/tool/GradleBuildTool.java` | 构建；`isBuildInProgress()` 互斥；超时 `cancelCurrentBuild()` |
| `artificial/agent/tool/InstallApkTool.java` | `SilentInstaller`(120s latch)，权限被拒时降级 FileProvider intent，**不谎报成功** |
| `artificial/agent/tool/LaunchAppTool.java` | 主线程 Handler+latch（`flashError` 是 Toast） |
| `artificial/agent/tool/LogcatReadTool.java` | `logcat -d -v threadtime -t N [--pid=X]` |
| `handlers/AgentRequestHandler.kt` | 工具事件桥接到 UI；已加 try/finally（修过"按钮永久禁用"） |
| `fragments/ChatFragment.kt` | 长按发送按钮切换新旧路径；默认仍走旧路径 |
| `preferences/aiAgentPrefExts.kt` | 设置项：agent 开关、权限模式、shell 后端、自定义端点 |

### 2.3 验证状态

**单元测试：58 项，0 失败，1 跳过**（跳过项是联网网关测试，需 `-Dai.live.apiKey=...`）

| 模块 | tests | failures | skipped |
|---|---|---|---|
| `ai-tool-api` | 5 | 0 | 0 |
| `ai-protocol` | 4 | 0 | 0 |
| `ai-tool` | 37 | 0 | 0 |
| `ai-agent` | 12 | 0 | 1 |

**真机验证通过**（黑鲨 SKW-A0 / Android 10 / arm64-v8a）：
文件读写、shell 执行、构建 → 安装 → 启动 → 读日志全链路。

**设备现状**（2026-09-17 实测）：

| 设备 | ACS 版本 | Shizuku |
|---|---|---|
| 黑鲨 SKW-A0（Android 10） | `1.0.0+gh.r04`（09-17 14:00 更新） | **已安装**（`moe.shizuku.privileged.api`），**服务未运行** |
| 一加 PJA110（Android 16） | `1.0.0+gh.r04` | 未安装 |

> 注：旧任务 #14（"遗留项：Shizuku/构建闭环未真机验证"）记录的阻碍是"设备未装 Shizuku"，
> 该前提已不成立——黑鲨上已装，只是服务未启动。因此 ShizukuShellBackend 的**真机端到端仍未验证**，
> 但阻碍已从"缺应用"变为"需启动服务后重测"。

### 2.4 已修的真实缺陷（6 个，均为真机跑出来的）

单测发现不了这些，后续阶段结束务必真机跑一遍。

1. **提示词诱导模型弃用原生工具调用** —— 无条件注入 XML 兜底格式说明，模型改用手写 XML 且常残缺。改为**仅协议不支持原生工具时注入**；实测成功率 1/6 → 8/8。
2. **网关 `index:-1` 处理** —— 部分网关把 `tool_calls` 的 `index` 发成 -1 且首帧无 `function.name`，按 index 归并会挤进同一 builder。改为按调用边界切分。
3. **按钮永久禁用** —— `AgentRequestHandler` 缺 try/finally。
4. **构建错误不可见** —— `TaskExecutionResult` 只带 `Failure` 枚举，模型只能盲猜（实测烧 30 次工具调用）。已加 `BuildOutputBuffer` + `BuildErrorExtractor`。
5. **模型谎报成功** —— 模型未重新构建却称"构建成功、APK 已生成"。已加 `ApkFreshnessCheck` 独立核验产物时间戳。
6. **工具名别名** —— 模型有 `read`/`write`/`edit` 先验，与 `file_read` 不匹配。已加 `ToolRegistry` 别名归一。

---

## 3. 未提交改动（工作区）

`git status` 过滤掉 build 产物后的真实改动：

**已修改（4 个，属"APK 新鲜度核验"这一功能，未提交）**

| 文件 | 改动 |
|---|---|
| `core/projects/.../builder/BuildService.kt` | 接口新增 `lastBuildOutcome: BuildOutcome?` + `recordRejectedBuildAttempt()` |
| `core/app/.../services/builder/GradleBuildService.kt` | 记录构建开始时间与结果；构建中清空上轮结论 |
| `core/app/.../agent/tool/GradleBuildTool.java` | 构建失败时记录被拒尝试，使既有 APK 判定为陈旧 |
| `core/app/.../agent/tool/InstallApkTool.java` | 安装前校验 APK 时间戳晚于构建开始时间 |

**新增未跟踪**

- `core/ai-tool/.../ApkFreshnessCheck.java` + `ApkFreshnessCheckTest.java`（5 项测试，已跑绿）
- `docs/ai-agent-port/`（本文档所在目录）
- `CLAUDE.md`（CodeGraph 检索规则）
- `core/ai-{protocol,tool-api,tool}/port.sh`（移植脚本，**建议删除或移出仓库**）
- `.codegraph/`（**390MB 索引库，必须加进 `.gitignore`** —— 当前未加）

---

## 4. 待办 15 项

任务定义在**旧会话**的任务目录（`~/.claude/tasks/6a437c2c-.../{15..29}.json`），
本会话的 `TaskList` 为空——需要迁移或按本表重建。

### P0 — 缺了 agent 能力不完整

| # | 任务 | 状态 | 阻塞于 | 关键依赖说明 |
|---|---|---|---|---|
| 15 | P0-1 会话持久化与多会话 | pending | — | **所有 P0 的地基** |
| 16 | P0-2 上下文管理与压缩 | pending | 15 | 压缩需有历史 |
| 17 | P0-3 权限确认分级与持久化 | pending | — | 当前只有全局布尔 |
| 18 | P0-4 Diff 回滚与审查 | pending | 15 | 移植时主动砍掉了 `revertDiff`/`setReview` |
| 19 | P0-5 MCP 客户端 | pending | — | 纯客户端 HTTP JSON-RPC，可独立做 |
| 20 | P1-9 会话 UI 渲染层 | pending | 15 | **最大缺口** |
| 21 | P0-7 提示词模板系统 + 聊天模式 | pending | — | 占位符机制可先做，收益立竿见影 |
| 22 | P0-6 补齐内置工具 | pending | 20, 23, 24 | 高优先级部分(todo/web)**实际无依赖** |
| 23 | P0-8 长期记忆 + 本地 RAG | pending | 15 | |

### P1 / P2

| # | 任务 | 状态 | 阻塞于 |
|---|---|---|---|
| 24 | P1-11 子 agent / pipeline | pending | — |
| 25 | P1-12 服务商预设表与模型目录 | pending | — |
| 26 | P1-13 Skill 系统 | pending | — |
| 27 | P1-10 消息操作与导出 | pending | 20 |
| 28 | P1-14 自定义 Agent 扩展 + P1-15 Slash 命令 | pending | — |
| 29 | P2 收尾项（日志/归档/代理/输入/主题） | pending | — |

> **依赖修正记录**：任务 #22 最初被设为 `blockedBy=[20,23,24]`，但 `todo_update`/`web_fetch`/`web_search`
> 三个工具的实现本身不需要 UI 或记忆，已放宽。只有卡片渲染依赖 #20。

### 依赖图（决定实施顺序）

```
#15 会话持久化 ──┬──> #16 上下文压缩
                 ├──> #20 会话 UI（渲染需要消息模型）
                 ├──> #18 Diff 回滚
                 └──> #23 长期记忆（记忆需要会话索引）
#17 权限确认 ────┴──> #20（确认交互在卡片里）
#18 Diff 回滚 ───────> #20（回滚按钮在卡片里）
#19 MCP ─────────────> 独立（但卡片需能渲染 MCP 工具）
```

**关键结论**：`#15/#16/#17/#18` 与 `#20` 强耦合。逐项做会反复改同一批 UI 代码，
**应合并为两次改动**：先"数据层地基"（#15+#16），再"渲染层+交互"一次做完（#20+#17+#18）。

---

## 5. 建议的下一步

按 `PLAN.md` 的阶段划分，当前处在**阶段一（数据层地基）的起点**。

### 立即（可选，二选一）

- **A. 先提交现有成果**：`ApkFreshnessCheck` 那批改动已验证（5 测试绿），且与后续工作正交。
  提交前需处理：`.codegraph/` 加 `.gitignore`、`port.sh` 决定去留。
- **B. 直接开始 #15**：建立 `conversations` + `messages` 最小集，跑通"重启不丢消息"。

### 未验证项（做后续工作时应顺带补齐）

| 项 | 状态 |
|---|---|
| ShizukuShellBackend 端到端 | 黑鲨已装 Shizuku，**需启动服务后重测** |
| 构建闭环四件套在真机的完整 agent 驱动 | 四件套各自验证过，**未经 agent 串起来跑** |
| agnes 网关端到端 | 有 key 与端点，`AgentLiveGatewayTest` 默认跳过，未跑 |
| `AIHistoryFragment` 与新 agent 循环 | 有 UI 骨架，走**旧** `AIAgentManager` 路径，与新循环不通 |

### 已知可复用资产（避免重复造）

- `libs.versions.toml:20,95-98` 已声明 **markwon 4.6.2**，但**代码零引用** → 做 Markdown 渲染直接用
- `AIHistoryFragment` 可复用为会话列表入口，但需接新数据源
- ACS 编辑器组件（sora-editor）可复用代码高亮

---

## 6. 已知风险（延续 `PLAN.md` 第 6 节）

| 风险 | 状态 |
|---|---|
| **模型谎报成功** | 已加 `ApkFreshnessCheck`，但其他环节也可能存在；UI 层必须显示真实工具输出而非模型自述 |
| **小模型工具调用不稳定** | 已提升到 8/8，但**换模型需重测** |
| **LCP UI 层依赖自定义主题** | `ui-theme/LineTheme.java` 被工具卡片依赖，做 #20 时绕不开 |
| **`buildStartedAtMs` 被赋值但未读取** | `GradleBuildService.kt` 里该字段目前是死代码，`lastBuildOutcome` 走的是 `BuildOutcome` 记录的时间戳。提交前应清理或统一 |
| `.codegraph/` 390MB 未忽略 | 一旦误提交会污染仓库 |

---

## 7. 环境与构建（复现用）

```bash
# 必须 JDK 21（CI 用 21；系统 JDK 17 会让 ksp 报 JVM target 不一致）
JAVA_HOME="C:/Users/70641/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2" \
SIGNING_STORE_FILE="C:/Users/70641/AppData/Local/Temp/debug-signing.jks" \
SIGNING_STORE_PASSWORD=android SIGNING_KEY_PASSWORD=android SIGNING_KEY_ALIAS=AndroidCS \
./gradlew :core:app:assembleDebug --offline

# 单测（本文档的数字来源）
JAVA_HOME="<jdk21>" ./gradlew \
  :core:ai-tool-api:test :core:ai-protocol:test :core:ai-tool:test :core:ai-agent:test --offline
```

- 切换 JDK 后必须 `./gradlew --stop`，否则旧 daemon 残留会报 `Internal compiler error`
- `--offline` 用于跳过 `generateGradleWrapper` 的 URL 校验（代理会破坏 HEAD 请求）
- 控制台是 GBK：管道输出需 `iconv -f GBK -t UTF-8`
- adb 路径需 `MSYS_NO_PATHCONV=1`

---

## 8. 相关文档

- [`PLAN.md`](./PLAN.md) — 总计划：缺口清单（P0/P1/P2/不移植）、依赖关系、5 阶段顺序、移植原则
- [`DETAILS.md`](./DETAILS.md) — 逐项细节：LCP 文件位置、ACS 现状、移植建议、验收标准
- 项目根 [`CLAUDE.md`](../../CLAUDE.md) — 代码检索约定（优先 CodeGraph）

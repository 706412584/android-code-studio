# AI Agent 能力移植总计划

把参考项目 **LineCodePro**（下称 LCP，`D:\软件\LineCodePro`，725 个 Java 文件）
的 AI 助手能力移植到 **AndroidCodeStudio**（下称 ACS）。

> 本文是**总计划**：范围、优先级、依赖关系、验收标准。
> 逐项的实现细节、文件清单、移植要点见 [`DETAILS.md`](./DETAILS.md)。
> **当前进度快照（含未提交改动、设备状态、下一步）见 [`PROGRESS.md`](./PROGRESS.md)。**

---

## 1. 当前进度

### 已完成并验证

4 个新增纯 Java 模块（约 105 文件，不依赖 Android，可 JVM 单测）+ app 层接入（10 文件）：

| 模块 | 文件数 | 内容 |
|---|---|---|
| `core/ai-tool-api` | 16 | 工具契约：`ToolCall` / `ToolResult` / `ToolInfo` / `ToolNames` / `ToolCallTextParser` / `ErrorLog` |
| `core/ai-protocol` | 51 | 协议层：OpenAI 兼容 + Anthropic Messages、重试、流式解析、7 个 reasoning 策略 |
| `core/ai-tool` | 32 | 工具执行：注册表、执行器、权限判定、6 个文件工具、shell 抽象 |
| `core/ai-agent` | 6 | 循环本体：`AgentEvent` / `AgentSession` / `AgentPromptBuilder` |

app 层（`core/app/.../artificial/agent/`、`handlers/AgentRequestHandler.kt`）：
- `AgentOrchestrator` 装配 10 个工具
- `TermuxShellBackend`（应用自身 uid）+ `ShizukuShellBackend`（adb 级权限）
- 运行测试闭环四件套：`gradle_build` → `install_apk` → `launch_app` → `logcat_read`

**真机验证通过**（黑鲨 SKW-A0 / Android 10 / arm64-v8a）：文件读写、shell 执行、
构建 → 安装 → 启动 → 读日志全链路。

58 项单元测试通过（0 失败 / 1 跳过；跳过项是需 API key 的联网网关测试）。
最新实测数字见 [`PROGRESS.md`](./PROGRESS.md)。

### 覆盖率

- LCP 与 AI 助手核心能力相关的文件约 **440–470** 个
- 已移植约 **115** 个 → **覆盖率 ≈ 25%**
- **缺口约 320–350 个文件**

> 注意：文件数只反映工作量量级，不等于价值比例。真正的体验差距主要在 UI 渲染层（P1-9），
> 那是单个模块就占 40+ 文件的量。

---

## 2. 缺口清单（按优先级）

### P0 — 缺了 agent 能力不完整

| # | 功能 | LCP 位置 | ACS 状态 |
|---|---|---|---|
| P0-1 | **会话持久化与多会话** | `data/.../db/LineCodeDatabase.java`、`LineCodeSchema.java`（22 张表）、`repository/ConversationRepository.java`(374行)、`mvp/ConversationPersistenceController.java`(252行) | 完全缺失。`ChatFragment` 用内存字符串；`AIHistoryFragment` 有 UI 骨架但走旧的 `AIAgentManager` 路径 |
| P0-2 | **上下文管理（token 预算 + 压缩）** | `feature-model/.../context/ContextManager.java`(195行)、`ContextCompactionService.java`(557行)、`TokenUsageTracker.java`、`mvp/ContextCompactionController.java`(749行) | 完全缺失。`AgentSession` 无历史累积（单轮）；`AgentPromptBuilder` 只拼工具列表 |
| P0-3 | **权限确认 UI + 规则持久化** | `ui/component/ToolApprovalView.java`（始终允许/本次允许/拒绝）、`mvp/ToolConfirmationController.java`(361行)、`data/repository/CommandPermissionRepository.java` | 部分。只有 readonly/confirm/auto 三档 + 单次 AlertDialog，无"始终允许"分级持久化 |
| P0-4 | **Diff 回滚与审查** | `data/repository/DiffRepository.java:61-111`（`revertDiff`/`markReverted`/`setReview`）、`data/service/FileRestorer.java`、`mvp/ToolReviewController.java`(209行) | 移植时砍掉了回滚/审查，`DiffStore` 仅剩 `recordDiff`/`getDiffChain`，且 `InMemoryDiffStore` 不持久化 |
| P0-5 | **MCP 客户端** | `data/repository/McpExtensionRepository.java`(266行)、`feature-tool/.../CustomMcpHttpTool.java`(191行)、`extension_mcps` 表、`mvp/McpSettingsController.java` | 完全缺失（`ToolNames` 里有 `mcpx_` 常量，无实现）。是**纯客户端**（HTTP JSON-RPC） |
| P0-6 | **内置工具缺口** | `feature-tool/.../builtin/` | 缺 `todo_update`、`memory_update`、`web_fetch`、`web_search`（6 个 provider）、`agent`、`agent_pipeline`、`agent_output`、`image_understanding`、`image_generation` |
| P0-7 | **聊天模式 + 提示词模板系统** | `core-model/.../ChatMode.java`（chat/plan/agent/control）、`data/repository/PromptTemplateRepository.java`（20+ 可编辑模板） | 完全缺失。`AgentPromptBuilder` 是硬编码中文提示词，改提示词必须改代码 |
| P0-8 | **长期记忆 + 本地 RAG** | `memories`/`working_memory`/`conversation_index` 表 + FTS4、`data/repository/MemoryRanker.java`、`ai/prompt/MemoryPromptBuilder.java` | 完全缺失 |

### P1 — 明显影响体验

| # | 功能 | LCP 位置 | ACS 状态 |
|---|---|---|---|
| P1-9 | **会话 UI 渲染层**（最大缺口） | `ui/component/ChatMessageListView.java`(1104行)、`ComposerView.java`(1435行)、`AssistantTurnView.java`(332行)、`mvp/StreamingRenderController.java`；**工具卡片 `tool-ui/` 40 文件**（`ToolCallBlockView` + 10 个 factory + `DiffView`/`DiffLines`）；**markdown 14 文件** | 完全缺失。ACS 当前把工具事件拼进 `statusText` 纯文本。`libs.versions.toml` 已声明 markwon 4.6.2 但**代码零引用** |
| P1-10 | 消息操作与导出 | `ui/component/MessageActionBarView.java`、`feature-share/`（Markdown/PDF/PNG/剪贴板） | 完全缺失 |
| P1-11 | 子 agent / pipeline | `feature-tool/.../AgentTool.java`、`AgentPipelineTool.java`（DAG + write_scope 互斥）、`mvp/agent/`（`AgentExecutionController.java` 1151行） | 完全缺失，`AgentSession` 无嵌套能力 |
| P1-12 | 服务商与模型配置完善 | `core-model/.../ModelProviderPresets.java`（16 预设）、`feature-model/.../ModelCatalogClient.java`（远程拉取模型列表）、缺 `CodexResponsesProtocol` | 部分。`AgentModelConfigs` 硬编码 6 个端点，无预设表、无目录拉取、无独立压缩模型 |
| P1-13 | Skill 系统 | `data/repository/SkillRepository.java`(408行)、`SkillHubClient.java`（在线商店）、`GitHubSkillInstaller.java` | 完全缺失 |
| P1-14 | 自定义 Agent 扩展 | `extension_agents` 表、`CustomAgentExtensionTool.java` | 完全缺失（`ToolNames.agentx_` 常量在） |
| P1-15 | Slash 命令 | `ui/util/SlashCommandCatalog.java`(206行) | 完全缺失 |

### P2 — 锦上添花

| # | 功能 | LCP 位置 |
|---|---|---|
| P2-16 | 错误日志中心 UI | `data/log/`（`ErrorLogRepository`/`ErrorLogFileProvider`）+ `ErrorLogsScreenView.java` |
| P2-17 | 归档导入导出 | `data/importer/`（`LineCodeArchiveCodec` 等） |
| P2-18 | 代理设置 UI | `data/repository/ProxySettingsRepository.java` |
| P2-19 | 输入设置 / 附件 | `InputSettings.java`、`AttachmentPickerSheetView.java` |
| P2-20 | 主题系统 | `ui-theme/.../LineTheme.java`（工具卡片依赖它） |

### 明确不移植（与 IDE 定位不符）

| 功能 | 理由 |
|---|---|
| 手机控制（7 个 `phone_*` 工具 + 无障碍服务） | ACS 是代码编辑器，不操控其他 App |
| IM 机器人（`feature-im/`，Telegram 远程驱动） | 与 IDE 场景无关 |
| SSH 远程执行（`feature-ssh/`） | ACS 已有 Termux / Shizuku 后端 |
| 终端 Provider 生态（`ipc/` 23 文件） | ACS 有内置终端 |
| Workspace SAF 抽象 | ACS 有自身文件模型 |

---

## 3. 依赖关系（决定实施顺序）

```
P0-1 会话持久化 ──┬──> P0-2 上下文管理（压缩需要历史记录）
                  ├──> P1-9 会话 UI（渲染需要消息模型）
                  └──> P0-8 长期记忆（记忆需要会话索引）
P0-4 Diff 回滚 ────> P1-9 工具卡片（回滚按钮在卡片里）
P0-3 权限确认 ────┴──> P1-9（确认交互也在卡片里）
P0-5 MCP ──────────> 独立，但工具卡片需要能渲染 MCP 工具
```

**关键结论**：P0-1 / P0-2 / P0-3 / P0-4 与 P1-9 强耦合。
逐项移植会导致反复改同一批 UI 代码，**应合并为一次"数据层 + UI 层"重构**。

---

## 4. 建议实施顺序

### 阶段一：数据层地基（P0-1 + P0-2）

先建立消息/会话/工具调用的数据模型与持久化，再做上下文管理。
没有这一步，UI 层无从渲染（没有消息模型），压缩也无从下手（没有历史）。

**验收**：重启应用后会话与消息不丢失；长对话触发压缩且不丢关键信息。

### 阶段二：渲染层 + 交互（P1-9 + P0-3 + P0-4）

一次性完成会话列表、消息气泡、工具卡片、Markdown、流式渲染、权限确认、Diff 回滚。
这几项共享同一批视图与状态管理，分开做会重复劳动。

**验收**：工具调用以卡片呈现（可展开看输入输出）；文件改动可看 diff 并可回滚；
危险工具弹确认且"始终允许"能持久化。

### 阶段三：能力扩展（P0-5 + P0-6 + P0-7）

MCP 客户端、补齐内置工具（todo/web/agent 系列）、提示词模板系统。
此时 UI 已能渲染任意工具，新增工具只需注册。

**验收**：能接入一个外部 MCP server 并调用其工具；提示词可在界面编辑并生效。

### 阶段四：增强（P0-8 + P1-11 + P1-12 + P1-13）

长期记忆/RAG、子 agent、模型目录拉取、Skill 系统。

### 阶段五：收尾（P1-10 + P1-15 + P2-*）

导出、Slash 命令、日志中心、主题等。

---

## 5. 移植原则

1. **照搬优先**：LCP 与 ACS 同为 GPLv3，代码可直接移植（已 md5 校验许可一致）。
   不重复造轮子，除非 LCP 的实现与 ACS 架构冲突。
2. **纯逻辑与 UI 分离**：协议层/工具层/循环保持纯 Java、无 Android 依赖，可 JVM 单测。
   这是已移植部分能快速验证的原因，后续应延续。
3. **不照搬 LCP 的数据层实现细节**：LCP 用 Room（22 张表），ACS 已有自己的持久化栈。
   应移植**数据模型与语义**，而非 Room 实体定义。
4. **不引入 LCP 的 Android 强耦合**：LCP 的 `ToolContext` 曾含 16 个字段（7 个数据层仓库、
   SSH、UI 概念），移植时已收窄为 6 个。后续新增能力应继续走窄接口注入。
5. **每个阶段结束跑真机验证**：已移植部分暴露的 3 个真实缺陷（提示词诱导模型弃用原生
   工具调用、网关 `index:-1` 处理、按钮永久禁用）都是真机跑出来的，单测发现不了。

---

## 6. 已知风险

| 风险 | 说明 |
|---|---|
| **模型会谎报成功** | 实测：模型删掉编译错误后未重新构建，却报告"构建成功、APK 已生成"。已加 `ApkFreshnessCheck` 独立核验产物，但其他环节也可能存在类似问题，UI 层需显示真实工具输出而非模型自述 |
| **免费/小模型的工具调用不稳定** | 同一提示词成功率有波动。已通过"仅对不支持原生工具的协议注入 XML 兜底"提升到 8/8，但换模型需重测 |
| **LCP 的 UI 层依赖自定义主题** | `ui-theme/LineTheme.java` 被工具卡片依赖，移植 UI 时绕不开，需评估是否改用 ACS 主题 |
| **22 张表的 schema 迁移** | LCP 有 4 个迁移类。ACS 若复用其模型，需设计自己的迁移策略 |
| **统计口径不确定** | "AI 相关文件"的划分带主观性（LCP `app/` 233 文件中 mvp/ui 包约 190），缺口数字是估算而非精确值 |

---

## 7. 相关文档

- [`DETAILS.md`](./DETAILS.md) — 逐项实现细节、文件清单、移植要点
- [`PROGRESS.md`](./PROGRESS.md) — 进度快照：已完成范围、测试实数、未提交改动、设备状态、下一步
- [`P0-1-design.md`](./P0-1-design.md) — 会话持久化方案（JSONL append-only，不引入 SQLite）
- 项目根 [`CLAUDE.md`](../../CLAUDE.md) — 代码检索约定

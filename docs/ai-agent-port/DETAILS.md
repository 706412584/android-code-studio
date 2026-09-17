# AI Agent 能力移植 — 实现细节

本文是 [`PLAN.md`](./PLAN.md) 的配套文档，逐项记录**移植要点、文件清单、验收方式**。
优先级编号与总计划一致。

> **使用约定**：实施某一项前先读对应小节；完成后把「状态」改为已完成并注明实际改动。
> 若发现 LCP 的实现与本文描述不符，**以代码为准并修正本文**。

---

## P0-1 会话持久化与多会话

**状态**：未开始

### LCP 的做法

| 文件 | 说明 |
|---|---|
| `data/.../db/LineCodeSchema.java` | 22 张表的建表语句。与会话直接相关：`conversations`、`messages`、`message_text_chunks`、`message_blocks`、`tool_calls`、`tool_results`、`attachments` |
| `data/.../db/LineCodeDatabase.java` | Room 数据库入口 |
| `data/.../db/migration/` | 4 个迁移类（`AddIpcProvidersTable`、`AddMessageTextChunksTable`、`AddToolCallObservabilityColumns`、`Migrations`） |
| `data/.../repository/ConversationRepository.java`(374行) | 会话与消息的 CRUD |
| `app/.../mvp/ConversationPersistenceController.java`(252行) | 把流式渲染中的消息落库 |
| `data/.../repository/ConversationIndexer.java` | 会话索引（供 RAG 用） |

### 消息模型要点（`message_text_chunks` / `message_blocks` 的拆分值得注意）

LCP 把一条消息拆成两层：
- `messages` — 消息头（角色、时间、状态）
- `message_text_chunks` — 文本增量（流式写入）
- `message_blocks` — 结构化块（文本块、工具调用块、思考块…）

这个拆分是为了**流式渲染**：增量先落 chunk，渲染完成后再整理成 block。

### ACS 现状

- `ChatFragment.kt` 把过程与结果拼进 `statusText` / `summaryText` 两个 TextView，**无消息模型**
- `AIHistoryFragment` 存在且有 UI 骨架，但走旧的 `AIAgentManager` 路径（`ViewPagerAdapter:19-20`），
  与新 agent 循环不通
- ACS 已有自己的持久化栈，**不要引入 Room**

### 移植建议

1. **移植数据模型语义，不移植 Room 实现**。ACS 已有持久化设施，应定义等价的消息/会话模型，
   用 ACS 的方式存储。
2. 先只做 `conversations` + `messages` 两张表的最小集，跑通"重启不丢消息"，
   再考虑 `message_blocks` 的拆分。
3. `AIHistoryFragment` 可复用为会话列表入口，但需要接新数据源。

### 验收

- 发送若干消息 → 杀进程 → 重启 → 消息仍在
- 能新建/切换/重命名/删除会话

---

## P0-2 上下文管理（token 预算 + 压缩）

**状态**：未开始

### LCP 的做法

| 文件 | 说明 |
|---|---|
| `feature-model/.../context/ContextManager.java`(195行) | 上下文组装与裁剪 |
| `feature-model/.../context/ContextCompactionService.java`(557行) | 压缩服务 |
| `feature-model/.../context/TokenUsageTracker.java` | token 用量统计 |
| `app/.../mvp/ContextCompactionController.java`(749行) | UI 侧编排 |

**已核实的阈值**（`ContextCompactionService.java:36,45`）：
```java
public static final double COMPACT_TRIGGER_RATIO = 0.8d;       // 硬压缩
public static final double SOFT_COMPACT_TRIGGER_RATIO = 0.5d;  // 软压缩
```

裁剪策略：按 `tool_call` 分组滑窗（一个工具调用及其结果作为整体，不拆散）。

### ACS 现状

`AgentSession.run()` 每轮 `new ArrayList<>()` 起消息列表，**单轮无历史**。
`AgentPromptBuilder` 只拼工具列表与工作区信息。

### 移植建议

1. **依赖 P0-1**：压缩的前提是有历史可压缩。先完成持久化。
2. 裁剪的最小可用版本：保留 system + 最近 N 轮 + 工具结果截断（`ToolResult` 已有 50KB 中段截断）。
3. **压缩需要独立的模型调用**。LCP 的 `ModelConfig` 含 `compressionModelId` 字段，
   允许用更便宜的模型做压缩。移植时应在 `AgentModelConfigs` 补这个字段。
4. LCP 还实现了 Codex Responses API 的专用压缩协议（`OpenAiResponsesCompactionProtocol`），
   **不确定是否生产启用**——移植时可先跳过，用普通对话式摘要。

### 验收

- 长对话（超过模型上下文）不报错，且压缩后仍能回答关于早期内容的问题
- token 用量可观测

---

## P0-3 权限确认 UI + 规则持久化

**状态**：部分完成

### 已完成

`core/ai-tool/.../ToolPermissionService.java` 三档模式（`readonly` / `confirm` / `auto`），
`ToolSettingsPort.confirmDangerousTool()` 带工具名与参数询问，
`AgentRequestHandler.askDangerousToolOnMain()` 弹 AlertDialog。

### LCP 的做法

| 文件 | 说明 |
|---|---|
| `app/.../ui/component/ToolApprovalView.java` | 三按钮：始终允许 / 本次允许 / 拒绝 |
| `app/.../mvp/ToolConfirmationController.java`(361行) | `session_auto`（本次会话）与 `permanent`（永久）两种语义 + 并发等待 |
| `app/.../mvp/PermissionModeController.java` | 模式切换 |
| `data/.../repository/CommandPermissionRepository.java` | 按命令/工具名持久化授权 |

### 缺口

1. **无"始终允许"分级持久化**。当前 `AgentToolSettings.confirmDangerousTools(true)` 是全局开关，
   一旦确认就放行所有危险工具，粒度太粗。
2. **无并发等待处理**。多个工具同时需要确认时的排队/合并逻辑。

### 移植建议

- 按**工具名 + 参数模式**（如 `shell_execute` + 命令前缀）持久化授权，而非全局布尔
- `AgentToolSettings` 的 `dangerous_confirmed` 键应改为按工具的集合
- 确认弹窗需支持"本次允许"（仅本次运行）与"始终允许"（持久化）两种

### 验收

- 拒绝某工具后，模型收到明确的拒绝信息并调整策略
- "始终允许"后重启应用仍生效
- 多个危险工具同时请求时不出现弹窗丢失或死锁

---

## P0-4 Diff 回滚与审查

**状态**：部分完成（移植时主动砍掉了回滚/审查）

### 已完成

`DiffStore`（`recordDiff` / `getDiffChain`）、`DiffRecord`、`DiffRecorder`、
`InMemoryDiffStore`（**不持久化，进程内有效**）。

### LCP 的做法

| 文件 | 说明 |
|---|---|
| `data/.../repository/DiffRepository.java:61-111` | `revertDiff` / `markReverted` / `setReview` |
| `data/.../service/FileRestorer.java` | 按 diff 恢复文件内容 |
| `app/.../mvp/ToolReviewController.java`(209行) | 审查流程 |
| `tool-ui/.../view/ToolCallWriteView.java:94-97` | 卡片上的 revert / accept 按钮 |

### 缺口

1. `DiffStore` 缺回滚方法（`revertDiff` / `markReverted` / `setReview`）
2. `InMemoryDiffStore` 不持久化，重启后无法回滚
3. 无 UI 入口

### 移植建议

- 先补 `DiffStore` 接口的三个方法，实现持久化（依赖 P0-1 的存储设施）
- 回滚 UI 与 P1-9 的工具卡片一起做（按钮在卡片上）

### 验收

- 模型改了文件 → 用户能在卡片上看到 diff → 点撤销 → 文件恢复原内容
- 重启应用后仍可撤销

---

## P0-5 MCP 客户端

**状态**：未开始

### 重要澄清

LCP 的 MCP 支持是**纯客户端**（连接外部 MCP server），**不是服务端**。
`ToolNames` 里已有 `mcpx_` 前缀常量，但无实现。

### LCP 的做法

| 文件 | 说明 |
|---|---|
| `data/.../repository/McpExtensionRepository.java`(266行) | `tools/list` 调用 + SSE 解析 |
| `feature-tool/.../builtin/CustomMcpHttpTool.java`(191行) | `initialize` / `tools/call`，含 session id 与协议版本 `2025-03-26` |
| `core-model/.../model/ExtensionMcpConfig.java`、`McpToolConfig.java`、`McpToolSummary.java`、`McpSettingsState.java`、`McpRequestHeader.java` | 配置模型 |
| `extension_mcps` 表 | 持久化 |
| `app/.../mvp/McpSettingsController.java`、`McpKindDescriptor.java` | 设置界面 |
| `app/.../ui/component/McpExtensionEditScreenView.java` | 编辑界面 |

### 移植建议

1. 依赖已移植的 `SimpleHttpClient` / `UrlPolicy`（`core/ai-protocol/security/`），网络层可复用。
2. 把 MCP 工具包装成 `BaseTool` 适配器注册进 `ToolRegistry`——
   这正是移植时在 `ToolRegistry` 注释里预留的扩展点（"在 app 层实现一个把远程工具包装成
   `BaseTool` 的适配器再注册进来即可"）。
3. 先做 `tools/list` + `tools/call` 两个方法，够用即可。

### 验收

- 能配置一个 MCP server 地址 → 拉取到工具列表 → 模型能调用其工具

---

## P0-6 内置工具缺口

**状态**：部分完成（已移植 6 个文件工具 + shell）

### 已移植

`file_read`、`file_write`、`file_edit`、`file_delete`、`glob`、`list_dir`、`shell_execute`
以及 ACS 特有的 `gradle_build`、`install_apk`、`launch_app`、`logcat_read`。

### 缺口清单

| 工具 | LCP 位置 | 优先级 | 说明 |
|---|---|---|---|
| `todo_update` | `builtin/TodoUpdateTool.java` | 高 | 需 `TodoStateStore` + 提示词注入 `{{TODO_STATE}}` |
| `web_fetch` | `builtin/WebFetchTool.java` | 高 | |
| `web_search` | `builtin/WebSearchTool.java` + `WebSearchService.java` + `search/` 目录（6 个 provider：Bing / BingRss / Brave / SerpApi / Tavily / Default） | 高 | 需 API key 配置 |
| `memory_update` | `builtin/MemoryUpdateTool.java` | 中 | 依赖 P0-8 |
| `agent` | `builtin/AgentTool.java` | 中 | 子 agent（explore / sub-coding），依赖 P1-11 |
| `agent_pipeline` | `builtin/AgentPipelineTool.java` | 中 | DAG 依赖 + write_scope 互斥 |
| `agent_output` | `builtin/AgentOutputTool.java` | 中 | |
| `image_understanding` | `builtin/ImageUnderstandingTool.java` + `Base64ImageValidator.java` | 低 | |
| `image_generation` | `builtin/ImageGenerationTool.java` + `ImageApiClient.java` + `ImageResponseParser.java` | 低 | |

### 不移植

`Phone*`（7 个工具 + `PhoneControlToolSupport` + `PhoneScreenshotCache`）、
`CustomAgentExtensionTool`（依赖 P1-14）。

### 验收

每个工具：注册后模型能调用，工具卡片能正确渲染（依赖 P1-9）。

---

## P0-7 聊天模式 + 提示词模板系统

**状态**：未开始

### LCP 的做法

**`core-model/.../ChatMode.java`**（已核实）：
```java
public static final String CHAT = "chat";
public static final String PLAN = "plan";
public static final String AGENT = "agent";
public static final String CONTROL = "control";
public static final String DEFAULT = AGENT;
// 对应模板 ID：chatModeChat / chatModePlan / chatModeAgent / chatModeControl
```

**`data/.../repository/PromptTemplateRepository.java`** — 可编辑模板。已核实的占位符：

```
CHAT_MODE_CONTEXT    EXTENSIONS_CONTEXT   GLOBAL_SKILLS_ROOT   HISTORY_SECTION
HOME_PATH            LEARNING_CONTEXT     LINECODE_ROOT        MEMORY_SECTION
MODEL_ID             MODEL_IDENTITY       MODEL_NAME           MODEL_PROTOCOL
MODEL_PROVIDER       PRIVATE_BOUNDARY_SECTION                  ROLE_PROMPT
SCOPE_CONTEXT        SKILLS_SECTION       SKILL_PATHS_SECTION  SUMMARY
TASK_DESCRIPTION     TODO_LIST            TONE_CONTEXT         TOOLS_CONTEXT
WORKING_MEMORY_SECTION                    WORKSPACE_CONTEXT
```

模板 ID（部分）：`systemPrompt`、`chatModeChat/Plan/Agent/Control`、`agentRoleX`、
`todoState`、`contextCompaction`。

### ACS 现状

`AgentPromptBuilder.java` 是硬编码中文提示词，改提示词必须改代码重新编译。

### 移植建议

1. 先做**占位符替换机制**（把当前硬编码提示词改造成模板 + 占位符），
   这一步收益立竿见影且改动小。
2. 再做模板的持久化与编辑 UI。
3. ACS 没有 LCP 的 skill/memory/extension 概念，对应的占位符可先留空。

### 验收

- 在设置界面编辑系统提示词 → 新会话生效，无需重新编译

---

## P0-8 长期记忆 + 本地 RAG

**状态**：未开始

### LCP 的做法

| 文件 | 说明 |
|---|---|
| `memories` / `working_memory` / `conversation_index` 表 | FTS4 全文索引 |
| `data/.../repository/MemoryRanker.java` | TF-IDF 式打分 |
| `data/.../repository/LearningContextRepository.java` | |
| `data/.../TextTokenizer.java` | 分词（需适配中文） |
| `app/.../ai/prompt/MemoryPromptBuilder.java` | 注入提示词 |
| `app/.../ui/component/MemorySettingsScreenView.java` | 设置界面 |

### 移植建议

- 依赖 P0-1（`conversation_index` 建立在会话数据之上）
- 中文分词是难点，`TextTokenizer` 需确认是否支持中文（**未核实**）
- 最小可用版本：只做"用户显式保存的记忆条目"检索，不做自动学习

---

## P1-9 会话 UI 渲染层（最大缺口）

**状态**：未开始

### 规模

| 部分 | 文件数 | 关键文件 |
|---|---|---|
| 会话视图 | — | `ChatMessageListView.java`(1104行)、`ComposerView.java`(1435行)、`AssistantTurnView.java`(332行)、`AssistantMessageView.java`(350行)、`WorkingStatusView.java` |
| 时间线编排 | — | `ui/model/ConversationTimeline.java`（把消息 + 工具调用编排为块） |
| 流式渲染 | — | `mvp/StreamingRenderController.java`（80ms 节流刷新） |
| **工具卡片** | **40** | `tool-ui/`：`ToolCallBlockView`、`ToolCallViewFactoryRegistry` + 10 个 factory（Read/Write/Delete/Shell/Agent/AgentPipeline/Todo/ImageGeneration/PhoneControl/Generic）、`DiffView`、`DiffLines`、`NestedToolCallParser`、`ToolCallJsonFormatter` |
| Markdown | 14 | `markdown/`：`MarkdownRenderer`、`MarkdownCodeBlockView`、`MarkdownTableView`、`MarkdownImageView` |
| 思维链 | — | `ui-theme/.../ThinkingBlockView.java`（折叠显示） |

### ACS 现状与可复用资产

- **完全缺失**，当前是 `statusText` 纯文本
- `libs.versions.toml:20,95-98` 已声明 markwon 4.6.2（core / ext-strikethrough / linkify / recycler），
  但**代码零引用** —— 可直接用于 Markdown 渲染
- ACS 自身有编辑器组件（sora-editor），代码高亮可复用

### 移植建议

1. **不要逐文件搬 40 个 tool-ui 文件**。先做：
   - 消息列表（含流式）
   - 一个通用工具卡片（可展开看输入/输出 JSON）
   - Markdown 渲染（用已声明的 markwon）
2. 卡片 factory 机制（按工具类型渲染不同视图）可等工具变多后再引入。
3. `ConversationTimeline` 的"把消息+工具调用编排为块"是核心抽象，值得移植其**设计**。
4. LCP 的 `ui-theme/LineTheme.java` 被工具卡片依赖，**移植 UI 时绕不开**——
   需评估改用 ACS 现有主题还是移植主题系统。

### 验收

- 工具调用以卡片呈现，可展开查看完整输入输出
- 助手回复支持 Markdown（代码块高亮、表格、链接）
- 流式输出不卡顿（节流刷新）

---

## P1-11 子 agent / pipeline

**状态**：未开始

### LCP 的做法

| 文件 | 说明 |
|---|---|
| `feature-tool/.../builtin/AgentTool.java` | explore / sub-coding 两种子 agent，异步 |
| `feature-tool/.../builtin/AgentPipelineTool.java` | DAG 依赖 + `write_scope` 互斥 |
| `feature-tool/.../builtin/AgentOutputTool.java` | 取子 agent 结果 |
| `app/.../mvp/agent/AgentExecutionController.java`(1151行) | 编排 |
| `app/.../mvp/agent/PipelineDependencyResolver`、`AgentResultRegistry`、`AgentProgressSession`、`ToolExecutionBatch` | 支撑组件 |

### 关键设计点

- `write_scope` 互斥：防止多个子 agent 并发写同一目录
- 进度会话：子 agent 的执行进度上报

### ACS 现状

`AgentSession` 无嵌套能力。

### 移植建议

- 先做单个子 agent（`agent` 工具），pipeline 后置
- `AgentSession` 需支持递归调用（注意取消传播与深度限制）

---

## P1-12 服务商与模型配置完善

**状态**：部分完成

### 已核实：LCP 的 20 个服务商预设

`core-model/.../ModelProviderPresets.java`：
```
claude  openai  gemini  deepseek  groq   glm     kimi    k3
minimax  mimo   qwen    siliconflow  together  openrouter
ollama  lmstudio  codex  custom
（另有 "cn" / "global" 分组标记）
```
含 4 槽位（main / haiku / sonnet / opus）与 `[1m]` 上下文后缀语法。

### ACS 现状

`AgentModelConfigs` 硬编码 6 个 provider（openai / deepseek / grok / claude / localllm / custom）。

### 缺口

1. 无预设表（新增服务商要改代码）
2. 无远程模型目录拉取（`feature-model/.../ModelCatalogClient.java` +
   `OpenAiCatalogFetcher` / `AnthropicCatalogFetcher` / `CodexCatalogFetcher`）
3. 缺两个协议：`CodexResponsesProtocol`、`OpenAiResponsesCompactionProtocol`
4. 无 `compressionModelId`（独立压缩模型）

### 移植建议

- 预设表优先（改动小、收益直接）
- 模型目录拉取次之
- Codex 协议需确认 ACS 用户是否需要

---

## P1-13 Skill 系统 / P1-14 自定义 Agent 扩展 / P1-15 Slash 命令

**状态**：均未开始

| 项 | LCP 位置 | 备注 |
|---|---|---|
| Skill 系统 | `data/.../repository/SkillRepository.java`(408行)、`service/SkillFileManager.java`、`SkillHubClient.java`（在线商店）、`GitHubSkillInstaller.java`、`SkillFrontmatterParser.java`、`skills`/`skill_usage` 表 | 含在线商店，移植需评估是否要外网依赖 |
| 自定义 Agent 扩展 | `extension_agents` 表、`ExtensionAgentRepository.java`、`CustomAgentExtensionTool.java` | `ToolNames.agentx_` 常量已存在 |
| Slash 命令 | `app/.../ui/util/SlashCommandCatalog.java`(206行)、`SlashCommandPopup.java` | `/mode`、`/model` 切换 |

---

## P2 项（简略）

| 项 | LCP 位置 | 备注 |
|---|---|---|
| P2-16 错误日志中心 | `data/log/`（`ErrorLogRepository` / `ErrorLogFileProvider` / `ErrorLogRedactor`）+ `ErrorLogsScreenView.java` + `ErrorLogController.java` | `ErrorLogRedactor` **已移植**，缺仓库与 UI |
| P2-17 归档导入导出 | `data/importer/`（`LineCodeArchiveCodec` / `ArchiveSecretRedactor` / `LineCodeImportMapper`）+ `LineCodeArchiveService.java` | |
| P2-18 代理设置 UI | `data/.../repository/ProxySettingsRepository.java` | `AppProxy` / `UrlPolicy` **已移植**，缺 UI |
| P2-19 输入设置 / 附件 | `InputSettings.java`、`InputAttachment.java`、`AttachmentPickerSheetView.java` | |
| P2-20 主题系统 | `ui-theme/.../LineTheme.java` + `ThemeSettingsRepository` | **P1-9 依赖它** |

---

## 附：已移植部分的实现要点（供后续参考）

### 架构边界（务必延续）

- **纯逻辑与 Android 分离**：`core/ai-tool-api` / `ai-protocol` / `ai-tool` / `ai-agent`
  四个模块是 `java-library`，不依赖 Android，因此能在 JVM 上单测。
  这是移植期能快速发现问题的关键（58 项测试，见 [`PROGRESS.md`](./PROGRESS.md)）。
- **窄接口注入**：`ToolSettingsPort`（工具层读配置）、`ShellBackend`（shell 抽象）、
  `DiffStore`（改动记录）、`BuildServiceProvider`（构建服务）——
  都是"接口在纯 Java 模块、实现在 app 层"的模式。新增能力应沿用。

### 移植时踩过的坑（避免重犯）

| 坑 | 现象 | 处理 |
|---|---|---|
| org.json 在 java-library 中缺失 | 编译报错 | 用 `compileOnly`（Android 运行时提供） |
| GBK 控制台损坏中文测试字面量 | 测试断言乱码失败 | 模块内固定 `options.encoding = "UTF-8"` + `file.encoding` |
| Kotlin 属性在 Java 侧的方法名 | 误用 `getXxx()` | Kotlin `val x` → Java `getX()`；`object` → `INSTANCE`；`companion` → `Companion` |
| Kotlin `Sequence` 不能直接 for-each | 编译错误 | 显式 `.iterator()` |

### 已修的真实缺陷（同类问题可能在未移植部分重现）

1. **提示词诱导模型弃用原生工具调用**：无条件注入 XML 兜底格式说明，
   导致模型改用手写 XML（且常残缺），两条路径都解析不出调用。
   实测去掉后原生调用成功率 1/6 → 8/8。**仅在协议不支持原生工具时才注入**。
2. **网关 `index:-1` 处理**：部分网关把 tool_calls 的 `index` 发成 -1 且首帧无 `function.name`，
   按 index 归并会把多个调用挤进同一 builder。改为按调用边界切分。
3. **按钮永久禁用**：`AgentRequestHandler` 缺 try/finally，异常时 UI 卡死。
4. **构建错误不可见**：`TaskExecutionResult` 只带 `Failure` 枚举，
   模型只能盲猜（实测烧 30 次工具调用）。已加 `BuildOutputBuffer` 旁路缓冲。
5. **模型谎报成功**：模型未重新构建却称"构建成功、APK 已生成"。
   已加 `ApkFreshnessCheck` 独立核验。
6. **工具名别名**：模型有 `read`/`write`/`edit` 先验，与 `file_read` 不匹配。
   已加 `ToolRegistry` 别名归一。

> **规律**：真机验证发现的缺陷，单测都发现不了。每个阶段结束务必真机跑一遍。

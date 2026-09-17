# AI Agent 移植 — 进度快照

> **用途**：跨会话的进度锚点。每次开工先读本文确认当前状态，收工前更新。
> 计划与缺口清单见 [`PLAN.md`](./PLAN.md)，逐项实现细节见 [`DETAILS.md`](./DETAILS.md)。
>
> **本次快照**：2026-09-18（分支 `dev`，P0-1 已提交 `7c677bd`，P0-2 见下）

---

## 1. 一句话状态

**工具循环已跑通（W0–W5），数据层地基已完成（P0-1 + P0-2），UI 渲染层未开始。**
agent 已能在真机上完成「读文件 → 改文件 → 构建 → 安装 → 启动 → 读日志」闭环，
会话可持久化并跨进程续接，长对话有 token 预算与压缩；
但对话界面仍是纯文本 `statusText`，无消息模型、无工具卡片。

覆盖率约 **30%**（已移植约 130 / LCP 相关 440–470 文件）。

---

## 2. 已完成（W0–W5）

### 2.1 四个纯 Java 模块（不依赖 Android，可 JVM 单测）

| 模块 | 主源码 | 行数 | 内容 |
|---|---|---|---|
| `core/ai-tool-api` | 15 | 1517 | 工具契约：`ToolCall`/`ToolResult`(50KB 中段截断)/`ToolInfo`/`ToolNames`/`ToolCallTextParser`/`ErrorLog` |
| `core/ai-protocol` | 50 | 5461 | 协议层：OpenAI 兼容 + Anthropic Messages、重试、流式解析、7 个 reasoning 策略、`SimpleHttpClient`/`UrlPolicy` |
| `core/ai-tool` | 27 | 3506 | 工具执行：注册表(RW 锁)、执行器(错误即结果)、权限判定、6 个文件工具、shell 抽象、`BuildErrorExtractor`、`ApkFreshnessCheck` |
| `core/ai-agent` | 24 | 3276 | 循环本体 + 会话持久化 + 上下文管理 |

**合计 116 个主源文件 / 13760 行**，全部零 Android 依赖——这是移植期能快速验证的关键。

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

**单元测试：200 项，0 失败，1 跳过**（跳过项是联网网关测试，需 `-Dai.live.apiKey=...`）

| 模块 | tests | failures | skipped |
|---|---|---|---|
| `ai-tool-api` | 5 | 0 | 0 |
| `ai-protocol` | 4 | 0 | 0 |
| `ai-tool` | 37 | 0 | 0 |
| `ai-agent` | 154 | 0 | 1 |

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

## 3. 数据层地基（阶段一）已提交范围

### 3.1 P0-1 会话持久化与多会话（4 个提交）

| 提交 | 内容 |
|---|---|
| `a7600f5` | 会话日志的 append-only 持久化基础：条目信封、`ConversationLog`、JSONL 读写 |
| `cb04ee6` | `ConversationStore` 窄接口 + `FileConversationStore`（含残缺尾部处理、指纹窗口判变） |
| `b1df5ee` | `ConversationHistory.fold` 折叠为可续接消息；`CompactionEntry` 预留 |
| `7c677bd` | 接入 agent 路径：`AgentOrchestrator` 用会话历史作为循环起点 |

### 3.2 P0-2 上下文管理与压缩

**五个新类**（`core/ai-agent/.../agent/context/`）：

| 类 | 职责 |
|---|---|
| `TokenEstimator` | 估算消息/工具定义的 token；CJK 约 1 token/字，其余约 4 字符/token |
| `TokenUsageTracker` | 用量跟踪 + 软(0.5)/硬(0.8)压缩阈值判定 |
| `ContextTrimmer` | 按工具调用组裁剪消息（调用与结果不拆散） |
| `ContextCompactor` | 把消息段摘要为字符串（纯字符串进、字符串出，便于单测） |
| `RunContextManager` | **一次运行内的预算**：每次请求前裁剪，钉住系统提示词与本次请求 |

**`ConversationCompaction`**（`conversation` 包）：决定"压缩哪一段"，产出 `CompactionEntry`。

**关键设计决策**：

- **裁剪在循环内、压缩在入口**。裁剪必须每轮做（消息随轮次增长，一次读大文件就可能超限）；
  压缩需要条目序号且结果必须落盘，只能在入口做一次——放循环里会每次运行重复摘要同一段。
- **两条消息不可裁**：系统提示词与**本次用户请求**。`ContextTrimmer` 从最旧开始丢，
  而本次请求恰好排在历史之后，放任裁剪会把它丢掉，模型便"不知道自己在做什么"。
- **`ContextTrimmer.trim` 把 `budgetTokens <= 0` 解释为"不限"**——语义本身合理
  （未配置预算不该清空历史），但调用方算出 0 预算时会静默失效。`RunContextManager`
  两处都显式绕开（轮次传 `max(1,...)`，历史用 `remaining > 0` 判断）。
- **压缩是回溯性标记**：`CompactionEntry` 出现在被覆盖条目**之后**，因此
  `ConversationHistory.fold` 必须两趟处理（先找最大覆盖序号与全部摘要，再追加未被覆盖的条目）。
- **`maybeCompact` 失败一律静默降级**：压缩是优化，不能因为它失败让用户发不出消息。

**测试**：`TokenEstimatorTest` 14 + `TokenUsageTrackerTest` 14 + `ContextTrimmerTest` 17
+ `ContextCompactorTest` 20 + `ConversationCompactionTest` 22 + `RunContextManagerTest` 12。

---

## 4. 待办（14 项未完成 / 16 项总计）

任务定义已迁入本会话 `TaskList`（#2 已完成，#3 进行中，其余 pending）。

### P0 — 缺了 agent 能力不完整

| # | 任务 | 状态 | 阻塞于 | 关键依赖说明 |
|---|---|---|---|---|
| #2 | P0-1 会话持久化与多会话 | **已完成** | — | 4 个提交 |
| #3 | P0-2 上下文管理与压缩 | **已完成** | — | 见 §3.2 |
| #15 | P0-3 权限确认分级与持久化 | pending | — | 当前只有全局布尔 |
| #5 | P0-4 Diff 回滚与审查 | pending | #2 | 移植时主动砍掉了 `revertDiff`/`setReview` |
| #14 | P0-5 MCP 客户端 | pending | — | 纯客户端 HTTP JSON-RPC，可独立做 |
| #16 | P1-9 会话 UI 渲染层 | pending | #2 | **最大缺口** |
| #10 | P0-7 提示词模板系统 + 聊天模式 | pending | — | 占位符机制可先做，收益立竿见影 |
| #8 | P0-6 补齐内置工具 | pending | #16 | 高优先级部分(todo/web)**实际无依赖** |
| #12 | P0-8 长期记忆 + 本地 RAG | pending | #2 | |

### P1 / P2

| # | 任务 | 状态 | 阻塞于 |
|---|---|---|---|
| #4 | P1-11 子 agent / pipeline | pending | — |
| #11 | P1-12 服务商预设表与模型目录 | pending | — |
| #6 | P1-13 Skill 系统 | pending | — |
| #13 | P1-10 消息操作与导出 | pending | #16 |
| #7 | P1-14 自定义 Agent 扩展 + P1-15 Slash 命令 | pending | — |
| #9 | P2 收尾项（日志/归档/代理/输入/主题） | pending | — |
| #17 | 项目界面 AI 悬浮助手 + 界面文案中文化 | pending | — |

> **依赖修正记录**：任务 #8（原 #22）最初被设为 `blockedBy=[20,23,24]`，但
> `todo_update`/`web_fetch`/`web_search` 三个工具的实现本身不需要 UI 或记忆，已放宽。
> 只有卡片渲染依赖 #16。

### 依赖图（决定实施顺序）

```
#2 会话持久化 ──┬──> #3 上下文压缩   ✔ 两者均已完成
                ├──> #16 会话 UI（渲染需要消息模型）
                ├──> #5 Diff 回滚
                └──> #12 长期记忆（记忆需要会话索引）
#15 权限确认 ───┴──> #16（确认交互在卡片里）
#5 Diff 回滚 ───────> #16（回滚按钮在卡片里）
#14 MCP ────────────> 独立（但卡片需能渲染 MCP 工具）
#17 悬浮助手 ───────> 独立（纯 UI + 复用 AgentOrchestrator）
```

**关键结论**：`#5/#15/#16` 强耦合。逐项做会反复改同一批 UI 代码，
**应合并为一次改动**（渲染层 + 交互）。

---

## 5. 建议的下一步

阶段一（数据层地基）**已完成**（#2 + #3）。下一批可选：

- **#16 会话 UI 渲染层**——最大缺口，且 #5/#15 都挂在它下面；先做它能一次收掉三项。
- **#14 MCP 客户端**——纯 Java 可独立单测，与 UI 无耦合，适合并行。
- **#17 悬浮助手**——纯 UI，可直接复用已完成的 `AgentOrchestrator`。

### 未提交 / 待决

- `core/ai-{protocol,tool-api,tool}/port.sh` —— 移植期一次性脚本，尚未决定是否入库

### 未验证项（做后续工作时应顺带补齐）

| 项 | 状态 |
|---|---|
| ShizukuShellBackend 端到端 | 黑鲨已装 Shizuku，**需启动服务后重测** |
| 构建闭环四件套在真机的完整 agent 驱动 | 四件套各自验证过，**未经 agent 串起来跑** |
| agnes 网关端到端 | 有 key 与端点，`AgentLiveGatewayTest` 默认跳过，未跑 |
| **上下文压缩的真实触发** | 单测覆盖边界逻辑，但**未经真机长对话触发**（需历史超硬阈值） |

### P0-1 前置调研结论（2026-09-17 核实，影响存储选型）

**更正 `PLAN.md` 的一处描述**：它写 `AIHistoryFragment`「有会话历史的 UI 骨架」。
实际核实 `AIHistoryFragment.kt:85` → `AIAgentManager.getConversationHistory()` →
`getModificationHistory()`，它列出的是 **`UnifiedModificationAttempt`（文件改动记录）**，
**不是对话消息**。所以那是"文件改动历史"，与 P0-1 的会话/消息模型无关，**不能复用为会话列表入口**。

**ACS 无任何数据库设施**（已彻底核实）：
- 全仓库无 `SQLiteOpenHelper` / `RoomDatabase` / `@Database` / `androidx.room` 依赖
- 唯一命中是 `GroovyAutoComplete.java:57-63` 的补全字符串清单，不是实际使用
- 无 `openOrCreateDatabase` / `getDatabasePath` 调用
- 现有持久化只有：SharedPreferences（含 `EncryptedSharedPreferences`）+ 文件

**LCP 的 schema 自带逃生口**：`conversations`/`messages`/`message_blocks`/`tool_calls`/`tool_results`
**每张表都有 `raw_json` 列**。即 LCP 已是「规范列（可查询）+ JSON（保真）」的混合模式。

**存储选型已定**（方案见 [`P0-1-design.md`](./P0-1-design.md)）：

**JSONL 为唯一真源（append-only），不引入 SQLite/Room。** 参考 `C:\Users\70641\cc-haha`
的 `src/server/services/localIndex/`——那套机制把 JSONL 当 source of truth，SQLite 只作可重建的派生索引。
借其 8 个机制（append-only 一会话一文件、条目自描述信封、字节偏移索引、增量水位线、
残缺尾部处理、指纹窗口判变、摘要由 fold 派生、摘要缓存独立）。

关键推论：**重命名 = 追加一条 `custom-title` 条目**，永不回写历史；
**`compaction` 条目现在只定义不实现**，为 P0-2 预留——压缩时只追加该条目，
读取端跳过被压缩区间，无需改写历史。这把 P0-2 的改动面压到最小。

分 4 步（1a 模型+日志 / 1b Store / 1c AgentSession 历史重载 / 1d 最小会话列表），
前 3 步纯 Java 单测闭环。

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

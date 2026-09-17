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

### 3.3 项目界面 AI 悬浮助手 + 界面文案中文化（#17）

**新文件**（`core/app`）：

| 文件 | 职责 |
|---|---|
| `artificial/agent/FloatingAssistantView.kt` | 悬浮入口 + 面板装配、事件流渲染、危险工具确认 |
| `adapters/AssistantMessageAdapter.kt` | 消息列表；按位置精确通知，不整表刷新 |
| `res/layout/layout_ai_assistant.xml` | 面板布局（形态由 LayoutParams 切换，不复制两套） |
| `res/layout/layout_ai_assistant_fab.xml` | 收起点：圆形入口（刻意不用 FAB，避免抢主操作注意力） |
| `res/layout/item_assistant_message.xml` | 单条消息卡片 |

**挂载点**：`fragment_main.xml` 新增最后一个子 `FrameLayout@assistantContainer`。
必须是最后一个子 View——`ConstraintLayout` 反向分发触摸，放最后才能让面板盖在动作列表之上；
容器自身不可点击，收起时触摸穿透到列表。

**关键设计决策**：

- **直接持有 `AgentOrchestrator`，不复用 `AgentRequestHandler`**。后者是
  `ChatFragment` 的控件渲染器：它把事件塞进 `statusText`/`summaryText` 两个 TextView，
  还要求传 `FileModificationAdapter` 与编辑器刷新回调，且**完全忽略 `TEXT_DELTA`**。
  这里的界面是消息列表，需要的是事件流本身。两者 UI 契约不同，强行复用会把
  ChatFragment 的控件假设带进来。
- **orchestrator 只创建一次并跨请求复用**。它持有当前会话 id，每次新建都会让历史断掉
  （与 P0-1 的会话续接直接冲突）。
- **`TURN_FINISHED` 用规范输出覆盖流式累积**。模型可能把工具调用写成正文文本形态，
  累积的增量里含标记，而事件里的 `message` 已经过 `ToolCallTextParser` 剥离。
  同时用 `streamedThisRun` 标志避免收尾时再补一条——否则同一段回答出现两遍。
- **流式首段新建消息而非复用末条**。模型可能在正文前先输出工具调用文本形态，
  此时 `TOOL_STARTED` 已往列表插过过程条目；复用末条会把过程信息当正文覆盖掉。
- **协程作用域必须用 `viewLifecycleOwner.lifecycleScope`**。
  `BaseFragment.viewLifecycleScope` 只是普通 `CoroutineScope(Dispatchers.Default)`，
  不随视图销毁取消，用它会在视图 detach 后继续写控件。
- **返回键用 `OnBackPressedCallback` 注册在 `viewLifecycleOwner` 上**。Activity 自己
  注册了一个（切屏幕用），dispatcher 按后进先出分发，后注册的先拿到事件；
  面板收起后返回 false，继续落到 Activity 的 callback。
- **工作区从 `GeneralPreferences.lastOpenedProject` 推得**。主屏本身没有"当前项目"
  概念；prefs 里没有可用项目时传 null，助手提示"请先打开项目"而非静默失败。
- **危险工具确认走 `CountDownLatch(1)` + 120s 超时**，已在主线程则直接拒绝——
  宁可让工具失败也不无确认执行。

**中文化范围**（gradle/git 指令本身不译，符合用户要求）：

| 位置 | 处理 |
|---|---|
| `bottomsheet_project_list.xml:32` | `"import project"` → `@string/import_project` |
| `item_project.xml:31,55` | 设计期占位文案改 `tools:text`（原本 `android:text` 会在无数据时显示英文） |
| `MainFragment.kt` 对话框硬编码 | 备份/删除/项目选项共 12 处 → `string.*` 资源 |
| `COMMON_GIT_OPTIONS` | chip **描述**本地化（`git_opt_*`）；**flag 保持英文**（`--depth 1` 等） |
| `values-zh-rCN/strings.xml` | 补 `btn_idecfg`；新增 42 条中文翻译 |

### 3.4 P0-3 权限确认分级与持久化（#15）

**新文件**（`core/ai-tool`）：

| 文件 | 职责 |
|---|---|
| `ToolPermissionRule` | 授权粒度：把「工具 + 参数」归约为一条可持久化的规则键 |
| `DangerousToolDecision` | 三档答复：`ALLOW_ONCE` / `ALLOW_ALWAYS` / `DENY` |

**改动**：`ToolSettingsPort` 新增 `hasDangerousToolRule` / `rememberDangerousToolRule`（均有默认实现，
未接入存储的调用方行为不变）；`ToolExecutor` 新增 `confirmViaRules`（先查规则，未命中才询问）；
`AgentToolSettings` 重写授权部分；两处确认弹窗（`AgentRequestHandler`、`FloatingAssistantView`）
从「全局布尔」改为「按粒度写规则」；设置页新增「已授权的危险工具」查看/撤销入口。

**关键设计决策**：

- **授权粒度按工具分类决定**，因为「参数里什么才是危险的部分」因工具而异：
  shell 取命令**首词**（`git status` 的授权不放行 `git push --force`），
  文件写取**路径**，其余退化为按工具名。
  取首词而非整条命令是折中：整条前缀匹配过窄（参数一变就要重问），只匹配工具名又过宽
  （等于回到全局布尔，用户只能被迫每次都点「本次允许」）。
- **`file_delete` 必须按路径授权**——它的参数是 `paths` **数组**而非 `file_path` 单值，
  只按单值键提取会拿到空 scope，于是最具破坏力的工具反而拿到最宽的授权
  （「始终允许删除 A.kt」变成「始终允许删除任何文件」）。scope 取**全部**路径而非首个，
  否则一次多文件删除里其余的会被悄悄连带放行。
- **规则键用 NUL 作分隔符**：工具名与命令首词都是受限字符集，NUL 不可能出现其中，
  因此不存在拼接歧义（用 `:` 或 `_` 时 `"a_b"+"c"` 与 `"a"+"b_c"` 会撞车）。
- **`ToolPermissionRule.keyFor` 先归一化工具名**：模型常写 `bash` 而非 `shell_execute`，
  授权用别名、判定用规范名会让用户点过的「始终允许」静默失效，表现为反复弹窗。
- **运行内记忆用集合而非单个布尔**：布尔无法区分「放行了 git」与「放行了全部」，
  会让一次「仅本次允许」顺带放行同一运行内的其它危险命令。
- **确认过程整体持锁**：工具可并发执行（`isConcurrencySafe` 的工具被并行调度），
  不加锁则两个工具同时发现「没有规则」会各弹一个对话框，用户看到重叠弹窗，
  且先答复的结果被后答复的覆盖。串行化后第二个调用能看到第一个刚写入的运行内规则。
- **撤销入口是必需的**：用户在弹窗里只看到一条命令，不可能记住后来放行了什么；
  没有撤销入口，规则只增不减，最终等同于关掉确认而用户毫无感知。

**测试**：`ToolPermissionRuleTest` 17 条（粒度边界、别名归一、NUL 无歧义、多路径覆盖、
非法 JSON 降级）。四个 AI 模块共 217 条全绿。

### 3.5 P0-4 Diff 回滚与审查（#5）

**新文件**（`core/ai-tool`）：

| 文件 | 职责 |
|---|---|
| `DiffReverter` | 按记录恢复文件内容（含路径重校验、新建文件的删除语义） |
| `FileDiffStore` | append-only JSONL 持久化存储，跨进程存活 |

**改动**：`DiffRecord` 增加 `reverted` / `reviewState` / `reviewMessage` 与
`withReview` / `asReverted`（不可变派生）；`DiffStore` 增加
`findById` / `getAll` / `markReverted` / `setReview` / `latestFor`（均有默认实现）；
`InMemoryDiffStore` 增加 id 索引；`AgentOrchestrator` 新增 `defaultDiffStore(context)`
与 `getDiffStore()`，两处调用方（悬浮助手、ChatFragment 的 handler）改用持久化存储；
`AssistantMessageAdapter` + `item_assistant_message.xml` 增加行内撤销按钮。

**关键设计决策**：

- **回滚拆成两层**：存储只负责「该恢复成什么」与状态标记，动文件由 `DiffReverter` 做。
  合在一起会让每个存储实现都重复一遍路径校验，只要有一个实现漏了校验，回滚就成了
  绕过工作区边界的写入通道。
- **恢复前重新校验路径**：记录里的路径是写入时算出来的，用户可能已经切了项目，
  或手工编辑了日志。不重校验就会往旧工作区甚至任意路径写。
- **回滚新建文件是「删除」而非「写空」**：写空内容会留下一个空文件，与「改动前不存在」
  并不等价（构建脚本会因此认为该资源存在）。
- **先写文件、成功后才标记状态**：反过来会留下「状态说已回滚、文件其实没变」的记录，
  用户看到内容还在却无法再次回滚。
- **已回滚的记录拒绝二次回滚**：否则用户误点第二次会把之后的手工编辑覆盖掉。
- **回滚同时置 `reviewState = rejected`**：两处状态不一致会让 UI 显示
  「已接受但内容已还原」这种自相矛盾的结果。
- **`asReverted` 幂等**：重复派生不得改动 `oldContent`，否则第二次回滚会写到错误内容。
- **`FileDiffStore` 沿用 append-only**：与 `ConversationLog` 同样的取舍——只追加、
  永不原地改写，崩溃最多损坏最后一行。启动时全量回放到内存，之后查询不碰磁盘。
  刻意**不**搬 `ConversationLog` 的字节偏移与指纹判变：那套是为数万条目的长会话准备的，
  diff 记录数量在裁剪下天然有界，额外机制只增加出错面。
- **裁剪是唯一原地改写路径**，用「写临时文件 + 原子替换」；重写失败时内存索引必须保持
  旧状态，否则出现「内存里有、文件里没有」的假象。
- **`appendLine` 先追加、后裁剪**：裁剪从内存索引重写整个文件，新记录此刻已在内存里，
  自然会写进去；反序会让新记录被写两遍。
- **写盘失败静默降级为仅内存记录**：回滚是补救手段，不能因为磁盘问题让 agent 的
  文件写入操作看起来失败了。

**UI 入口**（撤销按钮的行内位置属于 #5 的最小可用入口；完整工具卡片仍在 #16）：
工具结果带 `diffId` 时插入一条「✎ 已改动 <路径>」并挂撤销按钮；撤销成功后按钮转为
禁用态显示「已撤销」——按钮消失会让用户怀疑是否点到了。

**测试**：`DiffRevertTest` 22 条（内容恢复、新建文件删除、二次回滚拒绝、工作区外拒绝、
无上下文拒绝、最近一条定位、状态一致性、跨实例持久化、坏行跳过、append-only、
裁剪一致性、父目录创建、不可写降级）。四个 AI 模块共 239 条全绿。

### 3.6 P1-9 会话 UI 渲染层（#16）

**新文件**（`core/app`）：

| 文件 | 职责 |
|---|---|
| `artificial/agent/AssistantMarkdown.kt` | 助手回复的 Markdown 渲染（markwon） |
| `artificial/agent/StreamingThrottle.kt` | 流式刷新节流（固定间隔 + 首末必刷） |
| `res/layout/item_tool_call.xml` | 工具调用卡片：折叠给摘要，展开给完整输入输出 |
| `src/test/.../StreamingThrottleTest.java` | 节流回归测试 10 条 |

**改动**：`AssistantMessageAdapter` 从单类型改为 `sealed class Item` 多类型
（`Message` / `ToolCall`）；`FloatingAssistantView` 的 `TOOL_STARTED`/`TOOL_FINISHED`
从追加纯文本行改为操作卡片；流式增量走节流；`core/app` 引入 markwon（版本已在
`libs.versions.toml` 声明，termux 模块已在使用，非新依赖）与 junit4（该模块首个单测）。

**关键设计决策**：

- **不做 40 个 tool-ui 文件的逐文件搬运**。`DETAILS.md` 的建议是「先做消息列表 + 一个
  通用工具卡片 + Markdown」，本次照此执行：卡片按工具名统一渲染，`factory` 机制
  （按工具类型给不同视图）等工具变多后再引入。
- **工具卡片替代纯文本过程行**。原来把工具调用拼成一行塞进列表，参数被截断、输出看不到、
  失败原因只有行内片段，用户无法判断「这次调用到底改了什么」。
- **卡片默认折叠**。一个长任务会产生几十次调用，全部展开会把真正的回答挤出屏幕；
  折叠态摘要足以判断「这一步在做什么」。
- **展开状态存在数据里而非 ViewHolder 里**。RecyclerView 会复用 ViewHolder，
  把展开状态放控件上会导致滚动后「展开的是另一条卡片」。
- **只有助手正文走 Markdown**。过程日志里大量出现路径与命令，渲染器会把下划线、
  星号当标记处理导致显示变形。`looksLikeMarkdown` 只认几乎不可能是普通文本的信号。
- **Markdown 解析失败回退纯文本**：模型偶尔输出不闭合的代码块围栏（流式过程中尤其），
  解析器可能抛异常。内容不完美也要让用户看到，而不是整条消息变空白。
- **Markwon 按 TextView 缓存**（WeakHashMap）：构造会解析插件并建内部解析器，
  在滚动列表里每次新建是明显浪费；不做全局单例是因为主题切换后旧实例带旧配色。
- **节流是「固定间隔 + 首末必刷」**：首个增量立即刷新（否则用户以为没生效），
  间隔内合并，`flush()` 保证末尾补刷——只做「丢弃间隔内增量」会让最后一段文本永远
  不显示，看起来像回答被截断了。**累积始终在数据层发生，只有界面刷新被节流。**
- **时间源注入**：直接读 `System.currentTimeMillis()` 会让测试只能靠 sleep，既慢又不稳定。
- **TOOL_STARTED/TOOL_FINISHED 用「最近一张运行中卡片」关联**，不要求协议层额外传 id
  （两者成对且顺序执行）。
- **取消与失败时收尾运行中的卡片**：否则卡片永久停在运行态，用户以为还在跑。
- **`colorError` 是框架 attr**（Material 的 `R.attr` 里没有），且主题可能未定义；
  取默认值 0 后回退到次级色，否则失败文本会变成透明的。

**刻意未做**：`ConversationTimeline` 的块编排、卡片 factory 注册表、思维链折叠视图、
LCP 的 `LineTheme` 移植（改用 ACS 现有主题）。这些属于「工具变多后再引入」的范畴。

**测试**：`StreamingThrottleTest` 10 条（首刷、合并、间隔后刷新、末尾必刷、
flush 幂等、reset、pending 追踪、200 增量下的刷新次数上界与下界）。
五个模块共 249 条全绿。

### 3.7 P0-6 补齐内置工具（#8）

**新增工具**（`core/ai-tool`）：

| 工具 | 说明 |
|---|---|
| `todo_update` | 维护任务待办列表；整表覆盖，状态同义词归一 |
| `web_fetch` | 抓取网页并剥离为可读文本 |
| `web_search` | 搜索网页；provider 可替换 |

**新增支撑**（`core/ai-tool`）：`TodoItem`、`TodoStateStore`（+ `inMemory`/`none`）、
`FileTodoStateStore`、`HtmlTextExtractor`、`HttpPort`（窄接口）、`RssSearchProvider`。
**app 层**：`AppHttpPort`（适配既有 `SimpleHttpClient`）；`AgentOrchestrator` 注册三个
工具、构造 `FileTodoStateStore`、把待办状态注入系统提示词；
`AgentPromptBuilder.build` 新增 `todoState` 参数。

**关键设计决策**：

- **`HttpPort` 收窄成接口而非直接依赖 `ai-protocol`**：`ai-tool` 要保持零依赖（除
  org.json）以便 JVM 单测。适配放在 app 层，安全边界仍只有一处——URL 策略、代理、
  超时都由既有的 `SimpleHttpClient` 处理，不会因为新增工具就绕过 `UrlPolicy`。
- **`HttpPort` 不提供 POST**：当前只有读取需求。不预留用不上的能力，免得日后有人拿它
  发未审查的写入请求。
- **待办必须进提示词**：只存不读等于没记。`renderForPrompt()` 返回空串时调用方跳过
  注入，避免提示词里出现没有内容的段落。
- **待办持久化但不用 append-only**：待办是「当前状态」而非「历史事件」，整表覆盖即可，
  保留历史无意义。与 `FileDiffStore` 的取舍相反，因为数据性质不同。
- **待办状态做同义词归一**：模型常写 `done`/`完成`/`doing`。严格拒绝会让它反复重试
  同一次调用；无法识别时退回 `pending` 而非报错——待办是辅助手段，不值得为它中断任务。
- **待办上限 50 条**：防止模型把整个需求文档逐条列进来把上下文撑爆。
- **`web_search` 默认给免密钥的 RSS provider**：需要 API key 意味着多数用户永远用不上
  （装好应用 → 搜索不可用 → 要先去注册拿 key）。RSS 端点结果质量不如商业 API，但让
  功能开箱可用；需要更好效果的用户可换 provider。
  **默认端点未经实测验证**（开发机无网络），端点可替换、解析器同时兼容 RSS 与 Atom。
- **HTML 剥离手写而非引入解析器**：只为一件事（取文本）引入 jsoup 不值得。
  两个真实 bug 由测试暴露并修复：①裸 `&lt;` 会吞掉到下一个 `&gt;` 之间的正文
  （「a &lt; b then done」丢掉 done）；②`&amp;` 先于其它实体解码会造成双重解码。
- **`script`/`style` 内容整体丢弃**：它们不是给人读的正文，回灌既占上下文又干扰理解。
- **空抓取结果要明说**：全靠 JS 渲染的页面抓下来是空壳，必须告诉模型「该页面依赖
  JavaScript」，否则它以为页面本来就是空的。
- **搜索区分「无结果」与「失败」**：前者模型应换关键词，后者应报告故障。

**未做**（`DETAILS.md` 列为中/低优先级，且各自依赖未完成项）：
`memory_update`（依赖 #12）、`agent`/`agent_pipeline`/`agent_output`（依赖 #4）、
`image_understanding`/`image_generation`（低优先级）。`Phone*` 与
`CustomAgentExtensionTool` 按原计划不移植。

**测试**：`HtmlTextExtractorTest` 15 条 + `NewBuiltinToolsTest` 34 条。
五个模块共 298 条全绿。

### 3.8 P1-12 服务商预设表与模型目录（#11）

**新文件**：`artificial/agent/ProviderPresets.java`（预设表 + 端点解析 + 模型反查）。

**改动**：`AgentModelConfigs` 不再持有服务商清单，`endpointFor` 委托给预设表；
`Agents` 的模型列表、`providerForModel`、默认 provider 与推荐模型改为读预设表；
`AIPreferencesFragment` 的服务商列表/显示名改为读预设表，并新增自定义端点的配置弹窗；
`ApiKey` 新增通用 OpenAI 兼容密钥槽位；设置页新增对应输入项。

**覆盖的服务商**（14 个）：claude、openai、grok、groq、deepseek、glm（智谱）、
kimi、qwen（通义千问）、minimax、siliconflow、openrouter、localllm、custom。
其中 12 个是 OpenAI 兼容协议——**新增同类服务商只需在预设表加一行**。

**关键设计决策**：

- **集中到一张表**。此前服务商信息散落在四处（端点映射、密钥读取、模型列表、UI 显示名），
  加一个服务商要同时改四处，漏掉任一处都表现为「设置里能选但请求失败」或「列表里看不到」。
- **`ProviderPresets` 不依赖 Android**：密钥通过 `ApiKeyLookup` 回调注入，因此预设表
  可在 JVM 上单测（21 条）。
- **修复了一个既有缺陷：默认服务商 `gemini` 无法工作。** 协议层只注册了
  `OPENAI_COMPATIBLE` 与 `ANTHROPIC_MESSAGES` 两种协议，而 Gemini 讲 Google 自有协议
  （`generateContent`）。默认值指向它意味着首次使用者在配好密钥后仍然**一条消息都发不
  出去**。默认改为 `deepseek`（OpenAI 兼容、国内可直连、有免费额度），并有测试钉住
  「默认服务商必须有已实现的协议」。
- **通用 OpenAI 兼容密钥共用一个槽位**：为 12 个兼容服务商各开一个偏好键会让「加一个
  服务商」重新变成改三处。具体用哪个由 `ai_provider_name` 决定。
- **`setAgent` 不再擅自重置服务商**：原实现在模型名匹配不到硬编码列表时把 provider
  重置为 `gemini`，于是用户选好新服务商后只要模型名不在旧列表里，provider 就被悄悄
  改掉——表现为「设置里显示的服务商不是自己选的那个」。
- **本地模型不要求密钥**：llama.cpp / Ollama / LM Studio 通常无鉴权。但仍填入占位值，
  避免请求头出现空 `Authorization`。
- **未实现 CODEX_RESPONSES 协议，因此不提供 `codex` 预设**：`ModelProtocolType` 里有这个
  枚举值，但 `ModelProtocolFactory` 未注册实现（未注册会静默回退到 OpenAI 兼容协议）。
  提供一个实际走错协议的预设比不提供更糟——用户会拿到难以归因的失败。
- **未做远程模型目录拉取**：需要新增网络调用与缓存，收益（模型列表自动更新）相对改动
  规模偏低；预设表已覆盖当前主流模型。

**测试**：`ProviderPresetsTest` 21 条（id 唯一、baseUrl/模型非空、远程必须要求密钥、
默认服务商有协议实现且仅凭密钥可用、本地无需密钥、自定义端点需用户填 baseUrl、
模型列表只读、标签回退）。两个 app 测试类共 31 条全绿。

### 3.9 P0-7 提示词模板系统 + 聊天模式（#10）

**新文件**（`core/ai-agent/.../agent/prompt/`）：

| 文件 | 职责 |
|---|---|
| `PromptRenderer` | `{{NAME}}` 占位符渲染（单趟替换、空白整理、拼写校验） |
| `PromptPlaceholders` | 占位符注册表与说明 |
| `PromptTemplates` | 默认模板文本（提示词从代码变成数据） |
| `PromptTemplateStore` | 模板存储接口（+ `inMemory` / `defaults`） |
| `ChatMode` | 四种对话模式 |

**app 层**：`PrefsPromptTemplateStore`（偏好实现）、`PrefsChatModeStore`（模式持久化）；
`AgentOrchestrator` 持有模板存储与模式存储，`run()` 注入模式与模型信息；
设置页新增「对话模式」与「系统提示词模板」两项（含占位符清单与拼写校验提示）。

**关键设计决策**：

- **提示词从硬编码变为数据**。此前改一个措辞要重新编译整个应用；现在用户在设置界面
  编辑后**下一次运行即生效**。
- **单趟替换**。替换进去的文本里若含 `{{X}}`（用户代码里就有这种字符串），不得被二次
  展开。多趟替换会让提示词内容可被数据影响——这是注入风险，不只是意外。
- **未提供的占位符替换为空串**，而不是像 `StringTemplate` 那样原样保留。用户删掉某段
  可选内容时，希望那一段整体消失；留下 `{{TODO_LIST}}` 会让模型看到无意义的字面量。
- **编辑处即显示占位符清单与拼写校验**。写错占位符名不会报错，只会静默变空串，
  表现为「模型行为莫名其妙」——这是最难排查的一类问题，必须在编辑时可见。
- **`TODO_LIST` 与 `TODO_SECTION` 必须分开**。实现中一度同名，导致段落模板里的
  `{{TODO_LIST}}` 解析到段落自身，清单永远注入不进去。测试暴露后拆成两个占位符。
- **CHAT 模式不给工具清单**。模型看不到工具名就不会「顺手调用」；只写「不要调用工具」
  而仍列出工具清单，纯对话模式形同虚设。
- **模式只影响提示词，不替代权限拦截**。真正的强制在 `ToolPermissionService` 的只读
  模式。两者互补：提示词引导模型不做，权限层保证它做不了。
- **模板读取失败一律回退默认**：用户手工编辑的模板可能损坏，甚至偏好文件本身读不出来。
  任何一种都不该让 AI 功能整体不可用——模板是优化手段，不是运行前提。
- **写空等同于恢复默认**，不留下「自定义为空」的中间态（否则 `isCustomized` 与
  `resolve` 的结论矛盾，UI 显示自相矛盾的状态）。

**未做**：模板导入导出、按会话绑定模式（当前是全局设置）。

**测试**：`PromptTemplateTest` 36 条（渲染语义、单趟替换、空白整理、占位符注册完整性、
模式语义与模板 ID 往返、存储回退、构建器在各模式下的行为、默认模板无未注册占位符）。
五个模块共 355 条全绿。

### 3.10 P0-5 MCP 客户端（#14）

**方向澄清**：这是**客户端**——本应用作为 host 调用外部 MCP server，不是把本应用
变成 server 供他人连接。

**新文件**（`core/ai-tool/.../tool/mcp/`）：

| 文件 | 职责 |
|---|---|
| `McpClient` | JSON-RPC 2.0 over HTTP：`initialize` / `tools/list` / `tools/call` |
| `McpToolInfo` | server 侧工具描述（名 / 说明 / inputSchema） |
| `McpToolAdapter` | 把远程工具包装成本地 `BaseTool` |

**改动**：`HttpPort` 新增 `postJson`（返回正文**与响应头**）；
`AppHttpPort` 实现之（走 `SimpleHttpClient.execute` 而非便捷方法，因为后者丢弃响应头）；
`AgentOrchestrator.registerMcpTools` 在构建注册表时拉取并注册；
app 层 `McpServers`（配置存储）+ 设置页「MCP 服务端」项。

**关键设计决策**：

- **`HttpPort` 必须返回响应头**。MCP 的会话 id 走 `Mcp-Session-Id` **响应头**下发，
  只返回正文的接口拿不到它，会话无法建立。这是实际需要而非预留能力。
- **新增 POST 而非复用 GET**：MCP 是 JSON-RPC over POST。此前 `HttpPort` 刻意只有
  GET（「不预留用不上的能力」），现在有了具体需求才加。
- **适配器而非改工具层**：`ToolRegistry` 只要求「实现 ToolInfo + 能执行」，MCP 工具与
  内置工具在这个抽象下没有区别。适配器让 MCP 完全落在工具层内，agent 循环、权限判定、
  卡片渲染都不需要知道这个工具是远程的。
- **工具名加 `mcpx_` 前缀**：远程 server 的工具可能与内置工具**撞名**（都叫
  `file_read`）。不加前缀会让内置工具被静默覆盖——模型以为在写本地文件，实际调用了
  远程服务。注册时若仍冲突则跳过，不覆盖。
- **MCP 工具一律要求确认**：远程工具行为不可预知（可能删数据、发请求），而用户配置它时
  未必想过这一点。默认要求确认比默认放行安全。归类为 `WRITE`，只读模式下不放行。
- **解析响应正文而非只看 HTTP 状态码**：JSON-RPC 的错误（方法不存在、参数非法）通常
  仍以 200 返回，错误在 `error` 字段里。只看状态码会把失败当成成功，拿到一个空结果。
- **正文不是 JSON 时把前 200 字符带进错误**：地址填错时打到的往往是网页，
  否则用户只看到「解析失败」而不知道该检查什么。
- **会话 id 缺失不算失败**：无状态 server 不下发它，把它当失败会排除一类合法实现。
- **`initialize` 幂等**：每次拉工具列表都重新握手是浪费，且某些 server 会因此换掉会话。
- **`notifications/initialized` 失败不阻断**：有些 server 不实现它，为此中断整个接入
  不值得。
- **只取文本内容块**：MCP 结果可能含图片块（大段 base64），序列化进上下文既撑爆窗口
  又对模型无用。
- **工具名规整**：MCP 允许比模型预期更宽的命名（点号、斜杠、空格），
  非 `[A-Za-z0-9_]` 的字符换成下划线，避免生成模型难以稳定复述的名字。
- **拉取失败跳过并记日志**：MCP 是可选扩展，某个 server 连不上不该让整个 AI 功能不可用。
- **每次运行重新拉取工具列表**：工具可能随 server 升级而变化，缓存会让用户改了 server
  后看不到新工具。代价是多一次网络往返，且仅在配置了 server 时发生。

**未做**：MCP 资源（resources）、提示词（prompts）、采样（sampling）、SSE 流式传输、
OAuth 鉴权（当前只支持无鉴权或自定义请求头）。

**测试**：`McpClientTest` 25 条（会话 id 捕获与回传、大小写不敏感的头查询、
`initialize` 幂等、无会话 id 容错、工具列表解析与跳过无名项、JSON-RPC 错误上报、
非 JSON 响应提示、文本块提取与非文本块过滤、参数透传、适配器前缀/规整/确认要求/
空输出提示/远程失败转 error 结果）。五个模块共 380 条全绿。

### 3.11 P0-8 长期记忆 + 本地 RAG（#12）

**新文件**（`core/ai-tool/.../tool/memory/`）：

| 文件 | 职责 |
|---|---|
| `MemoryEntry` | 一条记忆（正文 + 标签 + 时间） |
| `MemorySearch` | 分词与相关度打分 |
| `MemoryStore` | 持久化存储（整表重写 + 原子替换） |
| `MemoryUpdateTool` | `memory_update`：add / list / delete / clear |

**改动**：`AgentOrchestrator` 持有 `MemoryStore`，注册工具，并用**本次用户请求**作为
检索词把相关记忆追加到系统提示词；设置页新增「长期记忆」项（查看 / 逐条删除 / 清空）。

**关键设计决策**：

- **中文分词是这个功能的核心难点**。西方语言的「按空格切词」对中文完全失效——
  一整句会变成一个 token，只有逐字完全相同才匹配得上，检索等于不存在。
  这里对 CJK 采用**字符二元组（bigram）**：「数据库连接」→「数据/据库/库连/连接」。
  选 bigram 而非单字：单字会让「数据」与「据理力争」里的「据」互相匹配，噪声极大；
  bigram 保留相邻关系，质量足够好且只需一次线性扫描。不引入第三方分词器（依赖与体积）。
- **中英混排要断开**：进入 CJK 段前先把累积的西文词收掉，否则「使用Hilt注入」会把
  `Hilt` 与中文粘成一个 token。
- **稀有度加权**：命中词越多分越高，但按它在记忆集合中的稀有度加权——「的」这类
  高频词几乎不影响排序，专有名词权重很高。不用真实 IDF 公式是因为记忆条数很少，
  过度拟合权重反而更难解释。
- **只做显式保存，不做自动学习**（与 `DETAILS.md` 的建议一致）。判断「什么值得长期
  记住」很难，自动抽取会把一次性调试细节也存进去，而每条噪声都会进入后续每一轮的
  提示词——污染是累积的。工具说明里直接写明「不该存什么」。
- **注入时按当前请求检索而非全量注入**：记忆可达 200 条，全量注入会占满上下文并稀释
  重点。每次最多注入 8 条——记忆的价值在于精准补充，宁少勿滥。
- **必须提供查看与删除入口**：记忆会持续影响所有后续对话。用户看不到存了什么，就无法
  理解「AI 为什么知道这件事」，也无法纠正一条记错的内容。
- **整表重写而非 append-only**：记忆是「当前有效的知识集合」，条目会被删除与修改，
  保留历史没有意义且会让检索扫到已删除内容。与 `FileDiffStore` 的取舍相反，因为数据
  性质不同。
- **达上限时丢最旧的一条**，而不是拒绝新增：拒绝会让「记住这个」在长期使用后突然失效。
- **文件损坏时返回空记忆**：记忆丢失的代价远小于 AI 功能不可用。

**未做**：自动学习、对话索引（`conversation_index`）、工作记忆（`working_memory`）、
真实向量检索。当前是关键词检索 + 稀有度加权，对「显式保存的记忆条目」这个最小可用
范围足够；向量检索需要嵌入模型与向量存储，改动规模远超本项。

**测试**：`MemorySearchTest` 39 条（中文 bigram、英文小写归一、单字母词丢弃、中英混排、
标点与空输入、中英文检索、稀有词优先、标签参与检索、limit、空白条目跳过、多命中排序、
增删清空、跨实例持久化、损坏文件容错、条目上限、超长截断、提示词渲染、
工具的四种 action 与各错误路径、工具说明含「不该存什么」）。
五个模块共 419 条全绿。

### 3.12 P1-11 子 agent（#4）

**新文件**：`core/ai-tool` 的 `SubAgentRunner`（窄接口）、`AgentTool`；
`core/app` 的 `SubAgentRunnerImpl`（用嵌套 `AgentSession` 实现）。

**改动**：`AgentOrchestrator.run()` 在构建注册表后注册 `agent` 工具；取消令牌提前创建
（原先在 `maybeCompact` 之后），使子 agent 能挂在它上面随父级一起停。

**关键设计决策**：

- **子 agent 的价值是上下文隔离**。主 agent 派一个「搞清楚这个模块的依赖关系」出去，
  子 agent 可能读二十个文件——这些过程**不进主对话**，只有结论回来。若在主循环里做，
  二十个文件的全文会永久占据上下文窗口。
- **`SubAgentRunner` 是接口**：`ai-tool` 不能依赖 `ai-agent`（会形成循环），而「跑一个
  agent 循环」正是 `ai-agent` 的能力。接口放工具层、实现放 app 层，沿用既有的分层做法。
- **explore 模式的只读是结构性的**：只注册读类工具，因此它**拿不到**写工具。
  提示词可能被忽略，缺少工具不会。
- **两种模式都不注册 `agent` 工具本身**：子 agent 不应再派子 agent。让「不能委派」
  成为结构约束，比靠深度计数更可靠。
- **不注册 shell / 构建 / 安装工具**：副作用范围远超「完成一个子任务」，而子 agent 的
  任务描述通常不足以让用户判断该不该放行。
- **深度上限 2**：主（0）→ 子（1）→ 孙（2）已是「拆任务、再拆」的极限。再深一层收益
  远小于上下文与额度消耗，且模型在深层已很难判断该做什么。超限时明确拒绝并说明原因。
- **`mode` 默认 `explore`**：委派出去的子 agent 若默认能改文件，一个「帮我看看」的请求
  可能顺手改代码。
- **失败与空结论都转为 error 结果**：空结论当成成功会让主 agent 以为任务已完成而停止
  工作；失败则需回灌说明让它判断重试还是自己做。
- **取消必须向下传播**：父级被取消时子 agent 一起停，否则用户点了取消却仍在烧额度。
  用独立子令牌挂在父级上（子级取消不影响父级）。
- **子 agent 的对话不进主会话日志**：它的价值正是上下文隔离，把中间过程写进主日志会
  让下次运行的提示词里出现一堆噪声，反而破坏隔离的收益。
- **刻意不复用 `AgentPromptBuilder`**：主提示词是对话式措辞（「任务完成后说明你做了什么」），
  在子 agent 语境下会稀释焦点。子 agent 需要的是「完成这一个任务并给出结论」。
- **不实现 `agent_output`**：本次子 agent 是**同步**的——工具调用阻塞到子 agent 结束并
  直接返回结论。`agent_output` 是为异步子 agent（先返回句柄、再取结果）准备的，
  同步模型下没有存在意义。
- **不实现 `agent_pipeline`**：DAG 依赖 + `write_scope` 互斥是独立且复杂的一块，
  `DETAILS.md` 也建议后置。单子 agent 已能覆盖「隔离调查」这个主要收益。

**测试**：`AgentToolTest` 20 条（参数校验、深度上限、模式解析与默认值、结论返回、
失败/空结论/null 结果/异常四种失败路径的区分、元数据与 schema、进度上报）。
五个模块共 439 条全绿。

---

## 4. 待办（4 项未完成 / 16 项总计）

任务定义已迁入本会话 `TaskList`（#2、#3、#4、#5、#8、#10、#11、#12、#14、#15、#16、
#17 已完成，其余 pending）。

### P0 — 缺了 agent 能力不完整

| # | 任务 | 状态 | 阻塞于 | 关键依赖说明 |
|---|---|---|---|---|
| #2 | P0-1 会话持久化与多会话 | **已完成** | — | 4 个提交 |
| #3 | P0-2 上下文管理与压缩 | **已完成** | — | 见 §3.2 |
| #15 | P0-3 权限确认分级与持久化 | **已完成** | — | 见 §3.4 |
| #5 | P0-4 Diff 回滚与审查 | **已完成** | — | 见 §3.5；完整工具卡片仍归 #16 |
| #14 | P0-5 MCP 客户端 | **已完成** | — | 见 §3.10 |
| #16 | P1-9 会话 UI 渲染层 | **已完成** | — | 见 §3.6 |
| #10 | P0-7 提示词模板系统 + 聊天模式 | **已完成** | — | 见 §3.9 |
| #8 | P0-6 补齐内置工具 | **已完成** | — | 见 §3.7；memory/agent/image 系列归各自依赖项 |
| #12 | P0-8 长期记忆 + 本地 RAG | **已完成** | — | 见 §3.11；向量检索未做 |

### P1 / P2

| # | 任务 | 状态 | 阻塞于 |
|---|---|---|---|
| #4 | P1-11 子 agent / pipeline | **已完成** | — | 见 §3.12；pipeline 与 agent_output 未做 |
| #11 | P1-12 服务商预设表与模型目录 | **已完成** | — | 见 §3.8；远程模型目录拉取未做 |
| #6 | P1-13 Skill 系统 | pending | — |
| #13 | P1-10 消息操作与导出 | pending | #16 |
| #7 | P1-14 自定义 Agent 扩展 + P1-15 Slash 命令 | pending | — |
| #9 | P2 收尾项（日志/归档/代理/输入/主题） | pending | — |
| #17 | 项目界面 AI 悬浮助手 + 界面文案中文化 | **已完成** | — | 见 §3.3 |

> **依赖修正记录**：任务 #8（原 #22）最初被设为 `blockedBy=[20,23,24]`，但
> `todo_update`/`web_fetch`/`web_search` 三个工具的实现本身不需要 UI 或记忆，已放宽。
> 只有卡片渲染依赖 #16。

### 依赖图（决定实施顺序）

```
#2 会话持久化 ──┬──> #3 上下文压缩   ✔ 已完成
                ├──> #16 会话 UI      ✔ 已完成
                ├──> #5 Diff 回滚     ✔ 已完成（能力层 + 最小 UI 入口）
                └──> #12 长期记忆（记忆需要会话索引）
#15 权限确认 ───┴──> #16（确认交互在卡片里）  ✔ 已完成
#5 Diff 回滚 ───────> #16（回滚按钮在卡片里）  ✔ 已完成
#14 MCP ────────────> 独立（但卡片需能渲染 MCP 工具）
#17 悬浮助手 ───────> 独立（纯 UI + 复用 AgentOrchestrator）  ✔ 已完成
```

**关键结论（已执行）**：`#5/#15/#16` 强耦合，逐项做会反复改同一批 UI 代码。
本次按「工具层能力 → UI 渲染层」的顺序推进，UI 只改一次：
#15 的按粒度授权、#5 的回滚能力都在工具层落地，#16 在渲染层一次性接上
（卡片展示 diffId + 撤销按钮 + 确认交互）。

---

## 5. 建议的下一步

阶段一（数据层地基）、阶段二（渲染层 + 交互）**均已完成**（#2/#3/#5/#15/#16/#17）。
剩余项中互相独立，可并行：

- **#14 MCP 客户端**——纯 Java 可独立单测，与 UI 无耦合，适合先做。
- **#11 服务商预设表与模型目录**——改动小、收益直接（新增服务商不必改代码）。
- **#10 提示词模板系统**——占位符机制可先做，收益立竿见影。
- **#12 长期记忆 + 本地 RAG**——需要会话索引，依赖已就绪（也是 `memory_update` 的前置）。

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

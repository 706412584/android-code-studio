# P0-1 会话持久化 — 方案

> 参考实现：`C:\Users\70641\cc-haha`（Claude Code 类 harness）
> 关键目录：`src/server/services/localIndex/`
>
> **一句话**：JSONL 为唯一真源（append-only），派生索引可选后置。
> 不引入 SQLite/Room。

---

## 1. 为什么不用 LCP 的 SQLite 方案

| 考量 | SQLite（LCP） | JSONL（本方案） |
|---|---|---|
| ACS 现状 | **零数据库设施**（无 Room / 无 SQLiteOpenHelper / 无 `android.database` 引用） | 只需 `File` + `org.json` |
| 与既有架构 | 冲突：存储层依赖 Android，无法 JVM 单测 | 一致：纯 Java 可单测（现有 58 项测试的基础） |
| 写入模式 | 需事务 + 迁移 | append 一行，天然崩溃安全 |
| 迁移 | 需迁移类（LCP 有 4 个） | 无需：新条目类型直接加，旧行不受影响 |
| 用户可读 | 需工具 | 可直接 grep / 在 ACS 里打开查看（IDE 场景是加分项） |
| 查询能力 | 强（JOIN / FTS4） | 弱 → 需要时再加派生索引 |

**LCP 自己也没完全依赖规范化**：`conversations` / `messages` / `message_blocks` / `tool_calls` /
`tool_results` **每张表都带 `raw_json` 列**。即它已经是「规范列 + JSON 逃生口」的混合模式，
纯 SQLite 的收益被这个逃生口抵消了大半。

> 注：`DETAILS.md#P0-1` 写的「ACS 已有自己的持久化设施，应定义等价的消息/会话模型，
> 用 ACS 的方式存储」——经核实，ACS 的"持久化设施"只有 SharedPreferences + 文件。
> 本方案即为该结论下的具体落地。

---

## 2. 从 cc-haha 借的 8 个机制

这 8 条都是针对**真实问题**的解法，不是形式。逐条对应到 ACS 的落地。

| # | 机制 | cc-haha 位置 | 解决什么问题 | ACS 落地 |
|---|---|---|---|---|
| 1 | **append-only JSONL，一会话一文件** | `<sessionId>.jsonl` | 每加一条消息不重写整个文件；崩溃只损坏最后一行 | `filesDir/ai/conversations/<id>.jsonl` |
| 2 | **条目自描述信封**（`type` + `uuid`） | 每条 line 带 `type`/`uuid`/`timestamp` | 免 schema 迁移：新类型直接加 | `ConversationEntry{type,uuid,parentUuid,timestamp,...}` |
| 3 | **字节偏移索引** | `session_entries.byteStart/byteLength` | 可按偏移直接 seek，不必重解析全文 | 读取时记录每条 `byteStart` |
| 4 | **增量水位线** | `source_files.indexed_bytes` | 恢复时只读新增尾部，不重扫全文件 | 存 `indexedBytes`，seek 后读 tail |
| 5 | **残缺尾部处理** | `readCompleteJsonlRange` 只解析到最后一个 `\n` | 写入中途崩溃留下的半行不会污染状态 | 只消费完整行，余下作为 pending tail |
| 6 | **指纹窗口判变** | `sourceFingerprint.hashWindow()`：对**截止到 `indexedBytes` 的末尾 64KB**（`FINGERPRINT_WINDOW_BYTES = 64 * 1024`）做 sha256 | 区分「纯追加」与「被重写/截断」；后者必须重建索引 | 指纹不符 → 重建该会话索引 |
| 7 | **摘要由 fold 派生，不存列** | `transcriptReducer` → `summaryFromState` | 改标题/统计不必 UPDATE 表 | 折叠条目得标题/条数；标题优先级见下 |
| 8 | **摘要缓存独立** | `sessions` 表（派生数据） | 列表页不必每次折叠全部文件 | `conversations/index.json` 缓存摘要 |

### 标题优先级（照搬机制 7）

```
custom-title  >  goal-title  >  ai-title  >  首条用户消息  >  "Untitled Session"
```

推论：**重命名 = 追加一条 `custom-title` 条目**，永不回写历史。这也是 append-only 的前提。

---

## 3. 条目模型（最小集）

一条 line 一个 JSON 对象。字段命名与 cc-haha 对齐，便于日后对照。

```
session-meta   { type, uuid, timestamp, conversationId, cwd, model, permissionMode }
user           { type, uuid, parentUuid, timestamp, message: { role:"user", content } }
assistant      { type, uuid, parentUuid, timestamp,
                 message: { role:"assistant", content, reasoningContent, toolCalls:[…] } }
tool-result    { type, uuid, parentUuid, timestamp, toolCallId, toolName, content, isError }
custom-title   { type, uuid, timestamp, customTitle }
ai-title       { type, uuid, timestamp, aiTitle }
compaction     { type, uuid, timestamp, summary, upToOrdinal }   ← 为 P0-2 预留
```

设计要点：

- **`parentUuid`** 让消息成链。P1-11 子 agent 分叉时不必改格式（cc-haha 的 `isSidechain` 同理）。
- **`compaction` 现在只定义不实现**。P0-2 压缩时只需追加该条目并让读取端跳过被压缩区间——
  不需要改写历史。这是把 P0-2 的改动面压到最小的关键。
- **不做 `message_text_chunks`**。LCP 拆 chunk 是为了流式落盘；ACS 的流式在 UI 层（P1-9），
  落盘按轮次整条写即可。等 P1-9 真的需要逐字恢复再加，属于过度设计。

---

## 4. 模块落位

沿用既有的**窄接口注入**模式（同 `ShellBackend` / `DiffStore` / `ToolSettingsPort`）：

```
core/ai-agent/src/main/java/com/tom/rv2ide/ai/agent/conversation/   ← 纯 Java，可 JVM 单测
├── ConversationEntry.java      条目信封 + 各类型子类/工厂
├── ConversationCodec.java      JSON <-> 条目（org.json，compileOnly）
├── ConversationLog.java        append / 按偏移读 / 水位线 / 残缺尾部
├── ConversationSummary.java    派生摘要（标题、条数、时间）
├── ConversationReducer.java    折叠条目 -> 摘要
└── ConversationStore.java      窄接口：list/create/rename/delete/append/read
```

app 层实现（依赖 Android 的部分全在这里）：

```
core/app/.../artificial/agent/store/FileConversationStore.java   ← 基于 filesDir
```

**为什么不新建 `core/ai-conversation` 模块**：条目模型与 `AgentSession` 同生命周期，
放 `ai-agent` 内可避免多一个模块的构建开销；且 `ConversationStore` 接口保持纯 Java，
app 层实现，边界与现有模式一致。

---

## 5. 与 AgentSession 的接线

**当前障碍**（已核实 `AgentSession.java:107-109`）：

```java
List<ModelMessage> messages = new ArrayList<>();
messages.add(new SystemModelMessage(systemPrompt));
messages.add(new UserModelMessage(userRequest));
// 每轮全新起列表 → 单轮无历史
```

**改法**（最小改动，不破坏现有签名）：

1. 新增重载 `run(config, systemPrompt, userRequest, history, toolContext, token, listener)`，
   `history` 为已折叠的历史消息；旧签名委托给新签名并传空列表 → **现有 12 项测试不受影响**。
2. 每轮结束后把 `AssistantModelMessage` 与各 `ToolResult` 追加进 `ConversationLog`。
3. 恢复会话时：读 log → 折叠出 `List<ModelMessage>` → 作为 `history` 传入。

`AgentOrchestrator` 持有 `ConversationStore`，`ChatFragment` 的"清空会话"改为
"新建会话"（语义更准，也顺带让多会话可见）。

---

## 6. 分步与验收

每步独立可验证，前 3 步纯 Java 单测即可闭环。

| 步 | 内容 | 验收 |
|---|---|---|
| **1a** | 条目模型 + Codec + `ConversationLog`（append / 按偏移读 / 水位线 / 残缺尾部） | JVM 单测：往返一致（含中文）、偏移 seek 正确、半行丢弃、水位线增量读、前缀哈希不符触发重建 |
| **1b** | `ConversationStore` 接口 + `FileConversationStore`（app 层） | 新建/切换/重命名/删除；重命名只追加不重写 |
| **1c** | `AgentSession` 历史重载 + 接线 `AgentOrchestrator` | 现有 12 项测试仍绿；新增"历史注入后能续答"单测 |
| **1d** | 最小会话列表（新建/切换/重命名/删除入口） | 真机：发消息 → 杀进程 → 重启 → 消息仍在 |

> **1d 与 P1-9 的边界**：1d 只做"能选中/管理会话"的最小入口，不做消息气泡渲染。
> 气泡、Markdown、工具卡片归 P1-9。`AIHistoryFragment` **不可复用**——它列的是
> `UnifiedModificationAttempt`（文件改动记录），语义完全不同（已核实 `AIHistoryFragment.kt:85`）。

**后置（明确不做）**：SQLite 派生索引。等 P0-8 需要 FTS 全文检索时再加，
且届时只加"索引"——JSONL 仍是唯一真源，索引可随时重建。

---

## 7. 风险与对策

| 风险 | 对策 |
|---|---|
| 长会话文件无限增长 | `compaction` 条目（P0-2）折叠历史；另设条数/体积上限后提示归档 |
| 手机存储 IO 慢 | 增量水位线（机制 4）保证只读新增；写入在 IO 线程 |
| 并发写同一会话 | 单会话同时只允许一个 agent 运行（`AgentOrchestrator` 已有运行中互斥）；文件层加锁兜底 |
| 指纹窗口大小 | 取 cc-haha 同值：64KB（`FINGERPRINT_WINDOW_BYTES = 64 * 1024`）——窗口越大越不易被"同长改写"骗过 |
| org.json 在纯 Java 模块 | 沿用既有做法：`compileOnly`（Android 运行时提供）+ 测试用 `org.json:json` |
| UTF-8 | 模块已固定 `options.encoding = "UTF-8"`（移植期踩过 GBK 损坏中文测试字面量的坑） |

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

## AI Agent 模块（`core/app/.../artificial/`）

改动此模块前注意：

- 6 个 provider（Gemini/OpenAI/Anthropic/Grok/DeepSeek/LocalLLM），各为独立类实现 `AIAgent` 接口
- 协议核心：`WritingRules.kt` 定义的 `FILE_TO_MODIFY:` 标记 + `SnippetParser` 解析
- 上下文注入方式为**全项目文件全量灌入**（`ProjectData.showProjectTree` + `readRelevantFiles`），
  无检索、无裁剪、无 token 预算控制
- 权限开关在 `AIAgentManager` 构造时被强制设为 `write=true, confirm=false`（`AIAgentManager.kt:57-58`），
  `AIPermissionDialog` 的确认弹窗当前无调用点

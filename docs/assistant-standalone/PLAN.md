# 剥离 AI 助手为独立通用助手 —— 可行性方案

**分支**：`feat/assistant-standalone`（长期分支，不动 `dev`）
**目标**：把 AI 助手从 ACS（Android Code Studio）剥离成独立的通用助手 App，
**保留终端（Termux）能力**，**暂不移植 SDK / 构建链**（Gradle Tooling、APK 安装、
设备控制、影子屏、云编译、CodeGraph 等）。
**参考项目**：`D:/app/Aharou`（设置与界面可直接移植）

---

## 一、实测数据（本分支上量出来的，不是估算）

### 1. 内核 4 模块：可直接剥离 ✅

| 模块 | 项目依赖 | 库依赖 |
|---|---|---|
| `core/ai-tool-api` | 无 | 无（仅测试用 org.json + JUnit） |
| `core/ai-protocol` | `ai-tool-api` | 无 |
| `core/ai-tool` | `ai-tool-api` | 无 |
| `core/ai-agent` | `ai-protocol` + `ai-tool` | 无 |

全部是 **Java/Kotlin JVM 模块**（`build.gradle.kts` 只有 `api(projects.*)`，
无 `android` 插件、无 androidx、无 AGP）。当前 **2177 条单元测试**在 JVM 上跑。

结论：**内核剥离成本 ≈ 0**。新建 Android App 模块后直接 `implementation(projects.core.aiAgent)`
即可。

### 2. App 层：227 文件 / 73391 行，其中真耦合很少

| 分类 | 文件数 | 占比 |
|---|---|---|
| **零 ACS 依赖**（可直接搬） | **155** | 68% |
| 有 ACS 依赖（需适配） | 72 | 32% |

而这 72 个的依赖性质分布（**注意第一项**）：

| ACS 包 | 次数 | 性质 |
|---|---|---|
| `resources` | 37 | **全部是 `R` 类**，非真耦合 |
| `projects` | 8 | 构建服务（可排除） |
| `cloudbuild` | 6 | 云编译（可排除） |
| `activities` | 5 | Activity 宿主（需替换） |
| `lsp` | 5 | 语言服务器 Port |
| `termux` | 4 | **要保留** |
| 其余 | 各 1–5 | 端口适配器 |

**关键结论**：37 处 `resources` 全部是 `import com.tom.rv2ide.resources.R`
（资源 ID 表）。Android 库模块会自己生成 `R`——换一个 `R` 引用即可，
**不是真实架构耦合**。剔除后，真正需要在剥离时处理的 ACS 依赖只剩约 **35 处**。

### 3. `AgentOrchestrator`（App 层 AI 中枢，3111 行）

- 顶层 `import com.tom.rv2ide.*` 中**非 AI 类型的数量 = 0**
- 全文用全限定名引用的 ACS 类型**只有 11 处**，且**全在 Port 适配器内**：
  `lsp.api.ILanguageServerRegistry`(4)、`projects.builder.BuildService`、
  `services.builder.BuildConnectionRegistryHolder`、`services.builder.BackgroundBuildManager`、
  `projects.IProjectManager`、`logging.IdeLogBufferHolder`、`utils.*`(4)

也就是说：**编排器本身对 ACS 的耦合已经被 Port 模式消化干净了**——它只知道
「诊断端口」「构建变体端口」这些中性契约，具体实现才碰 ACS 类型。

### 4. 「保留终端」的真实成本

`termux/*` 4 个模块自身依赖 ACS 重资产：

| 模块 | 依赖 |
|---|---|
| `termux/shared` | `core.common`、`core.resources`、`termux.view`、`utilities.buildInfo` |
| `termux/application` | `core.projects`、`core.common`、`core.resources`、`termux.view` |
| `termux/view` | `termux.emulator`、`core.resources` |
| `termux/emulator` | 无 |

而 AI 侧真正只用**两个** Termux 类型：
- `TermuxShellBackend` → `com.termux.app.TermuxService`（`termux/application`）
- `ReportedTerminalPort` → `TermuxShellManager` + `ShellUtils`（`termux/shared`）

**这就是冲突点**：`termux/application` 反向依赖 `core.projects`（Gradle 构建），
而用户要求「暂不放 sdk」。两条路（见下方决策点 A）。

### 5. 用户要求「暂不移植」的能力 → 需替换为空实现

可排除的文件（23 个，按目录/文件名匹配）：

```
artificial/cloudbuild/           云编译
artificial/hotupdate/            热更新
artificial/agent/codegraph/      CodeGraph
artificial/agent/tool/u2/        uiautomator2
artificial/agent/tool/vdisplay/  影子屏
artificial/agent/tool/device/    设备控制
GradleBuild / InstallApk / Logcat / LogWire / GitHubActions /
BuildVariant / PhoneTest / Vscreen / Launcher / IdeLog /
Diagnostics / WorkspaceSymbol / CodeActions / FormatCode /
LspNavigate / EditorState / CodeGraph / Termux / Shizuku / RootShell
```

排除后仍需搬运：**204 文件 / 66132 行**。

> 这 66k 行不是全要重写——155 个文件零依赖直接拷，其余只是替换 `R` 与 Port 实现。

---

## 二、目标结构

```
assistant/                          # 新建独立工程（或本仓库新模块树）
  ai-tool-api/  ai-protocol/        # 从 core/ 原样搬（零依赖）
  ai-tool/      ai-agent/
  app/                              # 新建 Android App
    com.assistant.app.ui/           # 155 个零依赖 UI 文件直接拷
    com.assistant.app.port/         # Port 的空实现 + Termux 实现
    com.assistant.app.settings/     # 从 Aharou 移植设置界面
core/                               # 原仓库保留，不动
```

**保留**：Compose 消息渲染全链路、会话 JSONL 持久化、工具调用循环、
上下文裁剪/压缩、子代理与跨会话消息（刚做完的 `send_message`）。

**替换为桩**：`DiagnosticsPort` / `BuildVariantPort` / `LspNavigationPort` /
`CodeActionsPort` / `CodeFormatPort` / `EditorStatePort` / `WorkspaceSymbolPort`
/ `IdeLogSource` / 构建类工具——全部报「当前环境未接入」，与既有降级文案一致
（各 Port 已定义 `isAvailable()` / `unavailableReason()`，语义现成）。

**保留终端**：`TermuxShellBackend` + `ReportedTerminalPort` 依赖 `termux/*` 模块。

---

## 三、必须由你决定的三个点

### 决策点 A：Termux 怎么带？（影响最大）

| 方案 | 做法 | 代价 |
|---|---|---|
| **A1 整体搬 termux 模块** | 把 `termux/{shared,view,emulator,application}` 连同其依赖的 `core/{common,resources,projects}` 一起搬 | 等于把「sdk 依赖」也搬进来（`termux/application` → `core.projects`），与「暂不放 sdk」冲突 |
| **A2 只搬 emulator + view + 自建 service** | 搬 `termux/{emulator,view}`（这两不依赖 ACS），自建一个精简 `TermuxService`；`shared` 里需要的 `ShellUtils` 等按需摘取 | 中等：要摘 `termux/shared` 的少量类，但能彻底断开 `core.projects` |
| **A3 终端用系统 shell** | 不搬 Termux，用 `Runtime.exec` / 自建 PTY | 最低成本，但**丢掉**你要求保留的「现在的 tex 终端能力」 |

> 我倾向 **A2**：能同时满足「保留终端」与「不放 sdk」。但 `termux/shared` 是个
> 大模块（113 文件），摘取需要逐类判断，是本次最大的工作量来源。

### 决策点 B：独立工程 vs 同仓库新模块

| 方案 | 优点 | 缺点 |
|---|---|---|
| **B1 独立 git 仓库** | 彻底与 ACS 解耦；不污染主分支 | 内核要复制一份，**两边修 bug 会双改**（刚修的 send_message 就是例子） |
| **B2 同仓库新模块树** | 内核仍共享 `core/ai-*`，修一处两边受益 | 仓库继续变大；仍需保证新模块不反向依赖 ACS |

> 我倾向 **B2**：内核本来就是零依赖的，共享它没有代价；而「剥离」的实质是
> **App 层不依赖 ACS**，这靠新模块 + 构建约束就能保证。

### 决策点 C：设置界面移植范围（Aharou）

Aharou 的设置页在 `desktop/` + `app/src/main/java/com/aharou/feature/settings/`。
需要你圈定要哪些：

- 服务商/模型配置（14 预设 + 4 槽位）
- 权限与确认策略
- MCP server 管理
- Skill / 自定义 agent / 长期记忆
- 界面主题（4 套预设）
- 定时任务

---

## 四、建议的推进顺序（每步可独立验证）

| # | 内容 | 验证方式 |
|---|---|---|
| 1 | 新建 `assistant/app` Android 模块，接 `core/ai-agent`，跑通「发一条消息拿到回复」 | 编译 + 真机 |
| 2 | 搬 155 个零依赖文件（Compose 渲染层），替换 `R` 引用 | 编译 + 真机看渲染 |
| 3 | Port 桩化（9 个 Port 返回未接入），验证工具列表正确降级 | 单测 + 真机 |
| 4 | Termux 接入（按 A2），验证能跑 shell 并读回输出 | 真机 |
| 5 | Aharou 设置界面移植 | 真机逐项 |
| 6 | 会话持久化 / 子代理 / 跨会话消息回归 | 既有单测搬过来 |

**建议先做第 1 步**：它不动任何现有代码，能立刻证伪或证实「内核可独立构建」。

---

## 五、风险

1. **双头维护**（若选 B1）：内核修一处要改两个仓库。B2 可规避。
2. **Termux 摘取**：`termux/shared` 113 文件互相牵连，A2 方案需要逐类判断，
   是本方案最不确定的部分。
3. **`R` 类替换**：37 处集中在 strings/资源 ID，机械但量大，容易漏。
4. **Compose 依赖版本**：ACS 被钉在 Compose 1.8（`build.gradle.kts` 有 ABI 天花板
   注释），新工程若用更新版本，渲染层可能需要适配。
5. **`libs.versions.toml` 共享**：若选 B2，新模块要复用同一版本目录，避免两套。

---

## 六、第 1 步实测结果（已完成 ✅）

在 `feat/assistant-standalone` 分支上建了 `assistant/app` 模块并实测。

### 构建验证

```
./gradlew :assistant:app:assembleDebug   → BUILD SUCCESSFUL
产出 app-debug.apk（1.2 MB）
```

### 依赖闭包（关键证据）

```
:core:ai-agent  ← :core:ai-protocol  ← :core:ai-tool-api
                ← :core:ai-tool      ← :core:ai-tool-api

非 ai-* 的项目依赖：无
```

**没有任何 ACS 模块**（无 `core:projects` / `editor` / `java` / `xml` / `tooling` /
`termux` / `utilities`）。`assistant/app/build.gradle.kts` 的 `dependencies` 里
唯一的内核引用就是 `implementation(projects.core.aiAgent)`。

### 真机运行验证（黑鲨 SKW-A0 / Android 10）

装包启动后 `ProbeActivity` 报告：

```
内核独立消费：通过
✓ ToolRegistry: 1 个工具
✓ ToolExecutor: 构造成功
✓ FileConversationStore: 会话存储就绪：/data/user/0/com.assistant.app/files/ai/conversations
✓ AgentSession: 构造成功
✓ AgentEvent 枚举: 类型数 = 14
✓ ToolContext: 构造成功
```

**这一步比「编译通过」有意义得多**：内核是 `java-library`（JDK 17 编译、经
`options.release = 8` 锁到 Java 8 API 面），编译过不代表真机能跑
（`NoSuchMethodError` 只在设备上暴露）。六个内核类型全部实例化成功，说明
内核的字节码与 API 面在 Android 上确实可用。

### 过程中踩到的两个坑（后续步骤要注意）

1. **不能引 appcompat / material**：`libs.androidx.appcompat` 在离线模式下缺
   `error_prone_annotations` / `constraintlayout-core` 缓存；且 material 的
   `Theme.Material3.*` 会让 AAPT 报资源找不到。探针改用框架自带的
   `android.app.Activity` + `@android:style/Theme.DeviceDefault.Light` 即可——
   结论也更干净（少一个无关依赖干扰）。
2. **jvmTarget 必须对齐**：仓库约定是 `BuildConfig.javaVersion = 11`，
   我最初写 17 导致 Kotlin 插件报 "Inconsistent JVM-target compatibility"。

### 结论

**剥离的核心前提成立**：内核可被一个完全不依赖 ACS 的 Android App 消费。
第 2 步（搬 App 层）不再是「能不能做」的问题，而是「搬多少、怎么搬」的工程问题。

剩余决策（见第三节）未定，第 2 步之后的分叉仍取决于它们。

---

## 七、第 2 步前置：修正后的真实边界 + Compose 离线验证 ✅

### 7.1 修正：之前的「155 文件零依赖」偏乐观

第六节那份数据只统计了**每个文件自身的 import**，没算传递依赖；而且
「`com.tom.rv2ide.ai.*` 是内核」被误算成 ACS，同时漏了 Kotlin 顶层函数的 import
（`import ...markdown.MarkdownContent` 指向的是 `MarkdownContent.kt` 文件而非类）。

建 import 图重算（含传递闭包、正确区分内核与 ACS）后的真实数字：

| 指标 | 修正前 | **修正后** |
|---|---|---|
| 零 ACS 依赖 | 155 | **142** |
| 触及 ACS | 72 | **85** |

`R` 引用也从 37 修正到 **49**（原先按 import 统计，漏了全限定名写法）。

### 7.2 触及 ACS 的类型分布（85 个文件）

| 阻塞类型 | 文件数 | 性质与处置 |
|---|---|---|
| `R` / `string` | **40** | 机制性——模块自带 `R` 即可，非架构耦合 |
| `prefManager`、`DevOpsPreferences` | 7 | 设置读写 → Aharou 有对标实现 |
| `ILanguageServer*` | 4 | 语言服务 → 按「暂不放 SDK」排除，Port 桩化 |
| `AssistantMessageAdapter` | 3 | ACS 数据适配层 → 需重写薄层 |
| `CloudBuildService` / `BuildService` | 5 | 构建链 → 排除 |
| `BaseApplication` / `IdeLogBufferHolder` / `IProjectManager` | 6 | 全局单例 → 注入替换 |
| 其余 | 各 1–2 | 端口适配器 |

### 7.3 最小竖切（以 `AssistantComposePanel` 为根）

`AssistantComposePanel.kt`（1034 行，渲染层入口）**自身只依赖一个 `R`**。
按 import 图展开其传递依赖：约 **23 文件 / 9864 行**
（若只按类名匹配会低估到 5 文件——那是脚本缺陷，不是真值）。

> 静态分析的边界**只作参考**：真正可搬的最小集由编译器决定
> （把文件拷过去编译，缺谁补谁）。这也是第 2 步的推进方式。

### 7.4 Compose 依赖离线解析：已验证 ✅

在 `assistant/app` 里引入与 `core/app` **完全相同**的 Compose 坐标，离线构建通过。
这套组合是剥离的必需品，且有几个**不看就会踩**的点：

| 依赖 | 为什么必须有 |
|---|---|
| `material-icons-extended:1.7.8` | material3 1.3.1 传递依赖 `material-icons-core:1.6.0`，而本机缓存只有 1.7.8。显式引 extended 由版本冲突顶掉 1.6.0——**core/app 能离线构建靠的就是这一行** |
| `activity-compose:1.9.3` | 同理：不显式引会去解析未缓存的 `activity-ktx:1.7.0` / `activity-compose:1.8.2` |
| `feather:1.1.1` | Aharou 组件直接引用 `compose.icons.FeatherIcons` |
| markdown-renderer **0.33.0** | **不能升**。0.34+ 调用 Compose foundation 1.8 的 `TextAutoSize`，而项目钉 1.7.6——编译期完全不报错，**运行时渲染 Markdown 段落**才抛 `NoSuchMethodError` |

### 7.5 三个决策已定（用户确认）

- **A2**：只搬 `termux/{emulator,view}`（这两个不依赖 ACS），自建精简 service，
  从 `termux/shared` 按需摘类 → 彻底断开 `core.projects`
- **B2**：同仓库新模块树（内核零依赖，共享无代价；「剥离」的实质是 App 层不依赖 ACS）
- **C**：设置界面**全要**（服务商配置 / 权限策略 / MCP / Skill / 记忆 / 主题 / 定时任务），
  从 Aharou 移植

### 7.6 下一步

第 2 步按「拷文件 → 编译 → 补缺」推进，不做静态边界猜测。
先搬 `AssistantComposePanel` 的传递闭包，跑通一个能渲染出消息面板的竖切，
再逐批扩到完整渲染层。

---

## 八、第 2 步实测结果（已完成 ✅）

建了 `assistant/ui`（`com.android.library`，namespace `com.assistant.ui`），
把 `core/app/.../artificial/agent/compose` 整目录 **75 文件 / 约 24k 行**搬过来，
包名统一改写，编译通过，并在黑鲨 SKW-A0 上跑通渲染。

### 8.1 静态分析低估了多少

| 项 | 静态分析估 | **实际编译错误** |
|---|---|---|
| 编译错误总数 | — | **699** |
| 其中 `R` 相关 | 49 处 | **339** 处 |
| 缺依赖（serialization / jsoup / coil / androidx.core） | 未识别 | 4 类 |
| ACS 类型引用 | 8 类 | 6 类（`DiffResult`/`DiffLineType`/`ToolResultImageSupport`/`AssistantMessageAdapter`/`AssistantUiStyleStore`/`AssistantToolGroupSummary`） |

> 静态分析只看到「文件里出现的名字」，看不到 `R.attr.*` 全限定写法、看不到
> `RecyclerView` 这类间接依赖，也看不到「类型在但符号不在」的情况。
> 结论仍是那条：**边界由编译器决定，不由静态分析决定。**

### 8.2 六类 ACS 引用的处置

| ACS 类型 | 处置 |
|---|---|
| `com.tom.rv2ide.resources.R` / `com.tom.rv2ide.R` | 本模块自带 `res/`：字符串 280 条（脚本按引用清单从 core/resources 抽取，非人工挑选）+ `jetbrains_mono.ttf` + 19 个 provider logo。`R.attr.colorXxx` 改为按名字 `getIdentifier` 解析（不引 material 主题依赖） |
| `AssistantMessageAdapter`（2405 行 RecyclerView 适配器） | **重写为 `MessageTimeline`**（纯数据层，无 View 类型）。逐字搬了全部数据变更逻辑与踩坑注释，去掉 ViewHolder / ViewBinding / DiffUtil 部分 |
| `DiffResult` / `DiffLineType` / `AssistantDiffBuilder` | 拷进 `model/AssistantDiff.kt`（原文件本身零依赖，只改包名） |
| `ToolResultImageSupport` | **拆开**：纯判定逻辑（decode/cacheKey/上限）搬进 `compat/ToolImageSupport.kt`；XML 灯箱（`R.layout.dialog_tool_image_gallery` + `ScaleGestureDetector`）**不搬**，改用 Compose 的 `detectTransformGestures` + Coil 重写 |
| `AssistantUiStyleStore`（272 行） | 拷进本模块（只依赖 `Context` + `SharedPreferences`） |
| `ToolGrouping`（104 行） | 拷进 `model/`（只依赖内核的 `ToolNames`） |

### 8.3 依赖闭包（关键证据）

```
:assistant:app → :assistant:ui → :core:ai-agent → {ai-protocol, ai-tool} → ai-tool-api
```

`./gradlew :assistant:app:dependencies --configuration debugRuntimeClasspath` 输出里
**没有任何非 `ai-*` 的项目依赖**——无 `core:projects` / `editor` / `java` / `xml` /
`tooling` / `termux` / `utilities`。

库依赖全部是通用 AndroidX / Compose / Coil / jsoup / kotlinx-serialization，
与 ACS 无关。

### 8.4 真机验证（黑鲨 SKW-A0 / Android 10）

APK 20 MB，装包启动 `ChatDemoActivity` 后截图确认：

| 渲染项 | 结果 |
|---|---|
| 用户消息气泡（含行内代码） | ✅ |
| 思考块（折叠标题 + 展开正文） | ✅ |
| 工具组（折叠态「2 次工具调用」→ 展开列出两张卡片） | ✅ |
| Markdown 表格（边框 + 单元格底色） | ✅ |
| 代码块（语法高亮 + 复制按钮） | ✅ |
| 改动汇总卡（组头 +2 文件 / +5 -1 + 逐文件撤销） | ✅ |
| 撤销回调（点后按钮变「已撤销」灰态） | ✅ |

### 8.5 新增的公开门面

渲染层内部全是 `internal`。宿主（未来的 App）只需要一个入口，因此加了
`com.assistant.ui.AssistantChatView`：

```kotlin
val timeline = MessageTimeline()
val view = AssistantChatView(context, timeline, callbacks, fileAccess)
container.addView(view.view)
// 宿主往 timeline 里塞事件，面板自动重绘
view.release()  // 销毁时释放观察者
```

`AssistantChatCallbacks` 四个动作：`onToggleExpanded` / `onToggleGroup` /
`onRevert` / `onMoreClick`。**面板不自己持有展开态**——回写 `MessageTimeline` 后
下一帧快照自然带上新状态。

### 8.6 踩到的两个坑

1. **ComposeView 必须挂在 `ComponentActivity`（或任何提供 `ViewTreeLifecycleOwner`
   的宿主）上**。第 1 步的 `ProbeActivity` 继承 `android.app.Activity` 没问题（它只用
   `TextView`），但 `ChatDemoActivity` 用纯框架 Activity 会抛
   `IllegalStateException: ViewTreeLifecycleOwner not found`。这是 Compose 的硬要求，
   与 ACS 无关，但**第 3 步之后所有宿主都必须注意**。
2. **Kotlin 属性初始化顺序**：`AssistantChatView` 里回调对象要读 `host.view` 做菜单锚点，
   而 host 的构造又需要回调——直接写会得到
   `type checking has run into a recursive problem`。用 `by lazy` 声明 host 解决。

### 8.7 结论

**渲染层已完全脱离 ACS**。75 个文件全部编译通过并在真机上正确渲染，
包括 Markdown / 代码高亮 / 表格 / diff 卡 / 工具组 / 思考块 / 询问面板。

第 3 步（Port 桩化）不再有「搬不动」的障碍：渲染层已经不认 ACS 类型，
桩化只需要在内核契约上提供空实现。

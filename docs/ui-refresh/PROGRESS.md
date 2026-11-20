# UI 升级进度

## 当前阶段（2026-11-20）

- 分支 `feat/main-app-ui-refresh`，基线 `5c881094`；未提交、推送、合并或发布。
- 首页二版、设置页与弹窗、助手默认主题、文件树及 SDK/JDK 管理页已完成代码、调试构建及黑鲨代表路径验收；完整矩阵未全覆盖，详见 ACCEPTANCE.md。
- 主会话负责实现、构建与设备验收；用户指定子会话负责只读探索和审查，未把代码或设备验收外包。

## 已实现

- 首页共享 XML/ViewBinding 工作台：创建/打开并排项目卡片、Git 导入行、开发工具双列；有效宽度不足320时单列，整体可滚动，保留七入口及终端长按。
- 局部 `acs_ui_*` 间距、文字、圆角与控件样式，保留五主题，不增加模块或依赖 `assistant:*`。
- 主题偏好通过 `IDETheme.fromStoredName` 安全解析；未设置、空或无法识别的值默认使用暖阳和煦，已保存的有效主题及浅深色模式不变。View/Compose 复用颜色属性解析。
- 设置分组/条目信息层级、入口图标、主题色图标、行底色与按压波纹统一；标题允许换行。使用 `PreferenceGroupAdapter.getItem(position)` 修复首版索引越界；系统栏 inset 不再累加。
- 设置页临时隐藏悬浮入口，退出按原持久化意图恢复，不改变开关意图。
- M3 选择/确认弹窗统一标题、正文、按钮及28dp圆角；项目重命名与云Token输入复用 `DialogFields`，保留原业务校验、密码切换和保存回调。
- 项目弹层分配剩余高度，向导/克隆内容可滚动并预留底部空间；模板卡片使用局部组件主题、描述不截断，大字体减少列数。
- 助手 UI 壳和默认消息方案成套读取 IDE 主色、容器、前景、背景和中性语义色；显示名改为“跟随 IDE 主题”，持久化 id 仍为 `default`。咖啡/石墨/森林消息方案保持独立。
- 系统浮窗按应用明暗偏好与 IDE 主题构造 Context；Material You 保留动态色路径。输入发送/停止/忙碌状态使用配套主题语义色。
- 文件树缩进12dp、图标24dp、目录/文件分色、主题激活态；当前文件观察 Activity 的 EditorViewModel，底部导航保留 Material 背景和按压行为。
- 编辑器项目长标题省略并避让按钮；文件树文件名保留横向查看，不宣称文件名已省略。
- 实机发现恢复文件后高亮缺失：标签选中早于异步读盘完成，旧回调用空 `editorView.file` 覆盖当前文件。改从已打开文件列表取身份，最小修改后关闭重开两文件会话的高亮恢复通过；临时诊断日志已移除。

## 构建与检查

- 按用户最新选择，默认主题恢复为暖阳和煦；缺失/无效偏好回退及五种已保存主题读回已在最终 SDK/JDK 构建中通过2项测试。此前 `test-default-greenish.log` 仅记录已被替代的中间选择，不作为最终默认主题证明。以下构建和设备条目保留上一批证据，本次最终包见“SDK/JDK 管理页增量验收”。
- 最终构建 `.verify/ui-refresh-v2/build-unified-ui-verified.log`：`BUILD SUCCESSFUL`；`gradle-stop-unified-ui-verified.log`：daemon 已停止，最终 `jps -l` 仅有 Jps。
- 本批主体构建及 common 单测见 `build-unified-ui-final.log`；`IDEThemeTest` 2项、`ElfArchTest` 22项无失败。最终增量只涉及 App 选中身份和资源文案，沿用未变化的 common 测试结果。
- arm64 APK：`core/app/build/outputs/apk/debug/android-code-studio-arm64-v8a-debug-1.0.0+gh.r29-local.apk`（1079）。
- `apksigner verify --print-certs` 确认同签名，仅覆盖安装，未卸载或清应用数据。证书 SHA-1：`83abb7381685a06dbdae877ded4a207f4919c1da`。
- 从设备拉取当前 APK 后核对 SHA-256 与最终产物均为 `67136fd8219e97bbebf7da267c1f599d80ff8f8fb1b324c823ac1c8f313030ff`，见 `apk-unified-verification.json`。首轮旧包 hash 不作为本批证明。
- 36份新增/改动 XML 解析通过；最终 `git diff --check` 通过，未遗留诊断日志或临时源码。
- 指定子会话两轮整体静态审查及恢复高亮增量审查均为 APPROVE。
- lint 仍是环境阻塞：离线依赖缺 lifecycle、datastore、graphics-shapes 和 Compose 等缓存，见 `build-final.log:564`；未执行到源码 lint，不记为通过。

## 黑鲨实机结果

设备 `9c18cb30`，Android 10 / SDK29；界面操作沿用 `android-ui-test`（uiautomator2）按文本/id定位，没有收费模型请求。证据在 `.verify/ui-refresh-v2/`。

- 首轮：首页七入口、终端点击/长按及返回、五主题浅深代表图、字号1.3/2.0与横屏滚动、项目列表/搜索、克隆打开取消、模板创建、文件编辑保存、文件树标题/按钮边界通过代表检查。
- 本批：设置根/常规/模式选择和返回、“打开项目前确认”修改离开重入及恢复、模板选择及创建新测试项目通过。
- 本批：字号2.0设置根/子页及选择弹窗、横屏选择按钮、竖屏重命名IME可达；测试项目重命名并改回成功。
- 本批：云Token只打开取消，非空字段未截图，未修改凭据或登录、提交构建。
- 本批：助手暖阳浅深全屏与系统浮窗、绿意深色浮窗的空态与草稿检查通过；咖啡方案可选且宿主壳保持绿意。空态不能证明实际消息/工具卡片的完整方案边界。
- 本批：文件点击、标签高亮同步、目录展开与长按菜单检查通过；最终包两文件会话关闭重开后的当前文件高亮恢复通过，见 `filetree-reopen-fixed-final.png/.xml`、`filetree-expanded-final.png/.xml`。
- 最终包设置文案/选择器、悬浮入口隐藏恢复、主题与模式读回再次检查，见 `settings-unified-verified-final.png/.xml`。

## 设置/助手/文件树批次的数据与设备收尾

- 首轮 `AcsUiRefreshSmoke20261120` 与本批 `AcsUnifiedUi20261120` 均正常关闭并清理，仅处理本轮创建的数据；没有卸载、清数据或删除用户项目/SDK/Termux。
- 本批精确删除测试最近路径与空白会话（仅一条 session-meta，无用户/模型消息）；核对其余7条最近记录、33个会话内容哈希未变。偏好仅删对应项，其余字节保留。见 `unified-cleanup-verification.json`。
- 消息配色偏好恢复为原先“未设置键”的状态；暖阳和煦、跟随系统、字号1.0、百度输入法、`accelerometer_rotation=1`、`user_rotation=0`、竖屏与首页均已读回。
- 本批收尾证据：`device-unified-restored-final.json`、`home-unified-restored-final.png/.xml`、`projects-unified-cleaned-final.png/.xml`。

## SDK/JDK 管理页增量验收

- SDK/JDK 工具栏、说明、卡片、状态、路径、按钮与输出面板使用统一令牌；动态 SDK chip 复用现有主题包装；两页补上已绑定回调的返回图标。保持所有 ViewBinding ID，JDK Activity 与安装器无业务差异。
- 默认主题按用户最新选择恢复为暖阳和煦（`SUNNY_GLOW`），有效已保存主题继续按原值读取。最终 `IDEThemeTest` 2项通过，不用清空设备偏好验证默认值。
- `.verify/ui-refresh-v2/build-sdk-jdk-ui-final.log` 完成 SDK/JDK 解析测试（平台9项、JDK6项）；追加默认主题与返回图标后的 `build-sdk-jdk-ui-verified.log` 构建成功，common 24项测试通过（主题2项、ELF22项）。两轮均停止 Gradle daemon，最终 `jps -l` 仅有 Jps。
- 最终包同签名覆盖安装，设备拉回的 APK 与本机 SHA-256 同为 `34b790dd9f5fffe4b5ea851eaef563c4ab39c7de9128f3f3e6e5ea36dfd06d29`，见 `apk-sdk-jdk-verification.json`。40份新增/改动 XML 解析通过，四份管理页布局的 ID 集合与基线相同。
- SDK 五类组件、加载完成的可用版本、platforms 更多/收起、安装确认取消、卸载确认取消、build-tools 默认选择取消与工具栏返回通过。证据：`sdk-manager-loaded-final-top`、`sdk-manager-final-bottom`、`sdk-platforms-{collapsed,expanded}-final`、`sdk-{install,uninstall,default}-dialog-final`（均有 PNG/XML）。
- JDK 17“已安装 · 使用中”无使用/卸载按钮，JDK 21显示已装路径与使用/卸载动作；卸载确认取消通过。设备两个候选均已安装，未执行切换，也未制造未安装状态。见 `jdk-manager-final-top`、`jdk-uninstall-dialog-final`。
- 暖阳浅深代表页面和卸载弹窗、字号2.0的 SDK 默认选择/JDK 卸载确认、两页横屏滚动可达通过。横屏自动 `scroll.to` 未可靠移动，改用实际 scroll_view 容器滑动后达到目标；一次工具栏返回断言过早，等待设置页出现后确认成功，不记为应用失败。
- 横屏字号2.0时悬浮助手会局部遮住默认版本行，滚动后可点击并取消选择框；此项为保留限制，未扩展到悬浮窗行为改造。无操作时日志区隐藏，未用真实安装/卸载制造日志状态。
- 当前 JDK17偏好、build-tools 默认未设置、SDK/JDK 目录名盘点前后相同；只检查目录清单，不宣称逐字节校验工具链。见 `toolchain-sdk-jdk-verification.json`。
- 本批没有创建/删除项目或测试会话，也未确认 SDK/JDK 安装、卸载、root 修权限。最终主题按最新选择为暖阳和煦，恢复跟随系统、字号1.0、百度输入法、自动旋转1/user_rotation0、竖屏及进入前的系统 USB 设置页。主题初始实读为绿意盎然，不将最终暖阳记作与该初始值一致。见 `device-sdk-jdk-restored-final.json`。

## 保留的限制与未测项

- 原 Sunny Glow 16张及其余主题8张的全页面矩阵没有补齐；不得用代表截图宣称全量通过。
- 助手 Compose 卡片缩放与自定义正文颜色尚未接线；内联侧栏、实际消息/工具卡片非默认方案、运行/停止/错误状态及全部主题矩阵未测。
- 字号2.0横屏输入时，底部按钮需先收起IME操作；旋转重建后输入弹窗不保留，未扩展本次布局修改为弹窗状态保存改造。
- Android12+动态色、全界面对比度/48dp量测、多项目、完整构建安装运行闭环、全部特殊模板和所有偏好持久化矩阵未测，逐项见 ACCEPTANCE.md。

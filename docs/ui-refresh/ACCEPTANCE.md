# UI 验收

## 当前状态（2026-11-20）

黑鲨 `9c18cb30`（Android 10 / SDK 29）已覆盖安装 `1.0.0+gh.r29-local`（1079）的 SDK/JDK 管理页最终包，包含此前的设置页/弹窗、助手主题与文件树改版，以及按用户最新选择恢复的暖阳和煦默认主题。代表路径验收与设备收尾完成；各批次证据分开记录，未执行的完整矩阵不记为通过。

旧版参考图位于 `%TEMP%/acs-ui-refresh-5c881094`；二版过程和最终证据位于 `.verify/ui-refresh-v2/`。旧文件名不代表最终页面有效，按下表引用证据。

## 静态与构建结果

| 项目 | 状态 | 证据/限制 |
|---|---|---|
| 资源编译与主 App 调试构建 | 通过 | 最终 `build-unified-ui-verified.log`：`BUILD SUCCESSFUL`；36份新增/改动XML另经解析通过 |
| `core:common:testDebugUnitTest` | 通过 | `build-unified-ui-final.log`、`core/common/build/test-results/testDebugUnitTest/`；`IDEThemeTest` 2项、`ElfArchTest` 22项无失败，最终App增量未改变common |
| 签名与安装产物一致性 | 通过 | `apksigner` 证书一致，仅覆盖安装；设备当前包与最终APK SHA-256 均为 `67136fd8219e97bbebf7da267c1f599d80ff8f8fb1b324c823ac1c8f313030ff`，见 `apk-unified-verification.json` |
| lintDebug | 环境阻塞 | `build-final.log:564`，缺离线 lifecycle/datastore/graphics-shapes/Compose 等缓存，未执行源码 lint |
| 最终代码静态审查 | 通过 | 用户指定子会话两轮整体与恢复高亮增量审查均为 APPROVE；原按压反馈观察已实施 |
| Gradle daemon 收尾 | 已完成 | `gradle-stop-unified-ui-verified.log`，最后 `jps -l` 仅有 Jps |

构建使用项目 JDK21、`--offline` 与既有设备测试签名；没有卸载或清应用数据。

## 首页二版首轮设备流程

界面操作使用 `.claude/skills/android-ui-test` 的 uiautomator2，按 text/resource-id 定位；不调用收费模型。

| 流程 | 结果 | 代表证据（均相对 `.verify/ui-refresh-v2/`） |
|---|---|---|
| 首页标题与七入口、终端长按 | 通过；普通点击/长按均进入终端，键盘收起后可返回 | `home-v2-light.xml`、`home-restored-final.png/.xml` |
| 项目列表与长名称/路径 | 通过可见性检查与测试项目打开 | `projects-v2.png/.xml`、`projects-after-install.png` |
| 搜索无结果与键盘 | 已检查 | `projects-search-empty-final.png` |
| 克隆表单打开与取消 | 通过；未发起实际克隆 | `clone-v2.png/.xml` |
| ATC 模板选择与普通项目创建 | 通过创建、初始化与重新打开 | `templates-v2.png/.xml`、`editor-created-final.png` |
| 向导底部、IME与横屏 | 创建按钮可滚动到达 | `wizard-v2-bottom.png/.xml`、`wizard-ime-final.png`、`wizard-landscape-final.png` |
| 设置根页/常规子页/主题与模式/返回 | 通过；首版越界崩溃未复现 | `settings-v2.png/.xml`、`settings-general-final.png/.xml` |
| 设置期间悬浮入口 | 进入隐藏，返回首页恢复 | uiautomator2 存在性断言；`home-restored-final.xml` |
| 五主题浅深首页 | 已检查代表页面 | `home-{blue,green,material,vscode}-{light,dark}.png`、`home-sunny-light-final.png`、`home-v2-dark.png` |
| 首页字体1.3/2.0与横屏 | 单列/滚动可达检查通过 | `home-font-1.3*.png`、`home-font-2.0*.png`、`home-landscape-final.png`、`home-landscape-bottom.png` |
| 编辑器文件打开、编辑、保存 | 测试文件 `gradle.properties` 注释已确认持久化 | `editor-file-final.png`、`editor-shell-final.png` |
| 文件树长标题与按钮 | 通过；标题右边界550px < 设置左边界572px，设置/浏览按钮均144px，约52dp；两按钮均可打开 | `editor-tree-title-final.png/.xml`、`editor-tree-bounds-final.json` |
| 测试数据清理与设备恢复 | 通过；目录和最近记录已清理，其他最近记录不变 | `projects-after-cleanup.png/.xml`、`device-restored-final.json` |

`file-tree-title-fixed.png` 实际是首页截图，不作为文件树修复验收证据。代表性流程没有进入 `CrashHandlerActivity`；未据此声明全仓库无崩溃。

## 统一改版本批增量

本节主体检查使用 `build-unified-ui-final.log` 新包；恢复高亮修复与最终设置复核使用 `build-unified-ui-verified.log` 最终包。最终增量仅修复选中身份和中英文消息方案说明，临时诊断日志已移除。同为 versionCode 1079 不代表构建相同。证据相对 `.verify/ui-refresh-v2/`。

| 流程 | 结果 | 代表证据/边界 |
|---|---|---|
| 设置根页、常规页、模式选择、返回 | 通过；根页6个可见入口图标，设置中悬浮入口隐藏，返回恢复 | `settings-unified-{root,general,mode-dialog}.png/.xml` |
| 打开项目前确认 | 修改、离开、重入读回与恢复原值通过 | uiautomator2 按标题点击及 checked 读回；原值 true |
| 模板选择、配置底部与创建 | 通过；创建 `AcsUnifiedUi20261120` 并打开编辑器 | `templates-unified.png/.xml`、`wizard-unified-bottom.png/.xml` |
| 字号2.0的设置和选择弹窗 | 通过代表场景；横屏取消/确定均可达 | `settings-unified-font2-{root,general,mode,landscape-mode}.png/.xml` |
| 项目重命名输入与取消 | 测试项目重命名为 `AcsUnifiedUi20261120Renamed` 再改回通过；字号2.0竖屏IME可达 | `rename-unified-font2-ime.png/.xml` |
| 横屏字号2.0输入 | 有限制；先收起IME后可取消，不能写为键盘打开时按钮均可见 | `rename-unified-font2-landscape-stable.png/.xml`；旋转中抓取的 `rename-unified-font2-landscape-ime.png` 不是有效布局证据 |
| 云Token输入 | 打开、取消通过；非空字段未采集截图，未修改Token或登录 | 测试项目非Git仓库，仅进入“配置 Token”，未提交构建 |
| 助手全屏与系统浮窗 | 暖阳浅深、绿意深色代表路径通过，发送按钮和中性底色随IDE | `assistant-fullscreen-sunny-light-controls.png/.xml`、`assistant-fullscreen-sunny-dark.png/.xml`、`assistant-overlay-{sunny-light,sunny-dark,green-dark}.png/.xml` |
| 独立消息preset | 咖啡可选、壳保持绿意；空态不足以证明实际消息卡片的完整配色 | `assistant-preset-dialog-unified.png/.xml`、`assistant-overlay-green-coffee-dark.png/.xml`；无模型请求 |
| 文件树图标、文件切换、菜单 | 目录/文件分色、文件及标签切换高亮、多层展开、长按菜单通过；恢复后高亮缺失已修复并复测 | `filetree-unified-selected.png/.xml`、`filetree-tab-sync-final.png/.xml`、`filetree-reopen-fixed-final.png/.xml`、`filetree-expanded-final.png/.xml`；深色旧图与 `filetree-reopen-click.png` 仅为修复前记录 |
| 最终包设置复核 | 根页、消息方案文案/选择器、悬浮入口隐藏恢复、主题/模式选中读回通过 | `settings-unified-verified-final.png/.xml`、`device-unified-restored-final.json` |

助手只使用本轮新建空白测试会话；草稿 `UI theme check` 均清空，没有发送模型请求。底部“AI 助手”实际进入全屏 Activity，不将其记作内联侧栏壳验收通过。切换主题中的一次 uiautomator2 连接中断已读回页面后重试，不作为应用崩溃。

## SDK/JDK 管理页最终批次

本节对应 `build-sdk-jdk-ui-verified.log`，包含暖阳和煦默认主题及返回图标。设备最终 APK 与本机产物 SHA-256 均为 `34b790dd9f5fffe4b5ea851eaef563c4ab39c7de9128f3f3e6e5ea36dfd06d29`；同签名覆盖安装，没有卸载/清数据，见 `apk-sdk-jdk-verification.json`。前文 `67136f…` 只证明上一批包。

| 项目 | 结果 | 证据/边界（相对 `.verify/ui-refresh-v2/`） |
|---|---|---|
| 调试构建、资源、绑定 ID | 通过 | `build-sdk-jdk-ui-verified.log`；40份新增/改动 XML 解析通过，四份管理页布局 ID 集合与 HEAD 相同 |
| 默认主题与兼容读回 | 通过 | 最终 common `IDEThemeTest` 2项，缺失/空/未知值回退暖阳和煦，五种有效主题仍读回原值；未清空设备偏好 |
| SDK/JDK 解析测试 | 通过 | `build-sdk-jdk-ui-final.log`；`PlatformPackageParseTest` 9项、`JdkInstallerParseTest` 6项，追加构建未改解析器 |
| common 其他测试与 daemon | 通过 | 最终 `ElfArchTest` 22项；`gradle-stop-sdk-jdk-ui-verified.log`，`jps -l` 仅有 Jps |
| SDK 五组件与状态 | 通过代表检查 | `sdk-manager-loaded-final-top.png/.xml`、`sdk-manager-final-bottom.png/.xml`；build-tools/platforms/NDK/CMake/platform-tools 均有现有安装项，可用列表已加载 |
| SDK platforms 更多/收起 | 通过 | `sdk-platforms-{collapsed,expanded}-final.png/.xml`；展开后收回，保留现有 API 与版本筛选 |
| SDK 安装/卸载/默认选择 | 确认框显示与取消通过 | `sdk-{install,uninstall,default}-dialog-final.png/.xml`；未点安装/卸载确定或默认选择项 |
| JDK 当前版本保护 | 通过 | `jdk-manager-final-top.png/.xml`；17显示使用中且无使用/卸载动作，21显示使用/卸载；没有点击“使用” |
| JDK 卸载确认 | 显示与取消通过 | `jdk-uninstall-dialog-final.png/.xml`；两个候选均已安装，安装按钮/确认状态未实机覆盖 |
| 工具栏返回与重入 | 通过 | SDK 按“后退”回设置，JDK 多次进入/返回；一次立即读 activity 的断言早于动画结束，等待设置项出现后确认成功 |
| 暖阳浅深 | 通过代表检查 | 浅色上述页面；`{sdk,jdk}-sunny-dark-final-top.png/.xml` 与对应 `sunny-dark-uninstall-dialog-final`，不代表五主题全页矩阵 |
| 字号2.0竖屏/横屏 | 滚动可达与取消通过 | `jdk-font2-{final-top,final-bottom,uninstall-dialog-final,landscape-final,landscape-dialog-final}`；`sdk-font2-{final-top,default-dialog-final,landscape-default-row-final,landscape-default-dialog-final}`（PNG/XML） |
| 大字横屏悬浮入口 | 保留限制 | 助手浮球可遮住默认版本行局部，滚动后该行可点并取消；未将悬浮窗行为纳入本批修改 |
| 工具链/偏好与系统恢复 | 通过有限核对 | `toolchain-sdk-jdk-verification.json`、`device-sdk-jdk-restored-final.json`；JDK17、build-tools 默认未设置及目录名盘点一致，不宣称全工具链内容哈希一致 |

本批全部界面操作使用项目 `android-ui-test` 的 uiautomator2，按 text/resource-id 或滚动容器定位。没有模型请求，没有创建或删除项目/测试会话。SDK/JDK 后端逻辑保持原实现；进出与旋转检查期间未观察到当前测试进程的崩溃记录。

SDK/JDK 本批未测：真实安装/卸载/下载/许可证、root 权限修复、后端不可用及权限错误状态、JDK 未安装卡与安装确认、当前 JDK 切换生效、安装过程日志展开/成功收起/失败保留/手动切换。日志默认隐藏，本批未制造有副作用的操作。lint 仍沿用前文离线依赖限制，不记为通过。

本批最终主题按用户最新选择为暖阳和煦，界面模式恢复跟随系统，字号1.0、百度输入法、自动旋转1/user_rotation0、竖屏恢复；前台恢复进入前的系统 USB 设置页。初始实际主题读回为绿意盎然，最终暖阳是最新选择，不记作原值恢复。此前各批次设备收尾记录保留其历史含义。

## 未测与未完成

- 文件树恢复高亮修复已在最终包通过两文件关闭重开复测；未据此宣称快速多项目切换等完整时序矩阵通过。
- 项目列表完全无数据、浏览导入与备份完整流程未测；本轮不删除用户项目以制造空数据。重命名已对本轮测试项目验证往返。
- 克隆参数校验、自定义选项、实际进度与取消下载流程。
- 所有特殊模板字段约束、错误反馈、取消和重入矩阵；模板排序/错误反馈窄测试没有新增执行结果。
- 多项目切换、完整文件标签/输出面板交互、专用测试项目构建及安装运行闭环。
- 设置所有直达页与所有开关重启持久化矩阵未测；“打开项目前确认”已验证修改、离开重入及恢复，主题/模式已验证重启应用。
- 助手内联侧栏、实际消息/工具卡片的非默认 preset、运行/停止/错误状态、完整浅深主题矩阵未测；全屏/系统浮窗的空态与草稿已测。Compose 卡片缩放与自定义正文颜色尚未接线。
- Android 12+ 动态色、窄屏模拟、手势导航与三键导航切换对照、所有弹窗字号2.0和IME矩阵。
- 文字4.5:1、关键非文字3:1的定量对比度检测；48dp触控尚未对全界面逐控件量测。

## 原计划截图矩阵

S01首页；S02长项目列表；S03搜索无结果+键盘；S04模板选择；S05配置错误+键盘；S06界面设置；S07项目/文件标签；S08助手/编辑器边界。

目标为 SunnyGlow 浅深各8张、其他四主题浅深首页各1张，另补横屏/大字/特殊表单边界。当前完成的是上表代表图，尚未补齐 SunnyGlow 全部16张及每项交互断言，不能宣称矩阵全通过。系统动态区域可遮罩，页面内容与边界不可。

## 上一批设置/助手/文件树的设备收尾

SDK/JDK 最终批次的设备状态以本文件“SDK/JDK 管理页最终批次”和 `device-sdk-jdk-restored-final.json` 为准；以下保留上一批数据清理与恢复记录。

首轮 `AcsUiRefreshSmoke20261120` 与本批 `AcsUnifiedUi20261120` 均正常关闭并仅清理本轮测试目录，保留 SDK、Termux、用户项目和应用数据。本批重命名测试已改回原名后清理。

本批精确移除残留的最近项目测试路径及对应空白会话引用；测试会话仅含一条 session-meta，无用户/模型消息。清理后核对其余7条最近记录及33个会话内容哈希未变，偏好其他字节保持原样，消息方案键恢复为原先不存在。见 `unified-cleanup-verification.json`。

已恢复并读回：主题“暖阳和煦”、界面模式“跟随系统”、字体1.0、输入法 `com.baidu.input_heisha/.ImeService`、`accelerometer_rotation=1`、`user_rotation=0`、当前竖屏与首页。原始基线为 `device-baseline.json` 和 `theme-baseline.json`；本批完成证明为 `device-unified-restored-final.json`、`home-unified-restored-final.png/.xml`、`projects-unified-cleaned-final.png/.xml`，首轮旧收尾文件不替代本批。

工作区保留未提交状态，未推送、合并或发布。

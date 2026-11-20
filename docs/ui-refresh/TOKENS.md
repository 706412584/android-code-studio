# UI 规格

## 布局与控件

- 间距：4、8、12、16、24、32dp，复用助手刻度。
- 页面边距：手机16dp；宽屏24dp；常规表单最大宽度720dp。
- 圆角：控件12dp，内容卡片16dp，弹层28dp。
- 最小触控区域48dp；按钮高度以 minHeight 约束，允许字体放大后自然增高。
- 页面标题24sp，区块18sp，正文14–16sp，辅助13sp；使用局部 TextAppearance，不修改全局字体。
- 低阴影与弱描边；一个页面区分页面、内容、强调三层，不逐控件叠卡。

## 颜色

| 角色 | Material 属性 |
|---|---|
| 页面 | android:colorBackground |
| 内容 | colorSurface |
| 次级内容 | colorSurfaceContainerLow |
| 主文本 | colorOnSurface |
| 次文本 | colorOnSurfaceVariant |
| 主操作 | colorPrimary / colorOnPrimary |
| 次操作 | colorPrimaryContainer / colorOnPrimaryContainer |
| 边界 | colorOutlineVariant |
| 错误 | colorError / colorErrorContainer |

保留 BlueWave、SunnyGlow、Greenish、VSCode、MaterialYou 五主题。未设置、空或无法识别的主题值回退到暖阳和煦（SunnyGlow），不覆盖已保存的有效主题。助手 UI 壳与默认消息方案跟随 IDE，主色、容器、文字与中性语义色成套映射；咖啡、石墨、森林消息 preset 保留独立，编辑器语法色板不变。系统浮窗使用同一份 IDE 主题与明暗偏好。

## 页面

- 首页（二版）：紧凑工作台标题、创建/打开并排项目卡片、Git 导入行、双列开发工具；七入口及终端长按保留。有效宽度（screenWidthDp/fontScale）不足320时切单列；横竖屏共享布局，整体可滚动，底部预留悬浮入口空间。
- 项目列表：标题、搜索、滚动列表、浏览；空项目与无结果分别提示，路径弱化但可访问。
- 模板：两步一致，字段分组、错误贴近输入，键盘下操作仍可达；特殊模板约束保留。
- 设置：统一顶栏、分组、标题/摘要与开关，保留 Preference 绑定；条目最小64dp、分组48dp，标题自然换行，图标取 IDE 主色，按压波纹与行底色使用主题属性。
- 弹窗：沿用原生 M3 选择/确认结构，统一28dp圆角与局部文字样式；输入复用 outlined 字段及帮助/错误区域，保留业务校验与密码切换。
- 编辑器：项目/文件标签可区分，文件树缩进12dp、图标24dp，目录取主色、文件取次文本色，当前文件使用主题容器高亮；文件名保留横向查看，顶部项目长标题单行省略并避让操作按钮。
- SDK/JDK：左对齐的标题与副标题、低高程工具栏、统一描边组件卡；已装版本与候选动作分组，JDK 使用/安装/卸载分别复用次要/主要/文字按钮。默认版本行、按钮和日志标题用最小高度而非固定高度，日志采用可折叠等宽文字面板。

首页二版已在黑鲨实机检查五主题浅深代表场景、横屏和大字体；设置、弹窗、助手与文件树的本批证据及未测范围见 ACCEPTANCE.md。字号2.0横屏输入时需先收起键盘操作底部按钮；助手 Compose 卡片缩放和自定义正文颜色接线仍待后续实现。

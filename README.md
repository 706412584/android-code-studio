<p align="left">
  <img src="https://avatars.githubusercontent.com/u/234419170?s=400&u=43571ebf481969baafb8399813ad57f46c19eb95&v=4" alt="Android Code Studio" width="80" height="80"/>
</p>

<h2 align="left"><b>Android Code Studio（中文定制版）</b></h2>
<p align="left">
  在 Android 设备上开发真正的 Gradle Android 应用——并在原版基础上，针对
  <b>中文开发者、国内网络环境与 AI 辅助开发</b>做了大量增强。
<p><br>

<p align="left">
<!-- Latest release -->
<img src="https://img.shields.io/github/v/release/706412584/android-code-studio?include_prereleases&amp;label=latest%20release" alt="Latest release">
<!-- License -->
<img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License">
<!-- 上游 -->
<img src="https://img.shields.io/badge/upstream-AndroidCSOfficial%2Fandroid--code--studio-lightgrey" alt="Upstream"></p>

<p align="left">
  <a href="https://github.com/706412584/android-code-studio/releases">
    <img src="https://img.shields.io/badge/下载-GitHub%20Releases-brightgreen?style=for-the-badge&logo=github" alt="Download from GitHub Releases">
  </a>
  <a href="https://gitee.com/wu-yongchengsvip_admin/android-code-studio">
    <img src="https://img.shields.io/badge/镜像-Gitee-red?style=for-the-badge&logo=gitee" alt="Gitee Mirror">
  </a>
</p>

---

## 这是什么

[Android Code Studio](https://github.com/AndroidCSOfficial/android-code-studio)（ACS）是一个
运行在 Android 设备上的完整 IDE：Gradle 构建、终端、SDK/NDK 管理、代码补全、
UI 设计器、Git 集成，以及一个**项目感知的 AI 编程助手**。

本仓库是它的一个 fork，**不是简单的代码镜像**。在保持与上游同步的基础上，
本 fork 做了以下方向的深度定制：

---

## 本 fork 的定制优势

### 1. AI 助手：工具调用架构与 60+ 工具

上游的 AI 助手是「生成文本 + 按标记写文件」的单轮模式。本 fork 移植并扩展了
**完整的工具调用 agent 循环**——AI 能多轮调用工具、读构建日志、装应用、看崩溃，
形成「读代码 → 改代码 → 构建 → 安装 → 启动 → 读日志」的闭环。

- **60+ 个内置工具**（`ToolNames` 常量表 63 项），覆盖文件、Shell、Git、构建、
  设备控制、Web、图像、MCP 等；另有 MCP server 与自定义 agent 动态扩展的工具
- **纯 JVM 工具层**（`core/ai-tool` 等 4 个模块零 Android 依赖），
  **1100+ 条单元测试**全绿
- 后台命令执行（`run_in_background` + `task_output`/`task_stop`）——跑长构建不再干等
- 多子 agent 流水线（`agent_pipeline`）：依赖编排 + 同层并行 + 写范围互斥
- 长期记忆、Skill 市场、自定义 agent、MCP（含 stdio 传输）
- 上下文裁剪与压缩、JSONL 会话持久化（进程被杀后可恢复）

### 2. 13 个 AI 服务商预设，国内服务商优先

针对国内开发者补齐了服务商生态，**新增同类服务商只需加一行代码**：

| 国际 | 国内 | 其他 |
|---|---|---|
| Anthropic Claude、OpenAI、xAI Grok、Groq、OpenRouter | DeepSeek、智谱 GLM、月之暗面 Kimi、阿里通义千问、MiniMax、硅基流动 | 本地模型（Ollama 等）、自定义端点 |

- 默认服务商为 **DeepSeek**（国内直连可用）
- 支持 1M token 上下文窗口标记、推理强度（reasoning effort）逐服务商配置
- 每个服务商可配置 4 槽位模型（main / haiku / sonnet / opus 语义）

### 3. 云编译（GitHub Actions 一键出包）

手机上点一下，云端出包——**无需本地构建**：

- 工具栏与侧栏入口，自动识别项目仓库与 workflow
- 一键触发、job 级实时进度、失败自动提取关键日志行
- 支持**取消构建**与**历史构建**列表
- **出包目标选择**：勾选 Android / 桌面 / Web / iOS，未选中的 job 直接跳过（不耗配额）
- GitHub 登录两种方式：Device Flow（浏览器授权）或手动 PAT（加密存储）
- 没有远程仓库？**一键创建 GitHub 私有仓库并推送**
- **AI 助手也能触发**（`github_actions_build` / `github_actions_status`）

### 4. 应用内热更新（增量补丁）

接入了类 Tinker 的补丁机制，**小改动不必重新下载整包**：

- 发版时 CI 自动从上一版本 APK 生成增量补丁（只含差异 dex/资源）
- 用户端检测到补丁 → 下载 → SHA-256 校验 → base 版本校验 → 应用
- 补丁生成失败不影响整包发布（整包通道始终可用）
- 修复了 DEX 命名跳号导致补丁**静默失效**的关键缺陷

### 5. 手机自动化与设备工具

AI 助手可以直接操作手机做 UI 测试与调试：

- `phone_uiautomator`——uiautomator2 代理（Unicode 输入、等待消失、多点触控、滚动到元素）
- `phone_ocr`——屏幕截图 OCR，返回文本与像素包围盒
- `phone_screenshot` / `phone_click` / `phone_swipe` / `phone_input_text` 等一整套
  设备操作工具 + 基线对比（`phone_baseline`）
- `image_tool`——色键/亮度键抠图、裁剪、缩放、主色提取
- `logcat_read` / `install_apk` / `launch_app`——构建产物直接上机验证

### 6. CodeGraph 语义索引

为项目建立符号级语义索引——AI 检索代码从「grep 文本」升级为「符号 + 调用关系」，
一次查询拿到源码与调用链，大幅减少无谓的文件读取。

- 应用内一键下载/管理组件（走 Gitee Release 分发，规避国内设备访问 GitHub 超时）
- AI 工具 `codegraph` 直接查询索引（找符号 / 调用者 / 被调用者 / 影响面）

### 7. 中文开发者体验

- **中文优先**：界面文案、模板描述、本 README 均为中文
- **新建项目向导**：每个模板都带中文描述（如「跨平台：一份 Kotlin + Compose 代码
  出 Android、桌面、网页三个平台」），跨平台模板显示**「跨平台」徽标**、
  排在模板列表末尾，便于区分
- **国内镜像源内置**：三个跨平台模板生成的工程已配好阿里云 / 腾讯镜像仓库
  （settings.gradle 仓库与 wrapper 下载地址），设备上创建项目即可构建，
  不必手动改源
- **网络适配**：Gitee 镜像分发、CodeGraph 组件走 Gitee Release（国内直连）
- 全部内置模板：Empty、Basic Activity、Bottom Navigation、Navigation Drawer、
  Responsive、Compose、Native C++、Game (LibGDX)、Quick Develop、
  Compose Multiplatform、React Native、Flutter

### 8. 跨平台项目模板（本 fork 新增）

上游只有 Android 模板。本 fork 新增三个跨平台模板，均由**真实工程骨架固化**而成，
生成后可直接构建（非占位文件）：

| 模板 | 技术栈 | 出包 | 编辑器支持 |
|---|---|---|---|
| **Compose Multiplatform** | CMP 1.10.3 / Kotlin 2.2.20 / AGP 8.13.0 | Android 本地构建 + 桌面/Web 云端出包（三 OS 矩阵） | Kotlin 补全（原生） |
| **React Native** | RN 0.81.4 / Kotlin 2.1.20 / Gradle 8.14.3 / compileSdk 36 | Android 本地 + iOS/Web 云端 | **TS 语言服务器**（补全 + 诊断）+ TS/TSX 语法高亮 |
| **Flutter** | Flutter 3.47.7 / Dart 3.13.5 | Android 本地 + iOS/Web/桌面 云端 | **Dart 分析服务器**（补全 + 诊断）+ Dart 语法高亮 |

- **云端出包**：三个模板的 workflow 均支持「云编译」一键触发（见上），
  并支持**出包目标选择**（勾选平台，未选中的 job 跳过）
- **编辑器语言支持**：本 fork 为 RN/Flutter 模板接入了 TS / Dart 语言服务器
  与 tree-sitter 语法高亮（上游编辑器对这两种语言只有纯文本）
- **零配置构建**：模板生成的工程自带 Gradle wrapper、图标、主题与镜像源，
  在设备上 `npm install` / `flutter` 工具链就绪后即可构建 APK

### 9. 其他增强

- **编辑器与 AI 联动**：AI 改文件后编辑器自动刷新；修复「自动保存静默覆盖 AI 修改」
  （数据丢失级缺陷）
- **Dart / TypeScript 语法高亮**：集成 tree-sitter 语言包
- **运行完成通知**：AI 任务完成时状态栏提醒
- **CI 发布流水线**：GitHub Actions 构建 → 验签 → 发 Release → 同步 `updater.json`
  → 生成热更新补丁，全自动

---

## 安装

> 请仅从可信来源安装——本 fork 的官方渠道为：

- **GitHub Releases**（主渠道）：https://github.com/706412584/android-code-studio/releases
- **Gitee 镜像**：https://gitee.com/wu-yongchengsvip_admin/android-code-studio

下载 APK 安装后，按应用内指引安装构建工具（SDK 等）。

> 本 fork 与上游使用**不同的签名密钥**。从上游版本切换过来需要卸载重装（会丢失应用内数据）；
> 本 fork 内部的版本升级支持覆盖安装，并支持热更新增量补丁。

---

## 与原版（上游）的关系

- 上游：https://github.com/AndroidCSOfficial/android-code-studio
- 本 fork 定期合并上游更新，定制功能以独立模块/提交形式维护，尽量不侵入上游代码结构
- 上游的 Issue 请不要提到本 fork 的定制功能；本 fork 的问题请提到本仓库 Issues

## 功能总览（含上游能力）

- [x] Gradle 构建支持（AGP 7.2.0+ 项目）
- [x] JDK 11 & JDK 17
- [x] 终端（内置 Termux 环境，含常用包）
- [x] SDK & NDK 管理
- [x] 自定义环境变量（构建与终端共用）
- [x] API 信息提示（since / removed / deprecated）
- [x] 日志阅读器（应用实时日志）
- [x] 语言服务器：Java / XML / Kotlin（本 fork 增加 TS / Dart）
- [x] UI 设计器（布局预览、资源解析、拖拽、属性编辑器）
- [x] Asset Studio（图标与图形生成）
- [x] 插件/子模块创建器
- [x] Git 集成
- [x] **AI Agent**（项目感知助手，本 fork 大幅增强，见上）

## 已知限制

- 项目须使用 AGP v7.2.0 或更新版本，旧项目需先迁移
- SDK Manager 部分工具（如 NDK）无法在设备上安装（官方工具链不含 Android 版本）
- 后台命令的输出为**命令结束后一次性交付**（非流式）
- 终端读取为**只读**（不向终端注入命令）
- 热更新补丁要求补丁 base 版本与当前安装版本一致（跨版本会回退整包更新）
- 应用仍处于积极开发阶段，可能不稳定；遇到问题欢迎提 Issue

## 参与贡献

- 上游贡献指南见 [CONTRIBUTING.md](./CONTRIBUTING.md)
- 翻译：上游使用 [Crowdin](https://crowdin.com/project/androidide)
- 本 fork 的定制功能欢迎提交 Issue / PR

## 致谢

- [Rosemoe](https://github.com/Rosemoe) — [sora-editor](https://github.com/Rosemoe/sora-editor)
- [Termux](https://github.com/termux) — [终端模拟器](https://github.com/termux/termux-app)
- [Bogdan Melnychuk](https://github.com/bmelnychuk) — [AndroidTreeView](https://github.com/bmelnychuk/AndroidTreeView)
- [George Fraser](https://github.com/georgewfraser) — [Java Language Server](https://github.com/georgewfraser/java-language-server)
- [FWCD](https://github.com/fwcd) — [Kotlin Language Server](https://github.com/fwcd/kotlin-language-server)
- [Itsaky](https://github.com/itsaky) — [AndroidIDE](https://github.com/AndroidIDEOfficial/AndroidIDE.git) 原作者；
  Android Code Studio 及其上游项目均源自此
- [AndroidCSOfficial](https://github.com/AndroidCSOfficial/android-code-studio) — 本 fork 的上游仓库
- 以及所有为本项目做出贡献的开发者

## 联系我们

- 本 fork Issues：https://github.com/706412584/android-code-studio/issues
- 🐧 706412584（作者 QQ）
- 🐧 交流群 634288136
- 上游社区：[Telegram](https://t.me/rv2ide) / [官网](https://m.androidide.com)

## 许可证

```
AndroidCodeStudio is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

AndroidCodeStudio is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
```

许可证违规可通过提交 Issue 或邮件举报。

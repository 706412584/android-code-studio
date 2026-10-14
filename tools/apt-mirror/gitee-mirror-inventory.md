# Gitee 镜像清单（2026-10-14）

ACS 首装下载源已迁至 Gitee。三个仓库 + 五个 release：

## 1. wu-yongchengsvip_admin/acs-mirror

| Release | 内容 | 用途 |
|---|---|---|
| `ndk-r28-v1` | NDK r29/r28/r27d（arm64+arm32，95MB 分卷 ×12）+ CMake 4.1.2 | 应用内 NDK/CMake 下载（`AcsProvider`，见 `AcsProvider.kt` 的 `MIRROR_MANIFEST_URL`） |
| `dart-sdk-v3.7.2` | Dart SDK 3.7.2 aarch64（67MB） | Flutter 模板 LSP（`DartSdkInstaller` 的 Gitee 源） |
| `sdk-tools-v35.0.1` | `buildtools-3501.tar.xz`（aarch64） | idesetup build-tools（aarch64） |
| — | `androidide-manifest.json`（raw 文件，master 分支） | **idesetup 的换源 manifest**——所有工具链 URL 指向 Gitee |

## 2. wu-yongchengsvip_admin/acs-sdk-mirror

| Release | 内容 | 用途 |
|---|---|---|
| `sdk-v1` | `android-sdk.tar.xz`（64MB） | idesetup 的 SDK 主体 |
| `tools-v1` | build-tools/platform-tools/cmdline-tools **全版本**（26 文件，608MB） | idesetup 的工具链（33.0.1~35.0.1，arm+aarch64） |

## 3. wu-yongchengsvip_admin/acs-apt-arm64

| Release | 内容 | 用途 |
|---|---|---|
| `apt-flat-test` | 测试残留（可删） | — |
| `apt-core-v1` | 首装 apt 闭包 66 包（125MB，2 分卷） | Gitee apt 本地源（`tools/apt-mirror/acs-apt-mirror.sh`） |

## 关键机制

1. **idesetup 换源**：app 在 onboarding 传 `--manifest <Gitee manifest URL>`（`IdeSetupConfigurationFragment.IDE_SETUP_MANIFEST_URL`），idesetup 实测支持该参数。
2. **idesetup 版本同步**：apt 包 `androidide-tools` 内的 `$PREFIX/bin/idesetup` 是 32-bit 旧版（不支持新参数），app 启动时用 assets 的同架构新版覆盖（`IdesetupSession.syncIdesetupToPrefix`）。
3. **Gitee 限制**：
   - 单文件 100MB（NDK 需分卷）
   - 仓库附件总配额 1GB（满则建新仓库——`acs-sdk-mirror` 就是因此新建）
   - raw 大文件强制登录（>~1MB 的 git 文件匿名访问 403）→ 大包一律走 Release 附件
   - 附件**按文件名路由可用**（2026-10-14 实测：同 tag 下 part.00/part.01 返回不同内容、
     不存在的文件名返回 404）。注意：早期曾记录"路由不可用"，该结论**有误**，已实测推翻。
     apt 仍走「分卷下载 + file: 本地源」是因为 Gitee Release 无法提供 apt 仓库所需的
     目录结构与索引文件，与路由无关。
   - 恶意文件误判：`build-tools-34.0.4-aarch64.tar.xz` 被拒 → 重新打包（改变哈希）绕过

## 更新方式

- **manifest 更新**：直接改 `acs-mirror` 仓库的 `androidide-manifest.json`（raw 文件，无需发版）
- **新增包**：上传到对应 release → 更新 manifest 的 URL

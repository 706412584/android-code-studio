# vendored 依赖

## hotupdate-1.4.0.aar

热更新 SDK（OrangeUpdate）的预编译产物，来自
[706412584/Android_hotupdate](https://github.com/706412584/Android_hotupdate) 的 `update` 模块
（`update/build/outputs/aar/update-release.aar`，构建于 2026-02-06，版本 1.4.0）。

**为什么用预编译 AAR 而不是源码依赖**：该 SDK 是独立仓库的 Android library，直接引入源码需要
把整个模块搬进 ACS；而它的产物已稳定，且我们只用其中的两个能力
（`HotUpdateHelper.applyPatch` / `loadPatchIfNeeded`）。

**为什么必须是这个版本（2026-02-06 或更晚）**：`DexPatcher` 在 Android 10+ 上必须先把补丁
dex 复制到 `code_cache` 只读目录再加载——Android 10 禁止从可写目录加载 DEX。更早的产物
（如 `test_assets/app-v1.5.apk` 里那份，2026-01-21）缺少这一步，注入会静默失败。
对应提交：`b5f43b5`「修复 Android 10+ 可写目录 DEX 加载限制」。

**运行时依赖**：`net.lingala.zip4j:zip4j:2.11.5`（ZIP 密码支持），已在 `core/app/build.gradle.kts`
中声明。该 AAR 不引用 apksig/bouncycastle（补丁签名走 JAR 签名而非 apksig）。

**更新方式**：从上述仓库重新构建 `:update:assembleRelease`，替换本文件并同步
`build.gradle.kts` 里的版本号与 `.gitignore` 的白名单条目。

## 已知缺陷与规避

patch-cli 生成的补丁包内 dex 会沿用 base APK 的 dex 名（ACS 有 12+ 个 dex，会产出
`classes8.dex` / `classes12.dex`）。而 Android 的 `DexPathList` 只按**连续**的
`classes.dex` → `classes2.dex` → … 查找，跳号即补丁内的类全部不可见——
表现为「补丁应用成功、DEX 注入 ✓、日志无异常，但代码毫无变化」的**静默失败**。

规避在两侧都做（双保险）：
- CI：`tools/hotupdate-normalize-patch.py` 在生成后规范化 dex 名；
- App：`HotUpdateManager.normalizeDexNames` 在应用前再规范化一次。

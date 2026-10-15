# targetSdk 约束与升级评估

> **用途**：说明本仓库为什么钉在 `targetSdk=28`、这个约束的技术依据是什么、
> 以及将来若要升级需要移植哪些补丁。**升级前必读。**
>
> **结论快照**：2026-10-15。当前**不建议**升级；触发条件与补丁清单见 §5、§6。
>
> 本文的结论全部基于在 `OnePlus PJA110（Android 16 / SDK 36，安全补丁 2026-08-01）`
> 与 `黑鲨 SKW-A0（Android 10 / SDK 29）` 上的**实测**，证据见 §3。

---

## 1. 一句话结论

**`targetSdk=28` 不是历史遗留，而是架构前提**——它是**最后一个**允许执行
应用私有目录内二进制的 targetSdk 取值（`29` 起被禁止）。本仓库的终端、构建、
CodeGraph 等核心能力全部建立在这个前提之上。升级 `targetSdk` 会**主动**切断这些能力。

---

## 2. 机制澄清（重要）

这个问题的公开讨论里混杂了两种说法，本文实测确认了**哪一种是对的**：

| 说法 | 是否正确 | 说明 |
|---|---|---|
| "SELinux 的 exec 限制不看 targetSdk" | ❌ **错** | 实测证伪，见 §3 |
| "targetSdk ≥ 30 才禁止执行" | ⚠️ 部分对 | 方向对，但边界值是 **29**（不是 30），且机制是"域映射"不是"策略开关" |
| **"targetSdk 决定进程落在哪个 SELinux 域，域决定 exec 权限"** | ✅ **对** | 本文结论，证据见 §3 |

### 机制链条

```
应用安装时的 targetSdk
        ↓  （由 /system/etc/selinux/plat_seapp_contexts 的 minTargetSdkVersion 匹配）
进程启动时被分配到某个 SELinux 域
        ↓  （如 untrusted_app_27 / untrusted_app_29 / untrusted_app）
该域对 app_data_file 类型的文件是否有 (allow ... (file (execute_no_trans)))
        ↓
能否 execve() 应用私有目录内的二进制（$PREFIX/bin/bash 等）
```

关键点：**`app_data_file` 是所有应用私有文件的统一 SELinux 类型**，与
应用本身无关。限制针对的是"文件在私有目录"这个事实。

---

## 3. 实测证据

### 3.1 域映射（设备原文）

`plat_seapp_contexts`（Android 16 设备，按 `minTargetSdkVersion` 从高到低匹配）：

```
user=_app minTargetSdkVersion=34 domain=untrusted_app     type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=32 domain=untrusted_app_32  type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=30 domain=untrusted_app_30  type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=29 domain=untrusted_app_29  type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=28 domain=untrusted_app_27  type=app_data_file levelFrom=all
user=_app minTargetSdkVersion=26 domain=untrusted_app_27  type=app_data_file levelFrom=user
user=_app                        domain=untrusted_app_25 type=app_data_file levelFrom=user
```

本应用（`targetSdk=28`）实测落在 `untrusted_app_27`：

```
$ adb shell ps -A -Z | grep rv2ide
u:r:untrusted_app_27:s0:c235,c260,c512,c768  u0_a1259  14030  ...  com.tom.rv2ide
```

### 3.2 exec 权限（`plat_sepolicy.cil` 原文）

| SELinux 域 | 匹配的 targetSdk | 对 `app_data_file` 的 exec 权限 |
|---|---|---|
| `untrusted_app_25` | 26–27 | ✅ `allow (file (execmod))` + `execute_no_trans` |
| **`untrusted_app_27`** | **28** | ✅ `allow (file (execmod))` + `execute_no_trans` |
| `untrusted_app_29` | 29–31 | ❌ **无任何 exec 权限** |
| `untrusted_app_30` | 32–33 | ❌ 无 |
| `untrusted_app_32` | 34–35 | ❌ 无 |
| `untrusted_app` | 36+ | ❌ 无 |

`untrusted_app_27` 的原文（本应用所在域）：

```
(allow untrusted_app_27 app_data_file (file (execmod)))
(allow untrusted_app_27 app_data_file (file (execute_no_trans)))
(auditallow untrusted_app_27 app_data_file (file (execute execute_no_trans)))
```

**`targetSdk=28` 是临界值**——`seapp_contexts` 取"最高的匹配项"，
所以 `targetSdk=29` 会落到 `untrusted_app_29`（无 exec），而 `targetSdk=28`
落在 `untrusted_app_27`（有 exec）。**差一个数就从"能执行"变成"不能执行"。**
这是 Termux 长期钉在 `targetSdk=28` 的同一个原因。

注意 `execmod`：只有 `_25`/`_27` 两个域有它（允许执行被修改过的文件映射），
现代域连它也没有——这正是 W^X（不可同时可写与可执行）语义的体现。

### 3.3 运行状态证据

Android 16 设备上本应用的实际工作痕迹（`files/` 目录）：

| 证据 | 说明 |
|---|---|
| `phone/acs_ui_dump_*.xml`（78 KB，**owner = `shell`**） | 通过 Shizuku（uid 2000）执行 uiautomator2 dump 产生的文件 |
| `u2/u2.jar`（3.7 MB） | uiautomator2 服务包已就位 |
| Shizuku 已安装（`moe.shizuku.privileged.api`） | 但**服务进程未运行**，见 §4 |

即：**Shizuku 后端链路在 Android 16 上可用**，且它**天然绕开**本约束——
Shizuku 以 `shell` 身份执行，二进制不在应用私有目录。

---

## 4. 当前状态与残余风险

| 能力 | 依赖 | Android 16 上状态 |
|---|---|---|
| Termux 终端（`$PREFIX/bin/bash`） | `untrusted_app_27` 的 exec 权限 | ✅ 前提成立（targetSdk=28） |
| Gradle 构建（Tooling API / JVM） | 同上 | ✅ 同上 |
| CodeGraph wrapper（`ProcessBuilder` 直接调） | 同上 | ✅ 同上 |
| Shizuku shell 后端 | 不需要 exec 私有目录 | ✅ 已实测工作 |
| 手机自动化（uiautomator2） | Shizuku | ✅ 已实测工作 |

**残余风险（值得留意，但当前不构成问题）**：

1. **Shizuku 服务未运行**——`PROGRESS.md` 记录黑鲨上"已安装、服务未运行"；
   Android 16 设备上本次实测同样未见 shizuku 进程。用到 Shizuku 后端前需先启动服务。
2. **系统升级可能改策略**——`plat_sepolicy.cil` 随安全补丁更新（本机为
   `2026-08-01`）。虽然 `_27` 域的 exec 权限属于"为兼容老应用保留"的稳定策略，
   但理论上存在被收紧的可能。**若某天终端突然无法启动，第一件事是复查本文 §3.2**。
3. **`compileSdk=34` 而设备是 36**——不影响运行，但意味着我们没在编译期接触
   36 的 API 面。升级 `compileSdk` 与升级 `targetSdk` 是**两件独立的事**，
   前者风险低得多（见 §5）。

---

## 5. 升级评估

### 5.1 结论：当前不升级

| | 收益 | 成本 |
|---|---|---|
| 升 `targetSdk` 到 36 | **无**——我们不依赖任何 36+ 的 targetSdk 门控 API | 必须实现 §6 全套补丁（约 3000 行 C/JNI + 29 个 Java exec 调用点改造）；并触发 Android 14+ 前台服务类型强制声明、后台启动限制等连带行为变更；**且等于主动放弃 §3.2 的 exec 权限** |
| 升 `compileSdk` 到 36 | 可在编译期使用 36 的新 API；消除"设备版本高于编译版本"的错配 | 需重编所有模块；`composite-builds` 的 vendored 依赖可能需要同步；**不触碰 SELinux 域，风险低** |

**核心判断**：升 `targetSdk` 的收益是 0（没有我们需要的门控行为），
成本是自断架构前提。这不是"值不值得"的问题，而是"没有理由做"。

### 5.2 何时需要重新评估

满足**任一**条件时，重读本文并执行 §6：

1. **上架要求**——Google Play 强制要求新的 targetSdk 下限（通常每年 +1，
   且对既有应用有宽限期）
2. **必需 API**——某个我们非用不可的能力只在 `targetSdk >= 30` 时可用
   （注意：**大多数新 API 看的是 `compileSdk`，不是 `targetSdk`**，
   所以这条触发条件比想象中罕见）
3. **策略收紧**——§4 残余风险 2 成为现实（终端无法启动）
4. **换架构**——决定放弃"应用内执行二进制"，改为全部经 Shizuku/远程后端执行

### 5.3 若必须升级：迁移路径

分两步走，**先做低风险的那步**：

- **步骤 A（低风险）**：只升 `compileSdk` → 34 保持 `targetSdk=28`。
  验证终端/构建不受影响。这能解决大部分"想用新 API"的需求。
- **步骤 B（高风险）**：升 `targetSdk` → 必须同时完成 §6 的全部移植，
  否则终端、构建、CodeGraph 全线失效。

---

## 6. 升级时需要移植的补丁清单

来源：`Rkorona/AndroidIDE`（AndroidIDE 的 fork，已实现 targetSdk 37 下的 exec 兼容）。

> 注意：该 fork 的包名与基线是 AndroidIDE（`com.itsaky.androidide`），
> 移植到本仓库（ACS / `com.tom.rv2ide`）需要包名与路径适配。

### 6.1 核心补丁（三层修复）

| 层 | 文件 | 作用 |
|---|---|---|
| L1 | `TermuxInstaller.java` | 关闭写 fd 后以只读 fd `fchmod(0500)`——避开 W^X（可写时不可加执行位） |
| L2 | `termux/emulator/src/main/jni/termux.c` | `execvp` 失败后回退 `execv("/system/bin/linker64", [linker64, cmd, argv...])`——系统二进制执行始终允许，linker 用 mmap 加载目标 ELF |
| L3 | `termux/application/src/main/cpp/exec-wrapper.c`（新增）+ `Android.mk` | 编译 `libandroidide-exec-wrapper.so`，经 `LD_PRELOAD` 注入，拦截 bash 及其子进程的所有 `execve()` |
| L3' | `TermuxShellEnvironment.java` | 注入 `LD_PRELOAD=<nativeLibraryDir>/libandroidide-exec-wrapper.so` |

**已知陷阱**（该 fork 文档明确记录，移植时不要重踩）：

- `linker64` **只能加载 ELF，不能加载脚本**。目标是 `#!` 开头的脚本时会报
  `bad ELF magic: 23212f64`。L2 必须在调用前读前 4 字节判断。
- `LD_PRELOAD` **覆盖不到 Java 的 `ProcessBuilder`**——`ProcessBuilder.start()`
  从 JVM 自身 `fork()+execve()`，wrapper 不在其地址空间。**必须单独改造**（见 §6.2）。

### 6.2 本仓库特有的改造点（该 fork 没有）

`ProcessBuilder` 直接调用点需在 `SDK_INT >= 36` 时前置 `/system/bin/linker64`。
本仓库受影响的位置（`core/` 下 14 个文件，去掉测试与 assets 后需逐个判断）：

| 文件 | 说明 |
|---|---|
| `core/app/.../codegraph/CodeGraphManager.kt` | 直接 `ProcessBuilder` 调 wrapper（**已注释说明依赖 targetSdk=28**，升级时须一并改） |
| `core/common/.../shell/ProcessBuilderImpl.kt` | 通用 shell 抽象，影响面最大 |
| `core/common/.../shell/shellUtils.kt` | 同上 |
| `core/ai-tool/.../mcp/StdioTransport.java` | MCP stdio 传输（若走私有目录二进制） |
| `core/app/.../terminal/session/TerminalSession.kt` | 终端会话 |
| `core/app/.../handlers/system/installer/IPackageInstaller.kt` | 安装器 |
| `core/app/.../templates/android/etc/NativeCpp/NativeChecks.kt` | NDK 检查 |
| `core/app/.../editor/IDELogcatReader.kt` | logcat 读取 |

（`core/ticode/` 与 `assets/QuickDevelop/` 下的命中属于另一套移植代码，需单独确认。）

### 6.3 移植后的验证清单

1. 黑鲨（Android 10）：终端、构建、CodeGraph 全部回归——**不能因为升 targetSdk 而破坏老设备**
2. Android 16 设备：终端启动（`bash` 能跑）、`idesetup` 完成、Gradle 构建成功、
   CodeGraph wrapper 可执行、MCP stdio 可用
3. 检查 `logcat` 中无 `avc: denied` 且无 `bad ELF magic` 报错
4. 确认 `untrusted_app` 域下 `$PREFIX/bin` 的权限修复（L1）实际生效

---

## 7. 附：本次核查使用的方法

便于将来复查（策略文件位置与命令）：

```bash
# 1) 本应用落在哪个域
adb shell ps -A -Z | grep rv2ide

# 2) 域映射规则（targetSdk → domain）
adb shell cat /system/etc/selinux/plat_seapp_contexts | grep untrusted

# 3) 各域的 exec 权限
adb shell grep -E '\(allow untrusted_app_27 ' /system/etc/selinux/plat_sepolicy.cil \
  | grep app_data_file | grep exec

# 4) 安全补丁版本（策略随它更新）
adb shell getprop ro.build.version.security_patch
```

> 设备信息（核查时）：`OnePlus PJA110`，`16/BP2A.250605.015`，
> 安全补丁 `2026-08-01`，SELinux `Enforcing`。

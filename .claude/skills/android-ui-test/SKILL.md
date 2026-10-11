---
name: android-ui-test
description: 用 uiautomator2 在黑鲨实机上做 ACS 的 UI 自动化测试——按控件文本/id 找元素、点击、断言界面状态、绕过中文输入法打字。当需要复现/验证纯 UI 交互类 bug（如「停止按钮需多次点击」「卡片残留不消失」）、或 adb 深链到不了、需要模拟真实点击时使用。
---

# Android UI 自动化测试（uiautomator2）

## 适用场景

- **纯 UI 交互 bug 的复现与验证**：点击、等待、断言元素出现/消失。典型如
  「重试卡片残留」「停止按钮需多次点击」——这类 bug 深链测不了，必须模拟真实点击。
- **替代硬编码坐标点击**：按控件 text / resource-id 定位，比 `input tap x y` 稳
  （布局一变坐标就废）。
- **中文输入法吞字问题**：`send_keys` 走 ADBKeyboard 注入，不经过当前输入法，
  天然绕过「百度输入法吞拉丁字母」的历史痛点。

**不适用**：诊断/构建等无需 UI 的链路——那些走
`tools/ai-diagnostics-device-test.sh`（深链 + 日志/JSONL 断言），更快更稳。

## 环境（已就绪，直接用）

```
venv:   D:/android/u2-venv          （持久目录，Python 3.13 + uiautomator2 3.7.0）
python: D:/android/u2-venv/Scripts/python.exe
设备:   黑鲨 9c18cb30（Android 10/SDK 29）
```

**不要重装环境**。uiautomator2 首连设备会自动装 `com.github.uiautomator`（agent APK，
装一次即可）；`send_keys` 首次使用会装 AdbKeyboard IME。

> **Nico (AutoNico) 已实测不可用，别再尝试**：它的 Android 路径依赖未发布的私有包
> `auto_cathin`（PyPI/GitHub 均无），`import nico` 直接失败。uiautomator2 是替代方案。

## 快速开始

```bash
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
PYTHONIOENCODING=utf-8 "D:/android/u2-venv/Scripts/python.exe" - <<'PYEOF'
import time
import uiautomator2 as u2

d = u2.connect("9c18cb30")
print("前台:", d.app_current().get("package"))

# 找元素 → 点击 → 断言
d(text="停止").click()
time.sleep(1)
assert not d(text="重试").exists, "重试卡片应已消失"

# 等待类断言（推荐：有超时、不会瞬间误判）
ok = d(text="已完成").wait(timeout=10)      # 出现 → True；超时 → False
gone = d(text="正在重试").wait_gone(timeout=10)  # 消失 → True；仍在 → False
PYEOF
```

## 已验证 API 速查（3.7.0 实测）

| 操作 | 写法 | 备注 |
|---|---|---|
| 连接 | `u2.connect("9c18cb30")` | 首次自动装 agent |
| 前台应用 | `d.app_current()` | 返回 `{package, activity, pid}` |
| 启动应用 | `d.app_start("com.tom.rv2ide")` | 等价 am start |
| 停应用 | `d.app_stop("com.tom.rv2ide")` | 等价 force-stop |
| UI 层级 | `d.dump_hierarchy()` | 返回 XML 字符串，35KB 量级 |
| 精确找 | `d(text="打开现有项目")` | `.exists` / `.count` / `.click()` |
| 模糊找 | `d(textContains="项目")` | 还有 `textMatches`(正则) |
| 按类找 | `d(className="android.widget.EditText")` | 返回列表，`[0]` 取第一个 |
| 元素信息 | `el.info` | 含 `bounds` 等 |
| 等待出现 | `d(text="x").wait(timeout=10)` | True/False，不抛异常 |
| 等待消失 | `d(text="x").wait_gone(timeout=10)` | **元素存在时超时返回 False** |
| 点击 | `el.click()` | |
| 按键 | `d.press("back")` / `d.press("ctrl+a")` | |
| 输入文本 | `d.send_keys("hello123")` | **会切换 IME，见下方坑** |
| 截图 | `d.screenshot(r"D:/path/x.png")` | 本地路径用 Windows 格式 |

## 坑（实测，别重踩）

### 1. send_keys 会把默认输入法切走且不恢复 ⚠️

`send_keys` 首次调用时装 AdbKeyboard 并 `settings put secure default_input_method` 切过去，
**调用结束后不切回**。实测后设备默认输入法停留在 `com.github.uiautomator/.AdbKeyboard`，
用户下次打字全是英文键盘。**每次用完必须手动恢复**：

```bash
adb -s 9c18cb30 shell ime set com.baidu.input_heisha/.ImeService
# 验证: adb -s 9c18cb30 shell settings get secure default_input_method
```

规范做法：脚本开头存一份 `settings get secure default_input_method`，结尾 `ime set` 回去。

### 2. Compose 输入框的文本操作三个不准

`assistant_compose_render=true` 时 AI 面板输入框是 Compose 渲染的（dump 里是
`android.widget.EditText` 节点，但行为与 View 体系不同）：

- `set_text()` **能写入**（dump 层级里能看到文本），但 `get_text()` **读回不可靠**——
  验证用 `dump_hierarchy()` 里搜文本，别信 `get_text()`；
- `clear_text()` **无效**（Compose 不响应）——清空用 `d.press("ctrl+a")` + `d.press("delete")`。

### 3. 本地路径给 Windows 格式

脚本里 `MSYS_NO_PATHCONV=1` 会**同时关掉本地路径转换**——adb.exe / python 收到的
`/d/android/...` 无法识别。传给 adb.exe 的本地路径（push 源、截图输出）一律
`D:/...` 格式（或 `cygpath -m` 转）。

### 4. 元素文本以 dump 为准，别猜

按文本找之前先 `dump_hierarchy()` 看一眼真实文本（可能有换行、空格、动态内容）。
找不到元素时先 dump，再决定用 `text` / `textContains` / `resourceId`。

## 与现有测试脚本的关系

| 工具 | 适用 | 断言方式 |
|---|---|---|
| `tools/ai-diagnostics-device-test.sh` | 诊断链路（无 UI 交互） | 深链发送 + JSONL/logcat |
| 本技能（uiautomator2） | 纯 UI 交互（点击/等待/消失） | 元素 exists / wait / wait_gone |

UI bug 的完整验证循环：`u2 复现（红色）→ 改代码 → 装新包 → u2 再跑（绿色）`。
装包用绝对路径 + `adb install -r -d`（见 [[build-and-device]]，别卸载）。

#!/usr/bin/env bash
# AI diagnostics 工具实机回归测试：验证 LSP 诊断在真机上给出与 javac 一致的结论。
#
# 覆盖两类历史 bug（都表现为「诊断与磁盘内容不符」）：
#   1. 方法体剪枝误报「缺少返回语句」——对未打开文件跑诊断时 isActive=false，
#      方法体被剪成空壳，正确/错误代码都报同一条假错误。
#      修复：JavaCompilerImpl.parse 剪枝条件去掉 isActive。
#   2. 编辑粘滞——外部写盘后诊断读到编辑器旧内容（缓存命中 + 快照来源）。
#      修复：FileManager 磁盘优先 + JavaDiagnosticProvider 按文件缓存/磁盘快照。
#
# 方法：错误版（return 123，应报 incompatible types）与正确版（return "123"，
# 应无诊断）各跑一次全链路——推夹具 → 冷启动自动开项目 → 等 LSP 就绪 →
# adb 深链自动发诊断请求 → 拉会话 JSONL 断言（只认本次发送之后的新结果）。
#
# 用法:
#   tools/ai-diagnostics-device-test.sh                 # 跑完整对照
#   tools/ai-diagnostics-device-test.sh --install       # 先构建+安装当前代码
#   tools/ai-diagnostics-device-test.sh --serial <id>   # 指定设备（默认 9c18cb30）
#   tools/ai-diagnostics-device-test.sh --keep          # 跑完不恢复 prefs/夹具
#
# 前置:
#   - 设备已装 **Debug 版** ACS（深链 ai_prompt/ai_autosend 仅 Debug 生效）
#   - 项目 MyQuickDevelop 存在（ListBox.java 夹具所在，脚本会创建/覆盖）
#   - 设备经 adb 连接；脚本临时开启 autoOpenProjects，结束恢复
#
# 退出码: 0=断言全部通过; 1=断言失败; 2=前置不满足（设备/项目/构建缺失）
set -uo pipefail

# ── 可覆盖参数 ────────────────────────────────────────────────────────────────
SERIAL="${SERIAL:-9c18cb30}"
ADB="${ADB:-/d/android/platform-tools/adb.exe}"
# 转 Windows 混合路径（D:/...）：脚本内 MSYS_NO_PATHCONV=1 会同时关掉本地路径转换，
# adb.exe 是 Windows 程序，不认 Git Bash 的 /d/... 风格本地路径。
REPO="$(cygpath -m "$(cd "$(dirname "$0")/.." && pwd)")"
TMP="$REPO/.verify"
JAVA_HOME_OVERRIDE="${JAVA_HOME_OVERRIDE:-C:/Users/70641/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2}"

PKG=com.tom.rv2ide
PROJ=/data/data/$PKG/files/home/ACSProjects/MyQuickDevelop
JAVA_FILE="$PROJ/app/src/main/java/com/example/myquickdevelop/ui/ListBox.java"
CONV_DIR=/data/data/$PKG/files/ai/conversations
PREFS=/data/data/$PKG/shared_prefs/${PKG}_preferences.xml
APK="$REPO/core/app/build/outputs/apk/debug/android-code-studio-arm64-v8a-debug-1.0.0+gh.r06.apk"

INSTALL=0; KEEP=0
while [ $# -gt 0 ]; do
  case "$1" in
    --install) INSTALL=1 ;;
    --keep)    KEEP=1 ;;
    --serial)  shift; SERIAL="$1" ;;
    *) echo "未知参数: $1"; exit 2 ;;
  esac
  shift
done

export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
PY="/c/Program Files/Python313/python"
FAILED=0

say()  { echo "── $*"; }
fail() { echo "!! 断言失败: $*"; FAILED=1; }

adb_() { "$ADB" -s "$SERIAL" "$@"; }
# run-as 读应用私有目录（Debug 包 debuggable=true）
as_cat() { adb_ shell run-as $PKG cat "$1"; }

# ── 前置检查 ─────────────────────────────────────────────────────────────────
say "前置检查"
adb_ get-state >/dev/null 2>&1 || { echo "!! 设备 $SERIAL 未连接"; exit 2; }
if ! adb_ shell dumpsys package $PKG 2>/dev/null | grep -q "DEBUGGABLE"; then
  echo "!! 设备上的 $PKG 不是 Debug 构建——深链预填/直发仅在 Debug 生效"; exit 2
fi
if ! as_cat "$PREFS" | grep -q "MyQuickDevelop"; then
  echo "!! 偏好里没有 MyQuickDevelop（ide_last_project），先手动打开一次该项目"; exit 2
fi
if [ "$INSTALL" = 1 ]; then
  say "构建 Debug APK（当前代码）"
  (cd "$REPO" && JAVA_HOME="$JAVA_HOME_OVERRIDE" \
      SIGNING_STORE_FILE="D:/android/keys/acs-debug-signing.jks" \
      SIGNING_STORE_PASSWORD=android SIGNING_KEY_PASSWORD=android SIGNING_KEY_ALIAS=AndroidCS \
      ./gradlew :core:app:assembleDebug --offline 2>&1 | iconv -f GBK -t UTF-8 -c | tail -3)
  [ -f "$APK" ] || { echo "!! 构建产物不存在: $APK"; exit 2; }
  say "安装（绝对路径）: $(md5sum "$APK" | cut -d' ' -f1)"
  adb_ install -r -d "$APK" | tail -1
else
  echo "  （未 --install；使用设备上现有 APK）"
fi

# ── 测试夹具 ─────────────────────────────────────────────────────────────────
mkdir -p "$TMP"
cat > "$TMP/ListBox_broken.java" <<'EOF'
package com.example.myquickdevelop.ui;

import android.content.Context;

/** ListBox：{@link 列表} 的英文别名，行为完全一致。 */
public class ListBox extends 列表 {

    public ListBox(Context context) {
        super(context);
    }

    public String ping() {
        return 123;
    }
}
EOF
sed 's/return 123;/return "123";/' "$TMP/ListBox_broken.java" > "$TMP/ListBox_correct.java"

push_fixture() {  # $1 = 本地文件
  adb_ push "$1" /data/local/tmp/LB.java >/dev/null 2>&1
  adb_ shell run-as $PKG cp /data/local/tmp/LB.java "$JAVA_FILE"
  adb_ shell rm -f /data/local/tmp/LB.java
}

set_auto_open() {  # $1 = true|false；调用方负责先 force-stop（prefs 有内存缓存）
  local cur; cur="$(as_cat "$PREFS")"
  echo "$cur" | sed "s#\(name=\"idepref_general_autoOpenProjects\" value=\"\)[a-z]*#\1$1#" > "$TMP/prefs_edit.xml"
  adb_ push "$TMP/prefs_edit.xml" /data/local/tmp/prefs_edit.xml >/dev/null 2>&1
  adb_ shell run-as $PKG cp /data/local/tmp/prefs_edit.xml "$PREFS"
  adb_ shell rm -f /data/local/tmp/prefs_edit.xml
}

# ── 启动项目并等待 LSP 就绪 ───────────────────────────────────────────────────
start_project_and_wait() {
  say "冷启动 → 自动打开 MyQuickDevelop → 等 LSP 就绪"
  adb_ shell am force-stop $PKG
  set_auto_open true
  adb_ logcat -c
  adb_ shell am start -n $PKG/.activities.MainActivity >/dev/null
  # 等 Kotlin LSP 初始化成功（workspace setup 完成的可靠信号；Java LSP 同时机 setup）
  local i=0
  while [ $i -lt 36 ]; do
    if adb_ logcat -d 2>/dev/null | grep -q "Server initialized successfully"; then
      say "  LSP 就绪（约 $((i*5))s）"; sleep 3; return 0
    fi
    sleep 5; i=$((i+1))
  done
  echo "!! 180s 内未见 LSP 就绪；当前前台: $(adb_ shell dumpsys activity activities | grep -m1 mResumedActivity)"
  return 1
}

# ── 深链发送并断言 ───────────────────────────────────────────────────────────
# $1 = 提示词, $2 = 期望包含的子串（| 分隔）, $3 = 断言名
send_and_assert() {
  local prompt="$1" expect="$2" name="$3"
  say "发送 [$name]"

  # 记录目标会话与起始行数：只认「本次发送之后」新增的 diagnostics 结果，
  # 否则旧会话里已有同类结果会造成假通过（历史上踩过）。
  local target floor
  target="$(adb_ shell run-as $PKG ls -t "$CONV_DIR" 2>/dev/null | grep jsonl | head -1 | tr -d '\r')"
  floor=0
  if [ -n "$target" ]; then
    floor="$(as_cat "$CONV_DIR/$target" 2>/dev/null | wc -l | tr -d ' ')"
  fi
  say "  目标会话: ${target:-<新建>}，起始行数: $floor"

  adb_ shell "am start -n $PKG/.activities.editor.AssistantFullscreenActivity -e ai_prompt '$prompt' -e ai_autosend true" >/dev/null 2>&1

  # 轮询（最长 120s）：出现本次新增的 diagnostics tool-result 即判定
  local i=0
  while [ $i -lt 24 ]; do
    sleep 5; i=$((i+1))
    local latest; latest="$(adb_ shell run-as $PKG ls -t "$CONV_DIR" 2>/dev/null | grep jsonl | head -1 | tr -d '\r')"
    [ -n "$latest" ] || continue
    if [ "$latest" != "$target" ]; then target="$latest"; floor=0; fi
    as_cat "$CONV_DIR/$latest" > "$TMP/conv_$name.jsonl" 2>/dev/null
    "$PY" - "$TMP/conv_$name.jsonl" "$expect" "$name" "$floor" <<'PYEOF'
import json, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
path, expect, name, floor = sys.argv[1], sys.argv[2].split('|'), sys.argv[3], int(sys.argv[4])
lines = open(path, encoding='utf-8').read().splitlines()
last = None
for ln in lines[floor:]:                      # 只认本次新增部分
    try: o = json.loads(ln)
    except: continue
    if o.get('type') == 'tool-result' and o.get('toolName') == 'diagnostics':
        last = o
if last is None:
    sys.exit(3)                               # 尚无新结果，继续等
content = json.dumps(last.get('content'), ensure_ascii=False)
if '未知工具' in content:
    print(f"[{name}] 工具未注册（装错包？）: {content[:200]}"); sys.exit(1)
RETRYABLE = ('语言服务器当前不可用', '超时', '尚未完成', '不属于编辑器当前打开的项目',
             '没有已完成 Gradle 同步的项目', '不属于任何已识别的 Gradle 模块')
if any(r in content for r in RETRYABLE):
    print(f"[{name}] 环境未就绪，等待重试: {content[:200]}"); sys.exit(3)
ok = any(e in content for e in expect)
print(f"[{name}] diagnostics 返回: {content[:400]}")
sys.exit(0 if ok else 1)
PYEOF
    local rc=$?
    case $rc in
      0) say "  [$name] 断言通过"; return 0 ;;
      1) fail "[$name] 内容与期望不符（期望含: $expect）"; return 1 ;;
      3) : ;;                                  # 继续轮询
    esac
  done
  fail "[$name] 120s 内未等到新的 diagnostics 结果"
  return 1
}

# ── 主流程 ───────────────────────────────────────────────────────────────────
push_fixture "$TMP/ListBox_broken.java"
start_project_and_wait || { echo "!! 项目未就绪，终止"; exit 2; }

send_and_assert \
  "用 diagnostics 工具分析 app/src/main/java/com/example/myquickdevelop/ui/ListBox.java 报告编译错误。只用 diagnostics，不要用 gradle_build，不要改文件。" \
  "不兼容的类型|incompatible" "broken"

# 改成正确版，再跑一次（同会话追加；singleTop 复用走 onNewIntent 深链路径）
push_fixture "$TMP/ListBox_correct.java"
send_and_assert \
  "再次用 diagnostics 工具分析 app/src/main/java/com/example/myquickdevelop/ui/ListBox.java 报告编译错误。只用 diagnostics，不要用 gradle_build，不要改文件。" \
  "未发现" "correct"

# ── 恢复现场 ─────────────────────────────────────────────────────────────────
if [ "$KEEP" = 1 ]; then
  say "--keep：跳过恢复"
else
  say "恢复现场"
  adb_ shell am force-stop $PKG
  push_fixture "$TMP/ListBox_broken.java"     # 夹具恢复到文档化的故障版
  set_auto_open false
fi

echo ""
if [ $FAILED = 0 ]; then echo "== 全部断言通过 =="; exit 0
else echo "== 存在失败断言 =="; exit 1; fi

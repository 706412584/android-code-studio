#!/usr/bin/env bash
# AI 工具「真实调用」实机验证：不注入假服务器，用设备上真实配置的 provider，
# 让真模型自己决定调用哪个工具、怎么构造参数——验证工具描述/schema 是否让模型
# 能正确理解并使用（假服务器只能验证工具层执行，验证不了这一点）。
#
# 机制（复用 ai-diagnostics-device-test.sh 的装置）：
#   - 冷启动 → autoOpenProjects=true 自动开 MyQuickDevelop → 等 LSP 就绪；
#   - adb 深链 am start ...AssistantFullscreenActivity -e ai_prompt ... -e ai_autosend true
#     发一条自然语言提示；
#   - 轮询会话 JSONL，断言「本次新增的 tool-result 里，期望工具返回了期望内容」；
#   - 模型没调期望工具时，报告它实际调了什么（这本身是有价值的发现——工具描述
#     没让模型理解该在什么场景用它）。
#
# 防设备争用（2026-10-12 实测踩坑——另一个会话同时在同一设备跑测试，表现为
# 请求无响应/断言目标漂移；那时无从判断，白等 240s×N）：
#   - 启动前检测「最近 2 分钟内被写过的其它会话文件」，有则拒绝启动；
#   - 发送前先等设备侧「上一轮运行」结束（深链排队不报错、只是静默延迟）；
#   - 目标会话按「发送时刻起被写过的文件」锚定，而非盲取最新文件——另一会话
#     新建文件时不会把断言带偏；
#   - 快速失败：设备 logcat 里出现本次发送后的 provider 错误（HTTP 4xx/5xx）
#     立即报失败，不干等满轮询窗口。
#
# 内置套件 ide6 覆盖 6 个 IDE 集成工具：
#   editor_state / file_open / save_all / code_actions / build_variant / workspace_symbol
#
# 用法:
#   tools/ai-real-invoke-device-test.sh                     # 跑内置 ide6 套件
#   tools/ai-real-invoke-device-test.sh --case file_open    # 只跑一个用例
#   tools/ai-real-invoke-device-test.sh --case file_open,save_all
#   tools/ai-real-invoke-device-test.sh --prompt "..." --tool editor_state --expect "当前文件"
#   tools/ai-real-invoke-device-test.sh --serial <id> --keep
#   tools/ai-real-invoke-device-test.sh --force       # 跳过争用检测（确认设备空闲时用）
#
# 前置:
#   - 设备已装 **Debug 版** ACS（深链 ai_prompt/ai_autosend 仅 Debug 生效）
#   - 设备上 AI provider 已配置且可用（真实调用会消耗额度）
#   - 项目 MyQuickDevelop 存在；脚本临时开启 autoOpenProjects，结束恢复
#   - 设备空闲（无其它会话在跑同类测试——脚本会自检并拒绝启动）
#
# 退出码: 0=断言全部通过; 1=断言失败; 2=前置不满足（设备/项目/争用）
set -uo pipefail

# ── 可覆盖参数 ────────────────────────────────────────────────────────────────
SERIAL="${SERIAL:-9c18cb30}"
ADB="${ADB:-/d/android/platform-tools/adb.exe}"
REPO="$(cygpath -m "$(cd "$(dirname "$0")/.." && pwd)")"
TMP="$REPO/.verify"

PKG=com.tom.rv2ide
PROJ=/data/data/$PKG/files/home/ACSProjects/MyQuickDevelop
UI_DIR="$PROJ/app/src/main/java/com/example/myquickdevelop/ui"
CONV_DIR=/data/data/$PKG/files/ai/conversations
PREFS=/data/data/$PKG/shared_prefs/${PKG}_preferences.xml

KEEP=0
CASE_LIST=""
CUSTOM_PROMPT="" CUSTOM_TOOL="" CUSTOM_EXPECT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --keep)   KEEP=1 ;;
    --force)  FORCE=1 ;;   # 跳过争用检测（确认设备上另一会话已停时用）
    --serial) shift; SERIAL="$1" ;;
    --case)   shift; CASE_LIST="$1" ;;
    --prompt) shift; CUSTOM_PROMPT="$1" ;;
    --tool)   shift; CUSTOM_TOOL="$1" ;;
    --expect) shift; CUSTOM_EXPECT="$1" ;;
    *) echo "未知参数: $1"; exit 2 ;;
  esac
  shift
done
FORCE="${FORCE:-0}"

export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
PY="/c/Program Files/Python313/python"
FAILED=0

say()  { echo "── $*"; }
fail() { echo "!! 断言失败: $*"; FAILED=1; }

adb_() { "$ADB" -s "$SERIAL" "$@"; }
as_cat() { adb_ shell run-as $PKG cat "$1"; }
push_private() {  # $1=本地文件 $2=设备私有路径
  adb_ push "$1" /data/local/tmp/_r6 >/dev/null 2>&1
  adb_ shell run-as $PKG cp /data/local/tmp/_r6 "$2"
  adb_ shell rm -f /data/local/tmp/_r6
}

# ── 用例表 ───────────────────────────────────────────────────────────────────
# 格式：名称|提示词|期望工具名|期望子串（| 分隔多个可选）
# 提示词明确点名工具与关键参数——真实调用的验证目标是「模型能否理解并按工具
# 描述正确调用」，不是「模型能否自由发挥选对工具」（后者噪声太大）。
run_case() {
  local name="$1" prompt="$2" tool="$3" expect="$4"
  say "用例 [$name] → 期望工具 $tool"
  echo "  提示词: $prompt"

  # 设备侧时间锚点：本次发送时刻（毫秒）。轮询里：
  #   - 会话文件「mtime ≥ 锚点」才算本次写过的文件（防另一会话的新文件带偏）；
  #   - logcat -T 从锚点起查本次的 provider 错误（fast-fail）。
  local t0 t0_logcat
  t0="$(adb_ shell 'date +%s%3N' | tr -d '\r')"
  t0_logcat="$(adb_ shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')"
  say "  时间锚点: $t0 / $t0_logcat"

  # 发送前先等设备空闲：上一轮运行还在跑时深链只是把提示排进队列（不报错），
  # 等它结束再发，否则断言会拿到上一轮的旧结果或等到超时。
  local w=0
  while [ $w -lt 60 ]; do
    if adb_ shell dumpsys activity activities 2>/dev/null | grep -m1 mResumedActivity | grep -q "AssistantFullscreenActivity"; then
      # 全屏助手在前台：可能正在运行，也可能只是停着。用「停止键是否存在」判断。
      local running
      running="$(/d/android/u2-venv/Scripts/python.exe -c "
import uiautomator2 as u2
d = u2.connect('$SERIAL')
xml = d.dump_hierarchy()
print('RUNNING' if ('处理中' in xml or '停止' in xml or '重试' in xml) else 'IDLE')
" 2>/dev/null | tail -1)"
      if [ "$running" = "IDLE" ]; then break; fi
      say "  设备上一轮运行中，等待结束（${w}0s）"
    else
      break
    fi
    sleep 10; w=$((w+1))
  done

  adb_ shell "am start -n $PKG/.activities.editor.AssistantFullscreenActivity -e ai_prompt '$prompt' -e ai_autosend true" >/dev/null 2>&1

  # 轮询（最长 240s；真模型思考 + 工具执行比假服务器慢）
  local i=0
  while [ $i -lt 48 ]; do
    sleep 5; i=$((i+1))

    # fast-fail：本次发送后设备日志里出现 provider 错误码 → 立即失败，不干等。
    local http_err
    http_err="$(adb_ shell "logcat -d -T '$t0_logcat' 2>/dev/null" | grep -oE "HTTP (4[0-9]{2}|5[0-9]{2})" | tail -1)"
    if [ -n "$http_err" ]; then
      fail "[$name] provider 返回 $http_err（服务端故障，稍后重试或换 provider）"
      return 1
    fi

    # 找「发送后写过」的会话文件（可能多个——取工具结果所属的那个）。
    local candidates="" f
    for f in $(adb_ shell run-as $PKG ls -t "$CONV_DIR" 2>/dev/null | grep '\.jsonl$' | tr -d '\r' | head -5); do
      local mt
      mt="$(adb_ shell "run-as $PKG stat -c '%Y' '$CONV_DIR/$f'" 2>/dev/null | tr -d '\r')"
      # 文件 mtime（秒）≥ 锚点（毫秒/1000 - 2s 容差）
      if [ -n "$mt" ] && [ "$(( mt * 1000 ))" -ge "$(( t0 - 2000 ))" ]; then
        candidates="$candidates $f"
      fi
    done
    [ -n "$candidates" ] || continue

    for f in $candidates; do
      as_cat "$CONV_DIR/$f" > "$TMP/real_conv_$name.jsonl" 2>/dev/null
      "$PY" - "$TMP/real_conv_$name.jsonl" "$tool" "$expect" "$name" "$t0" <<'PYEOF'
import json, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
path, tool, expect, name, t0 = sys.argv[1], sys.argv[2], sys.argv[3].split('|'), sys.argv[4], int(sys.argv[5])
lines = open(path, encoding='utf-8').read().splitlines()
found = None
called = []          # 本次发送后模型实际调用的所有工具（顺序）
for ln in lines:
    try: o = json.loads(ln)
    except: continue
    if o.get('type') != 'tool-result': continue
    # 时间戳过滤：只认本次发送之后的条目（t0-2s 容差吸收设备/主机时钟差）。
    ts = o.get('timestamp') or 0
    if ts < t0 - 2000:
        continue
    tn = o.get('toolName', '?')
    called.append(tn)
    if tn == tool:
        found = o
if found is None:
    if called:
        print(f"[{name}] 模型未调用 {tool}；实际调用: {called}"); sys.exit(1)
    sys.exit(3)                               # 尚无任何新结果，继续等
content = json.dumps(found.get('content'), ensure_ascii=False)
if '未知工具' in content:
    print(f"[{name}] 工具未注册（装错包？）: {content[:200]}"); sys.exit(1)
RETRYABLE = ('语言服务器当前不可用', '超时', '尚未完成', '没有已完成 Gradle 同步的项目',
             '不属于任何已识别的 Gradle 模块', '当前没有在编辑器中打开项目')
if any(r in content for r in RETRYABLE):
    print(f"[{name}] 环境未就绪，等待重试: {content[:200]}"); sys.exit(3)
ok = any(e in content for e in expect)
print(f"[{name}] {tool} 返回: {content[:400]}")
sys.exit(0 if ok else 1)
PYEOF
      local rc=$?
      case $rc in
        0) say "  [$name] 断言通过"; return 0 ;;
        1) fail "[$name] 内容与期望不符（期望含: $expect）"; return 1 ;;
        3) : ;;                              # 本文件尚无新结果，继续看下一个/轮询
      esac
    done
  done
  fail "[$name] 240s 内未等到新的 $tool 结果"
  return 1
}

# 内置套件：6 个 IDE 集成工具
# 注意 save_all 的目标文件是「编辑器当前打开的文件」（会话恢复后的），提示词
# 不指定路径；断言兼容「已保存」与「没有需要保存」两种正确结果。
case_prompt() {
  case "$1" in
    editor_state)
      echo "用 editor_state 工具告诉我编辑器当前打开了什么文件、光标在哪一行。只用这一个工具，不要做别的。"
      ;;
    file_open)
      echo "用 file_open 工具在编辑器里打开 app/src/main/java/com/example/myquickdevelop/ui/列表.java 的第 1 行。只用这一个工具，不要做别的。"
      ;;
    save_all)
      echo "用 save_all 工具保存编辑器里所有未保存的文件。只用这一个工具，不要做别的。"
      ;;
    code_actions)
      echo "用 code_actions 工具给 app/src/main/java/com/example/myquickdevelop/ui/ListBox.java 添加 import：action 用 add_import，argument 用 java.util.ArrayList，line 用 1，column 用 1。只用这一个工具，不要做别的。"
      ;;
    build_variant)
      echo "用 build_variant 工具列出当前项目的所有模块和构建变体（action 用 list）。只用这一个工具，不要做别的。"
      ;;
    workspace_symbol)
      echo "用 workspace_symbol 工具在工作区里搜索名字包含 ListBox 的符号。只用这一个工具，不要做别的。"
      ;;
  esac
}
case_expect() {
  case "$1" in
    editor_state)     echo "当前文件|已打开文件" ;;
    file_open)        echo "已在编辑器中打开|列表.java" ;;
    save_all)         echo "已保存|没有需要保存" ;;
    code_actions)     echo "代码动作已完成|未发生改动" ;;
    build_variant)    echo ":app|:ticode" ;;
    workspace_symbol) echo "ListBox" ;;
  esac
}
# 顺序有讲究：file_open 先打开文件，editor_state 随后就能看到打开的文件（验证更实）；
# build_variant 依赖项目同步完成，靠轮询兜底（首轮可能因同步未完成而等待）。
ALL_CASES="file_open editor_state save_all code_actions build_variant workspace_symbol"

# ── 前置检查 ─────────────────────────────────────────────────────────────────
say "前置检查"
adb_ get-state >/dev/null 2>&1 || { echo "!! 设备 $SERIAL 未连接"; exit 2; }
if ! adb_ shell dumpsys package $PKG 2>/dev/null | grep -q "DEBUGGABLE"; then
  echo "!! 设备上的 $PKG 不是 Debug 构建——深链预填/直发仅在 Debug 生效"; exit 2
fi
# 项目目录存在即可——ide_last_project 由脚本注入段钉住（跑完恢复），不要求
# 用户手动打开过（另一会话可能随时把它改成别的项目，检查 prefs 值不可靠）。
# settings.gradle 或 settings.gradle.kts 任一存在即视为项目（模板可能生成后者）。
# 注意 set -o pipefail：ls 对缺失的第二个文件返回 1，会污染管道退出码——
# 用 || true 吸收，判定只看 grep 的输出匹配（grep 退出码经 if 判断）。
if ! { adb_ shell "run-as $PKG ls $PROJ/settings.gradle $PROJ/settings.gradle.kts" 2>/dev/null || true; } | grep -q settings; then
  echo "!! MyQuickDevelop 项目不存在（$PROJ），先手动打开/创建一次该项目"; exit 2
fi
# 真实 provider 检查：提示词会走设备当前配置的 provider（会消耗额度）
PROVIDER="$(as_cat "$PREFS" | grep -o 'name="ai_provider_name">[^<]*' | sed 's/.*>//')"
echo "  使用设备真实 provider: ${PROVIDER:-<未配置>}（真实调用，会消耗额度）"
[ -n "$PROVIDER" ] || { echo "!! 设备未配置 AI provider"; exit 2; }

# 争用检测：最近 2 分钟内被写过的其它会话文件 = 另一个会话正在用同一设备跑
# 同类测试（2026-10-12 实测：两个会话同时发请求，请求互相排队、prefs 互相覆盖，
# 谁的结果都不可信）。有则拒绝启动。
say "争用检测（最近 2 分钟内活跃的会话文件）"
recent="$({ adb_ shell "run-as $PKG find $CONV_DIR -name '*.jsonl' -mmin -2 2>/dev/null"; } | tr -d '\r')"
if [ -n "$recent" ]; then
  echo "!! 检测到最近 2 分钟内有会话文件被写入："
  echo "$recent" | sed 's/^/     /'
  echo "   另一个会话可能正在同一设备上跑测试。等它结束（或 --force 忽略）再跑。"
  if [ "${FORCE:-0}" != "1" ]; then exit 2; fi
  echo "   （FORCE=1，继续）"
else
  echo "  OK（无活跃会话）"
fi

# provider 健康预检：设备网络直连 provider 端点。2026-10-12 实测 agnes 端点
# 返回 503，脚本干等 240s×N 才暴露——这里提前一次 curl，故障时立即退出。
# 从 providers.json 取当前 provider 的 baseUrl（设备与主机同网络，主机 curl 可达）。
say "provider 健康预检"
PJSON="$(as_cat /data/data/$PKG/files/ai/providers.json)"
BASEURL="$(echo "$PJSON" | "$PY" -c "
import json, sys
try: records = json.load(sys.stdin)
except Exception: sys.exit(0)
name = '$PROVIDER'
for r in records:
    if r.get('id') == name or r.get('label') == name:
        print(r.get('baseUrl', '')); break
" 2>/dev/null)"
if [ -n "$BASEURL" ]; then
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$BASEURL/models" 2>/dev/null)"
  echo "  $PROVIDER @ $BASEURL → HTTP ${code:-无响应}"
  case "$code" in
    2*|4*) : ;;  # 4xx 说明端点活着（鉴权问题另说）；只有 5xx/无响应才算服务端故障
    *) echo "!! provider 端点异常（$code）——等服务恢复或换 provider 再跑"; exit 2 ;;
  esac
else
  echo "  （未能解析 baseUrl，跳过——继续跑，由用例轮询兜底）"
fi

# ── 现场备份（prefs + 会被用例改动的夹具） ───────────────────────────────────
mkdir -p "$TMP"
say "备份现场"
adb_ shell am force-stop $PKG
sleep 2
as_cat "$PREFS" > "$TMP/real_prefs_before.xml"
as_cat "$UI_DIR/ListBox.java" > "$TMP/real_ListBox_before.java"
as_cat "$UI_DIR/列表.java" > "$TMP/real_列表_before.java"
echo "  prefs=$(wc -c < "$TMP/real_prefs_before.xml")B"

# ── 启动项目并等待 LSP 就绪 ───────────────────────────────────────────────────
say "冷启动 → 自动打开 MyQuickDevelop → 等 LSP 就绪"
# 注入：autoOpen=true + 把「上次打开的项目」钉到 MyQuickDevelop（跑完整体恢复）。
# 深链全屏助手的工作区取自已打开项目，否则回退 ide_last_project——两个键必须
# 指向同一项目，否则按工作区解析相对路径的工具全部报「文件不存在」。
# 用 Python 改写（键缺失时插入），不用 sed——键不存在时 sed 静默不插入。
set_prefs_for_run() {  # $1 = autoOpen true|false
  local cur; cur="$(as_cat "$PREFS")"
  echo "$cur" | "$PY" -c "
import re, sys
text = sys.stdin.read()
target = '/data/data/$PKG/files/home/ACSProjects/MyQuickDevelop'

def set_bool(text, name, value):
    pat = re.compile(r'(<boolean name=\"%s\" value=\")[a-z]+(\")' % re.escape(name))
    if pat.search(text):
        return pat.sub(r'\g<1>%s\g<2>' % value, text)
    return text.replace('</map>', '    <boolean name=\"%s\" value=\"%s\" />\n</map>' % (name, value))

def set_str(text, name, value):
    pat = re.compile(r'(<string name=\"%s\">)[^<]*(</string>)' % re.escape(name))
    if pat.search(text):
        return pat.sub(lambda m: m.group(1) + value + m.group(2), text)
    return text.replace('</map>', '    <string name=\"%s\">%s</string>\n</map>' % (name, value))

text = set_bool(text, 'idepref_general_autoOpenProjects', '$1')
text = set_str(text, 'ide_last_project', target)
sys.stdout.write(text)
" > "$TMP/real_prefs_edit.xml"
  push_private "$TMP/real_prefs_edit.xml" "$PREFS"
}
adb_ shell am force-stop $PKG
set_prefs_for_run true
adb_ logcat -c
adb_ shell am start -n $PKG/.activities.MainActivity >/dev/null
LSP_OK=0
i=0
while [ $i -lt 36 ]; do
  if adb_ logcat -d 2>/dev/null | grep -q "Server initialized successfully"; then
    LSP_OK=1; echo "  LSP 就绪（约 $((i*5))s）"; break
  fi
  sleep 5; i=$((i+1))
done
[ $LSP_OK = 1 ] || echo "!! 180s 内未见 LSP 就绪（工具断言可能超时）"
sleep 5

# ── 执行用例 ─────────────────────────────────────────────────────────────────
if [ -n "$CUSTOM_PROMPT" ]; then
  [ -n "$CUSTOM_TOOL" ] && [ -n "$CUSTOM_EXPECT" ] || { echo "!! --prompt 需要配合 --tool 和 --expect"; exit 2; }
  run_case "custom" "$CUSTOM_PROMPT" "$CUSTOM_TOOL" "$CUSTOM_EXPECT"
else
  if [ -n "$CASE_LIST" ]; then
    RUN_CASES="$(echo "$CASE_LIST" | tr ',' ' ')"
  else
    RUN_CASES="$ALL_CASES"
  fi
  for c in $RUN_CASES; do
    run_case "$c" "$(case_prompt "$c")" "$c" "$(case_expect "$c")"
    # build_variant 依赖项目同步完成，放最前面跑一轮（其余用例受益于已同步）；
    # 但用例表顺序按用户指定，不重排——首个用例超时重试由轮询兜底。
    sleep 2
  done
fi

# ── 恢复现场 ─────────────────────────────────────────────────────────────────
if [ "$KEEP" = 1 ]; then
  say "--keep：跳过恢复"
else
  say "恢复现场"
  adb_ shell am force-stop $PKG
  sleep 2
  push_private "$TMP/real_prefs_before.xml" "$PREFS"
  # code_actions 用例给 ListBox 加了 import；save_all 可能把 列表.java 落盘——
  # 一律恢复（幂等）。
  push_private "$TMP/real_ListBox_before.java" "$UI_DIR/ListBox.java"
  push_private "$TMP/real_列表_before.java" "$UI_DIR/列表.java"
fi

echo ""
if [ $FAILED = 0 ]; then echo "== 真实调用全部通过 =="; exit 0
else echo "== 存在失败项 =="; exit 1; fi

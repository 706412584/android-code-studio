#!/usr/bin/env bash
# ticode 编译检查（单次全量 javac，约 2 秒，不跑 Gradle）
#
# 用法:
#   tools/ticode-compile.sh [zh|en] [maxerrs]      # 默认 zh 2000
#
# zh: core/ticode/src/zh/java（中文版，包 ticode.zh.*）
# en: core/ticode/src/main/java（英文版，包 ticode.*）
# 输出: 错误总数 + 按文件聚合 + 明细（UTF-8）
# 陷阱: javac 的 @argfile 必须用 Windows 路径；源码 UTF-8（中文标识符）。
#
# 环境变量:
#   ANDROID_JAR  覆盖 android.jar（默认 libs/android.jar）。
#                指定低版本 SDK 的 android.jar 可查出"compileSdk 高、设备版本低"
#                导致的 API 级别不匹配（NoSuchMethodError/NoSuchFieldError）：
#                  ANDROID_JAR=D:/android/platforms/android-28/android.jar \
#                    bash tools/ticode-compile.sh zh
set -u
MODE="${1:-zh}"
MAXERRS="${2:-2000}"
ROOT="D:/android/projecet_iade/android-code-studio/core/ticode"
export JAVA_HOME="C:/Users/70641/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2"
JC="$JAVA_HOME/bin/javac"
TMP="/d/android/tmp"

if [ "$MODE" = "en" ]; then SRC="$ROOT/src/main/java"; else SRC="$ROOT/src/zh/java"; fi

CP="$(cygpath -w "${ANDROID_JAR:-$ROOT/libs/android.jar}")"
for j in "$ROOT"/libs/*.jar "$ROOT"/libs/androidx/*.jar; do CP="$CP;$(cygpath -w "$j")"; done

OUT="$TMP/out-$MODE"; rm -rf "$OUT"; mkdir -p "$OUT"
find "$SRC" -name '*.java' | sed 's|^/d/|D:/|' > "$TMP/srcs-$MODE.txt"

echo "=== 编译 $MODE（$(wc -l < "$TMP/srcs-$MODE.txt") 个 .java）==="
"$JC" -encoding UTF-8 -nowarn -Xmaxerrs "$MAXERRS" \
  -d "$(cygpath -w "$OUT")" -classpath "$CP" @"$TMP/srcs-$MODE.txt" 2> "$TMP/err-$MODE.txt"
echo "exit=$?"
iconv -f GBK -t UTF-8 -c "$TMP/err-$MODE.txt" > "$TMP/err-$MODE-utf8.txt"
echo "--- 错误总数 ---"
grep -c '错误:' "$TMP/err-$MODE-utf8.txt" || echo 0
echo "--- 按文件 ---"
PYTHONIOENCODING=utf-8 python - "$TMP/err-$MODE-utf8.txt" <<'PY'
import io,re,collections,sys
t=io.open(sys.argv[1],encoding='utf-8').read()
c=collections.Counter()
for m in re.finditer(r'^(\S+?):(\d+): 错误: ',t,re.M):
    c[m.group(1)]+=1
for k,v in c.most_common(): print('%3d  %s'%(v,k))
PY
echo "--- 明细(前 80 行) ---"
head -80 "$TMP/err-$MODE-utf8.txt"
echo "(完整明细: $TMP/err-$MODE-utf8.txt)"

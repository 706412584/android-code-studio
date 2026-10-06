#!/usr/bin/env bash
# ticode 中文版冒烟测试：编译产物 + 跑纯 JVM 断言。
#
# 用法: tools/ticode-smoke.sh
#
# 为什么需要它：javac 编译通过 ≠ 运行时正确。真实教训——
#   `(abstract 壳) new 原生类()` 编译能过，但运行时必抛 ClassCastException；
#   `ZIP条目[] = stream.toArray(...)` 编译能过，但运行时抛 ArrayStoreException。
# 这两类只有真跑才抓得到，本脚本就是那个"真跑"。
set -u
ROOT="D:/android/projecet_iade/android-code-studio/core/ticode"
export JAVA_HOME="C:/Users/70641/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2"
JC="$JAVA_HOME/bin/javac"; JAVA="$JAVA_HOME/bin/java"
TMP=/d/android/tmp
OUT="$TMP/out-zh"          # 由 tools/ticode-compile.sh zh 产出
SMOKE_OUT="$TMP/ticode-smoke-out"

if [ ! -d "$OUT" ]; then
  echo "!! 缺少 $OUT —— 先跑 tools/ticode-compile.sh zh"
  exit 2
fi

CP="$(cygpath -w "$ROOT/libs/android.jar")"
for j in "$ROOT"/libs/*.jar "$ROOT"/libs/androidx/*.jar; do CP="$CP;$(cygpath -w "$j")"; done
CP="$CP;$(cygpath -w "$OUT")"

rm -rf "$SMOKE_OUT"; mkdir -p "$SMOKE_OUT"
echo "=== 编译冒烟测试 ==="
"$JC" -encoding UTF-8 -nowarn -d "$(cygpath -w "$SMOKE_OUT")" -classpath "$CP" \
  "$(cygpath -w "$ROOT/smoke/SmokeTest.java")" "$(cygpath -w "$ROOT/smoke/SmokeTest2.java")" || exit 1

FAIL=0
for T in SmokeTest SmokeTest2; do
  echo "=== 运行 $T ==="
  "$JAVA" -Dfile.encoding=UTF-8 -classpath "$(cygpath -w "$SMOKE_OUT");$CP" "$T" 2>&1 \
    | iconv -f UTF-8 -t UTF-8 -c
  [ "${PIPESTATUS[0]}" -ne 0 ] && FAIL=1
done

echo
if [ "$FAIL" -eq 0 ]; then echo ">>> 冒烟测试全部通过"; else echo ">>> 冒烟测试有失败"; fi
exit $FAIL

#!/usr/bin/env bash
# 用本机 Gradle 构建「模板生成的 Quick Develop 工程」，验证产物真能编译成 APK。
#
# 用法:
#   tools/build-qd-project.sh            # 先跑生成测试，再构建
#   tools/build-qd-project.sh --no-gen   # 跳过生成，直接构建已有产物
#
# 前置:
#   - core/app/build/qd-project/ 由 QuickDevelopProjectGenTest 生成
#   - 本机 Android SDK（local.properties 的 sdk.dir）+ JDK 17
#   - 首次需联网拉 AGP / androidx（之后走缓存）
#
# 意义：把「模板产物是否正确」与「真机能否构建」解耦——本机构建过了，
# 真机失败就只可能是设备网络/环境，而非产物本身。
set -uo pipefail

REPO="D:/android/projecet_iade/android-code-studio"
PROJ="$REPO/core/app/build/qd-project"
export JAVA_HOME="C:/Users/70641/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2"
GRADLE="$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/bin/gradle 2>/dev/null | head -1)"
GRADLE="${GRADLE:-/d/android/gradle-9.1.0/bin/gradle}"  # 本机缓存里的 8.13（模板 wrapper 写的 9.0.0 未缓存）

if [ "${1:-}" != "--no-gen" ]; then
  echo "=== 生成工程（跑 QuickDevelopProjectGenTest）==="
  (cd "$REPO" && JAVA_HOME="$JAVA_HOME" ./gradlew :core:app:testDebugUnitTest \
      --tests '*QuickDevelopProjectGenTest*' -q 2>&1 | iconv -f GBK -t UTF-8 -c | tail -3)
fi

if [ ! -d "$PROJ" ]; then
  echo "!! 工程未生成: $PROJ"; exit 2
fi

# 用本机 SDK；wrapper 版本不匹配，直接用本机 gradle
echo "sdk.dir=D\\:\\\\android" > "$PROJ/local.properties"

cd "$PROJ"
echo "=== 构建 :app:assembleDebug（本机 gradle 8.13）==="
"$GRADLE" :app:assembleDebug --no-daemon --console=plain 2>&1 | iconv -f GBK -t UTF-8 -c | tail -40

APK=$(find "$PROJ/app/build/outputs/apk" -name '*.apk' 2>/dev/null | head -1)
if [ -n "$APK" ]; then
  echo
  echo ">>> 构建成功: $APK"
  ls -la "$APK"
else
  echo
  echo ">>> 未产出 APK —— 构建失败"
  exit 1
fi

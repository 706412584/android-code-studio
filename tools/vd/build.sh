#!/bin/sh
# acs-vd 构建脚本：VdMain.java -> acs-vd.jar（app_process 可直跑），并复制到 assets。
#
# 移植自 Aharou 的 tools/aharou-vd/build.sh，SDK 路径改成 ACS 本机布局。
# 用法：在 Git Bash / Linux 下执行；需要 JDK 21 与 Android SDK（platforms/android-34、build-tools/35.0.0）。
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-D:/android}"
JAVA_HOME="${JAVA_HOME:-C:/Users/70641/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2}"
AJAR="$SDK/platforms/android-34/android.jar"
D8="$SDK/build-tools/35.0.0/d8.bat"
OUT="$DIR/build"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

"$JAVA_HOME/bin/javac" -source 8 -target 8 -bootclasspath "$AJAR" -d "$OUT/classes" "$DIR/VdMain.java"
"$D8" --release --min-api 26 --lib "$AJAR" --output "$OUT/dex" "$OUT"/classes/com/tom/rv2ide/vd/*.class
"$JAVA_HOME/bin/jar" --create --file "$DIR/acs-vd.jar" -C "$OUT/dex" classes.dex

# 复制到 app 资产（打包进 APK，运行时由 VdController 导出到外部目录再部署）。
ASSETS="$DIR/../../core/app/src/main/assets/vd"
mkdir -p "$ASSETS"
cp "$DIR/acs-vd.jar" "$ASSETS/acs-vd.jar"

echo "OK: $DIR/acs-vd.jar ($(wc -c < "$DIR/acs-vd.jar") bytes) -> $ASSETS"

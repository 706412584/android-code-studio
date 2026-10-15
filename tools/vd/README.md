# acs-vd — 无头虚拟屏小工具（不占宿主屏幕跑 App）

移植自 [Aharou](https://github.com/520huxiangli/Aharou) 的 `tools/aharou-vd`（GPL-3.0），
其机制参考并改编自 **Genymobile/scrcpy** 的 `--new-display`（Apache-2.0）。

本目录：`VdMain.java`（源码）+ `build.sh`（本机构建）+ `acs-vd.jar`（产物，约 4KB）。

## 已验证的完整链路（Aharou 真机验证，2026-09-26）

```sh
# 1) 部署（App 侧自动做；手动调试用）：
cp acs-vd.jar /data/local/tmp/
cd /data/local/tmp
CLASSPATH=/data/local/tmp/acs-vd.jar setsid app_process / com.tom.rv2ide.vd.VdMain 1080 1920 440 > vd.log 2>&1 < /dev/null &
cat vd.log        # => VD_READY id=N
# 2) 查 SF 长 id（screencap 用）：
dumpsys SurfaceFlinger --display-id | grep acs-vd
# 3) 开 App（短 id）：
am start --display N -n pkg/act
# 4) 触控（短 id）：
input -d N tap X Y
# 5) 截图：Android 14+ 可 `screencap -d <SF长id>`；Android 13 得用 runner 自带出图（见下）
# 6) 停止：
touch /data/local/tmp/acs-vd.stop
```

## 自带截图（Android 13 起必需）

`screencap -d <SF长id>` 只在 Android 14+ 对虚拟屏有效；**Android 13 上它只认物理屏 token**，
会把目标文件写成 0 字节且不报错。因此 runner 支持自己出图：启动时多传「截图输出路径
截图请求文件」两个参数，App 侧 touch 请求文件即触发把最近一帧编成 PNG。

```sh
CLASSPATH=/data/local/tmp/acs-vd.jar setsid app_process / com.tom.rv2ide.vd.VdMain 1080 1920 440 \
  /sdcard/out.png /data/local/tmp/acs-vd.shot > vd.log 2>&1 < /dev/null &
touch /data/local/tmp/acs-vd.shot   # 触发一次出图 → /sdcard/out.png，日志打 VD_SHOT
```

- 输出路径要落在 **App 可读**的位置（二进制不能经 Shizuku 的文本通道回来）；写文件先落 `.tmp` 再改名，避免读到半截。
- 屏上还没有内容时打印 `VD_SHOT_FAILED no-frame`，不写文件。
- 帧拷贝节流 100ms（画面在动时不必每帧都拷）。

## 与 Aharou 原版的差异（仅命名空间隔离）

| 项 | Aharou | ACS |
|---|---|---|
| 包名 | `com.aharou.vd` | `com.tom.rv2ide.vd` |
| 显示屏名 | `aharou-vd` | `acs-vd` |
| 停止文件 | `/data/local/tmp/aharou-vd.stop` | `/data/local/tmp/acs-vd.stop` |
| runner 路径 | `/data/local/tmp/aharou-vd.jar` | `/data/local/tmp/acs-vd.jar` |

逻辑与 flags 逐字保留。

## 重建

```sh
sh tools/vd/build.sh   # 编译 → acs-vd.jar → 复制到 core/app/src/main/assets/vd/
```

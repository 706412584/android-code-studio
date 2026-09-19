---
name: acs-env
description: 本机（AndroidCodeStudio）的运行环境事实——shell 执行的环境变量缺失、项目位置、Termux 前缀。执行 shell 命令前先看这条。
---

# 本机环境事实

这些是**实测结论**，不是推测。它们会直接决定你的命令能不能跑起来。

## `shell_execute` 没有 Termux 的环境变量

`shell_execute` 通过 `/system/bin/sh -c` 执行命令，**不设置任何环境变量**。实测：

```
PATH=/sbin:/system/sbin:/product/bin:/apex/com.android.runtime/bin:/system/bin:...
PREFIX=            ← 空
command -v node    → 找不到
```

因此：

- 不能假设 `$PREFIX`、`$HOME`、`$LD_LIBRARY_PATH` 已存在
- 要用 Termux 里的程序，必须写**绝对路径**或自己先 export
- Termux 前缀固定为 `/data/data/com.tom.rv2ide/files/usr`

想用 Termux 环境时，命令开头加上：

```sh
export PREFIX=/data/data/com.tom.rv2ide/files/usr
export PATH="$PREFIX/bin:$PATH"
export LD_LIBRARY_PATH="$PREFIX/lib"
```

## 项目位置

用户项目在 `/data/data/com.tom.rv2ide/files/home/ACSProjects/<项目名>/`。
这是 app 私有目录，不是 `/sdcard`。

## 可用的 shell 后端

按优先级回退：Termux（总是可用，但无 adb 权限）→ Shizuku（有 adb 权限，需设备端安装并授权）。
工具结果里会告诉你**实际用了哪个**后端以及是否发生了回退——注意看，回退意味着某些命令会失败。

## 验证改动是否生效

`file_write` / `file_edit` 成功**不代表**代码能编译。改了 Kotlin/Java/C++ 之后要跑构建，
改了 UI 之后要装机看。构建失败时读完整错误，不要凭猜测改代码。

## 长命令要加超时意识

构建一个 Android 项目可能几分钟。`gradle_build` 是专用工具（有构建服务、增量、日志），
优先用它而不是自己 `shell_execute ./gradlew`——自己跑会丢掉增量缓存与错误解析。

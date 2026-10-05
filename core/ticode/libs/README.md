# core/ticode/libs —— 编译期依赖 jar（不入库）

本模块是纯 `java-library`，不解析任何 POM（否则会被 appcompat/material 的传递依赖
拉到本机缓存里没有的 `kotlin-stdlib-jdk8:1.6.21`，`--offline` 直接失败）。
因此这三类依赖以**本地 jar + compileOnly** 提供，重建步骤如下。

## android.jar

框架面。与仓库里既有的 `utilities/framework-stubs/libs/android.jar` **逐字节相同**：

```bash
cp utilities/framework-stubs/libs/android.jar core/ticode/libs/android.jar
```

## recyclerview / constraintlayout / flexbox

从本机 Gradle 缓存的 AAR 里取 `classes.jar`。版本与 `gradle/libs.versions.toml`
中声明的一致（recyclerview 1.3.2 / constraintlayout 2.1.4 / flexbox 3.0.0）：

```bash
CACHE=~/.gradle/caches/modules-2/files-2.1
unzip -p "$CACHE/androidx.recyclerview/recyclerview/1.3.2/"*/recyclerview-1.3.2.aar \
  classes.jar > core/ticode/libs/recyclerview.jar
unzip -p "$CACHE/androidx.constraintlayout/constraintlayout/2.1.4/"*/constraintlayout-2.1.4.aar \
  classes.jar > core/ticode/libs/constraintlayout.jar
unzip -p "$CACHE/com.google.android.flexbox/flexbox/3.0.0/"*/flexbox-3.0.0.aar \
  classes.jar > core/ticode/libs/flexbox.jar
```

## 为什么需要这三个

生成的代码只引用三类东西：`android.*`（含 `android.R` 常量）、
`androidx.recyclerview` / `androidx.constraintlayout`、`com.google.android.flexbox`。
全部只出现在 `ticode.meng`（分割线/布局管理器家族）与个别 `ticode.android` 控件里。

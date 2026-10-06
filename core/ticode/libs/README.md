# core/ticode/libs —— 编译期依赖 jar（不入库）

本模块是纯 `java-library`，不解析任何 POM（否则会被 appcompat/material 的传递依赖
拉到本机缓存里没有的 `kotlin-stdlib-jdk8:1.6.21`，`--offline` 直接失败）。
因此这三类依赖以**本地 jar + compileOnly** 提供，重建步骤如下。

## android.jar

框架面。与仓库里既有的 `utilities/framework-stubs/libs/android.jar` **逐字节相同**：

```bash
cp utilities/framework-stubs/libs/android.jar core/ticode/libs/android.jar
```

## recyclerview / constraintlayout / flexbox / appcompat

从本机 Gradle 缓存的 AAR 里取 `classes.jar`。版本与 `gradle/libs.versions.toml`
中声明的一致（recyclerview 1.3.2 / constraintlayout 2.1.4 / flexbox 3.0.0 /
appcompat 1.3.1）：

```bash
CACHE=~/.gradle/caches/modules-2/files-2.1
unzip -p "$CACHE/androidx.recyclerview/recyclerview/1.3.2/"*/recyclerview-1.3.2.aar \
  classes.jar > core/ticode/libs/recyclerview.jar
unzip -p "$CACHE/androidx.constraintlayout/constraintlayout/2.1.4/"*/constraintlayout-2.1.4.aar \
  classes.jar > core/ticode/libs/constraintlayout.jar
unzip -p "$CACHE/com.google.android.flexbox/flexbox/3.0.0/"*/flexbox-3.0.0.aar \
  classes.jar > core/ticode/libs/flexbox.jar
unzip -p "$CACHE/androidx.appcompat/appcompat/1.3.1/"*/appcompat-1.3.1.aar \
  classes.jar > core/ticode/libs/appcompat.jar
```

## androidx/*.jar（整套）

结绳源码里用 `@外部依赖库("../../依赖库/androidx/xxx.aar")` 声明的依赖。
**注意：声明里既有 `.aar` 也有纯 `.jar`** —— 首次只提取了 43 个 aar，
漏掉 5 个纯 jar（`lifecycle-common`/`collection`/`core-common`/`annotation`/
`constraintlayout-core`），其中 `lifecycle-common` 含 `LifecycleOwner`，
缺它会让 `安卓X窗口` 报「无法访问 LifecycleOwner」。
现共 **48 个 jar**。**继承链需要它们**：

```
安卓X窗口 extends androidx.appcompat.app.AppCompatActivity
AppCompatActivity extends androidx.fragment.app.FragmentActivity   ← 只在 fragment aar 里
FragmentActivity extends androidx.activity.ComponentActivity        ← 只在 activity aar 里
ComponentActivity extends androidx.core.app.ComponentActivity       ← 只在 core aar 里
```

少了 `fragment` 这一层，`X窗口` 的 `onCreate/onPause/onResume/onRestart/
onCreateOptionsMenu/onOptionsItemSelected` 全部「找不到符号」，连带 8 处
`@Override` 报「方法不会覆盖或实现超类型的方法」（实测共 22 处，占当时总错误 6%）。

```bash
# 从结绳源码附带的依赖库目录批量提取（43 个 aar + 5 个纯 jar）
mkdir -p core/ticode/libs/androidx
python - <<'PY'
import os, shutil, zipfile
src = r'D:/android/tmp/jbb/依赖库'          # 结绳源码同级目录
out = r'core/ticode/libs/androidx'
for d, _, fs in os.walk(src):
    for f in fs:
        p = os.path.join(d, f)
        if f.endswith('.aar'):
            z = zipfile.ZipFile(p)
            if 'classes.jar' in z.namelist():
                open(os.path.join(out, f[:-4] + '.jar'), 'wb').write(z.read('classes.jar'))
        elif f.endswith('.jar'):            # 纯 jar 直接复制（别漏！）
            shutil.copy(p, os.path.join(out, f))
PY
```

> 注：`libs/recyclerview.jar` 是 1.3.2，而结绳 `@外部依赖库` 声明的是 1.2.1；
> 两者同时在类路径上（前者在前），`libs/androidx/recyclerview-1.2.1.jar` 基本不会被解析。
> 保留它是为了不擅自降级——需要时再统一版本。

## 为什么需要这些

生成的代码只引用三类东西：`android.*`（含 `android.R` 常量）、
`androidx.recyclerview` / `androidx.constraintlayout`、`com.google.android.flexbox`、
`androidx.appcompat` 及上述整套 androidx。分布：
- `ticode.meng`（分割线/布局管理器家族）与个别 `ticode.android` 控件 → recyclerview/flexbox/constraintlayout
- `ticode.android.工具栏`（`androidx.appcompat.widget.Toolbar`）、
  `安卓X窗口`（`androidx.appcompat.app.AppCompatActivity`）→ appcompat


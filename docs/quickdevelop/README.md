# Quick Develop 模板 — 使用入口

> 本文是 **Quick Develop 模板的使用文档**：它是什么、生成什么、怎么上手。
>
> 如果你想看的是「结绳库移植到什么程度、覆盖度多少」这类工程内部记录，
> 那是另一份文档 —— [`docs/ticode-port/PROGRESS.md`](../ticode-port/PROGRESS.md)。
> 两者受众不同：本文给**使用者**，那份给**维护移植的人**。

## 目录

- [Quick Develop 是什么](#quick-develop-是什么)
- [怎么用](#怎么用)
- [生成的工程长什么样](#生成的工程长什么样)
- [三套中文 API 怎么选](#三套中文-api-怎么选)
- [界面：ui/ 组件](#界面ui-组件)
- [工具：tool/ 工具库](#工具tool-工具库)
- [ticode 是什么、怎么用](#ticode-是什么怎么用)
- [构建](#构建)
- [深挖](#深挖)

---

## Quick Develop 是什么

在 ACS 里**新建工程时可选的一个模板**（`QuickDevelop.kt:82`，`displayName = "Quick Develop"`，
注册于 `TemplateRegistry.kt:39`）。

它生成的工程和普通 Android 工程同构 —— 标准 AndroidX（appcompat + material +
constraintlayout）、纯 Gradle 依赖、零 NDK、零 C++、单 Activity
（`QuickDevelop.kt:45-80` 的类注释）。**唯一区别在源码**：

> 模板额外生成三套**中文命名**的 API，`MainActivity` 直接用它们以代码搭界面，
> **不写 XML 布局**。

具体差异（类注释 `QuickDevelop.kt:64-71`，数量见 [三套中文 API 怎么选](#三套中文-api-怎么选) 的核对）：

| 项 | 普通模板（如 EmptyActivity） | Quick Develop |
|---|---|---|
| `res/layout/activity_main.xml` | 有 | **不生成**（`hasLayout = false`，`QuickDevelop.kt:163`） |
| 语言 | Kotlin / Java 可选 | **恒为 Java** |
| 额外源码 | — | 45 个中文 UI 组件 + 5 个工具类 + 独立 `:ticode` 模块 |

**为什么恒为 Java**：中文类名在 Java/Kotlin 都合法，但向导的语言下拉默认是 Kotlin。
若用户选了 Kotlin 而模板只写 Java 源码，就会出现「`build.gradle` 里没有 kotlin 插件、
源码却是 `.kt`」的不一致。所以向导对本模板把语言锁死在 Java
（`AtcWizardDialog.kt:316-319` 锁 UI，`AtcWizardDialog.kt:386-388` 强制 `LanguageType.JAVA`）。

**为什么不用写 XML**：界面是 Java 代码里的对象树。根视图由一个抽象方法 `搭建()` 返回，
基类 `页面` 在 `onCreate` 里把它设为 contentView（`QuickDevelopSources.kt:615-635`，
抽象方法在 `:626`）：

```java
public abstract class 页面 extends AppCompatActivity {
    protected abstract android.view.View 搭建();   // 子类实现

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(搭建());
    }
}
```

---

## 怎么用

1. ACS 主界面 → **新建工程**（Create Project）
2. 在模板列表里选 **Quick Develop**
3. 填工程名与包名。**语言下拉是灰的（锁死 Java）**，这是正常的
4. 选保存位置 → 创建

创建过程会：生成工程骨架 → 拷 gradle wrapper → 写版本目录 → 写 `:ticode` 模块 →
写 `ui/` 组件（45 个中文 + 38 个英文别名）→ 写 5 个 `tool/` 工具类 →
写 `MainActivity` → 拷图标与主题资源（`QuickDevelop.kt:130-497`）。

生成的工程根目录还会带一份 `README.md`，是模板自己写的（`QuickDevelop.kt:605-683`），
内容和本文互补 —— 它是**工程内**的速查，本文是**仓库内**的入口说明。

---

## 生成的工程长什么样

以下是模板**真实写出**的结构。权威来源是 `QuickDevelop.kt:605-683` 的 `projectReadme()`
函数 —— 它写的就是这份 README；`$uiCount` / `$toolCount` / `$ticodeCount` 三个数量
从真实常量取，不硬编码（`QuickDevelop.kt:606-609`）。

```
<项目名>/
├── settings.gradle.kts            include(":app") + include(":ticode")
├── build.gradle.kts               顶层：两个插件 alias，apply false
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml         版本目录（12 个版本 / 11 个库 / 2 个插件）
│   └── wrapper/                   gradlew / gradlew.bat / wrapper jar+properties
├── app/
│   ├── build.gradle.kts           主模块（Java 17，关闭 kotlin options）
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/<包路径>/
│       │   ├── MainActivity.java           入口 Activity（extends 页面）
│       │   ├── ui/                         45 个中文 UI 组件 + 38 个英文别名
│       │   │   └── README.md               组件 API 文档（从规格表生成）
│       │   └── tool/                       5 个工具类
│       │       └── README.md               工具 API 文档
│       └── res/                            图标 / 主题 / 颜色 / strings.xml
└── ticode/                        结绳语言基本库（Java 移植），独立 Android 库模块
    ├── build.gradle.kts           namespace = "ticode.zh"
    ├── README.md
    └── src/main/java/ticode/zh/   333 个中文 API 类
        ├── base/      23 个（文本 / 整数 / 字符…）
        ├── jvm/       91 个（集合 / 流 / ZIP / 反射 / 正则…）
        ├── android/  200 个（控件 / 网络 / 加解密 / 文件…）
        └── meng/      19 个（RecyclerView 系列 / 弹性布局）
```

几点说明：

- **`ui/` 的文件数是 45 + 38 = 83 个 `.java`**，不是 45 个。38 个扩展控件各配一个
  英文别名类（别名继承中文类，行为完全一致，见 `QuickDevelopSources.kt:1638-1652`）。
  7 个基础控件（`视图`/`线性布局`/`约束布局`/`文本`/`按钮`/`输入框`/`页面`）**没有别名**。
- **`ticode/` 是独立 Gradle 模块**，不是 `app` 里的一个包。源码从 APK 的 assets
  递归拷入（`QuickDevelop.kt:519-544`），随工程走，**可以直接改**。
- **`:ticode` 用 `com.android.library` 而不是 `java-library`**：它引用 `android.*` 与
  androidx（appcompat / constraintlayout / recyclerview / flexbox），这些是 AAR，
  纯 `java-library` 解析不了 AAR 里的 classes.jar（`QuickDevelop.kt:499-517`）。
- **没有 `res/layout/` 目录**，也没有 `activity_main.xml`。

> **Gradle DSL 可选**：上表按 Kotlin DSL（`.kts`）列出。向导里「使用 Kotlin DSL」
> 开关关掉时，会生成 `build.gradle` / `settings.gradle`（Groovy）—— 注意这跟**源码语言**
> 是两件事，后者恒定 Java。
>
> 想直接看一份生成好的产物：跑
> `./gradlew :core:app:testDebugUnitTest --tests '*QuickDevelopProjectGenTest*'`，
> 产物落在 `core/app/build/qd-project/`。
> ⚠️ 该目录的 `MainActivity.java` 会被覆盖度自检 harness 覆写，**不是模板的真实产物**；
> 真实 `MainActivity` 看 `QuickDevelopSources.kt:642` 的 `mainActivityJava()`。

---

## 三套中文 API 怎么选

**这是最容易懵的地方**：工程里同时有三套中文 API，第一次打开不知道用哪个。
权威说法是 `QuickDevelopTicode.kt:186-213` 的 `选型指引()` 函数 —— 它会被模板写进
生成工程的根 `README.md`。原文如下：

| 要做什么 | 用哪个 | 例子 |
|---|---|---|
| **写界面**（推荐） | `ui/` | `new 按钮(this).文字("确定")` |
| **常见工具**（推荐） | `tool/` | `字符.转换大写("abc")` |
| **加解密 / ZIP / 反射 / 集合族 / 大数** | `ticode` | `加解密操作.MD5加密(s, "UTF-8")` |
| **跑结绳老代码**（兼容） | `ticode` | `ticode.zh.android.文本框` |

**为什么界面推荐 `ui/` 而不是 ticode 的控件**：两者实测都能用
（`ui/` 45/45、ticode 21/21），但设计不同 —— `ui/` 基于 androidx、链式 API、
控件更全（**45 vs 21**），是为本模板写的现代封装；ticode 的控件是结绳移植的原始包装
（`getView()` 模式），价值在兼容结绳老代码。

> 数量核对：`45` 来自 `QuickDevelopSources.CHINESE_COMPONENT_COUNT`
> （`QuickDevelopSources.kt:762-763`，= `BASE_CN_NAMES.size + EXTRA_WIDGETS.size`
> = 7 + 38）。`5` 来自 `QuickDevelopToolkits.all()`（`QuickDevelopToolkits.kt:1451`）。
> `333` 来自 `QuickDevelopTicode.EXPECTED_SOURCE_COUNT`（`QuickDevelopTicode.kt:64`）。

**ticode 不可替代的是它的工具类**（`ticode.zh.jvm` + `ticode.zh.base`，114 个类：
ZIP/GZIP、反射、集合族、大整数、UUID…），这些 `tool/` 没有。

**不想要 ticode？** 删掉 `app/build.gradle` 里的 `implementation(project(":ticode"))`
与 `settings.gradle` 里的 `include(":ticode")` 即可。

---

## 界面：ui/

包名 `<你的包名>.ui`，**45 个中文组件**（`QuickDevelopSources.kt:765-780`）。
所有组件都提供五个通用样式方法，返回自身以便链式继续
（`QuickDevelopSources.kt:1529-1536`）：

| 方法 | 参数 | 说明 |
|---|---|---|
| `背景(int color)` | ARGB 颜色 | 纯色背景 |
| `圆角(float dp)` | 半径 dp | 圆角（`卡片` 走 CardView 自身的 radius） |
| `内边距(float dp)` | 四边一致 | 内边距 |
| `外边距(float dp)` | 四边一致 | 外边距；父容器不支持时静默忽略 |
| `权重(float w)` | 线性权重 | 仅父容器是 `线性布局` 时生效 |

静态工具：`视图.dp(float)` 把 dp 转成像素。

### 基础控件（7 个，手写类，只有中文名）

| 中文名 | 基于 | 特有方法 |
|---|---|---|
| `视图` | `FrameLayout` | `dp(float)` 静态 |
| `线性布局` | `LinearLayout` | `方向(int)` / `添加(View...)` / `对齐(int)` |
| `约束布局` | `ConstraintLayout` | `居中(View)` / `铺满(View)` |
| `文本` | `TextView` | `文字(CharSequence)` / `字号(float)` / `颜色(int)` / `粗体(boolean)` |
| `按钮` | `MaterialButton` | `文字(CharSequence)` / `点击(Runnable)` / `铺满宽度()` |
| `输入框` | `TextInputEditText` | `提示(CharSequence)` / `单行(boolean)` / `取值()` |
| `页面` | `AppCompatActivity` | `protected 视图 搭建()` 抽象 |

来源：`QuickDevelopSources.kt:1619-1630`（`BASE_CN_SPECS`）。

### 扩展控件（38 个，规格表生成，**都有英文别名**）

13 个容器 + 25 个叶子控件（`QuickDevelopSources.kt:934-1496`）。完整清单与特有方法
见生成工程里的 `ui/README.md` —— 那份文档**从规格表产出**，不会过期
（`QuickDevelopSources.kt:1506-1583`）。这里只列几类：

- 容器：`相对布局` `帧布局` `表格布局` `滚动` `嵌套滚动` `卡片` `协调布局`
  `应用栏布局` `滑动窗体` `垂直滑动窗体` `单选布局` …
- 数据展示：`列表` `v7列表` `网格视图` `浏览器` `图像` `视频` `动态图` …
- 输入与选择：`开关` `单选项` `多选` `拖动条` `评分` `进度条`
  `日期选择器` `时间选择器` `浮动动作按钮` …

### 命名规则

扩展控件有**中文名**与**英文别名**两个类，行为完全一致（别名继承中文类，
因此不会随时间分叉）。基础控件只有中文名（`QuickDevelopSources.kt:1570-1582`）：

```java
文本 t1 = new 文本(this);        // 基础控件：只有中文名
文本 t2 = new Text(this);        // ❌ 编译失败：基础控件没有别名
卡片 c1 = new 卡片(this);        // 扩展控件：中文名
CardBox c2 = new CardBox(this);  // ✅ 扩展控件的英文别名
```

包名保持 ASCII（`<包名>.ui`），只有类名与方法是中文。

### 界面示例（`QuickDevelopSources.kt:642-744` 的真实 MainActivity 节选）

```java
@Override
protected 视图 搭建() {
    输入 = new 输入框(this).提示("请输入内容").单行(true);

    结果 = new 文本(this).文字("等待操作…").字号(16f).颜色(Color.parseColor("#616161"));

    按钮 提交 = new 按钮(this).文字("显示输入").铺满宽度();
    提交.点击(() -> 结果.文字("你输入了：" + 输入.取值()));

    线性布局 内容 =
            new 线性布局(this)
                    .方向(线性布局.垂直)
                    .背景(Color.parseColor("#FFFFFF"))
                    .圆角(12f)
                    .内边距(20f)
                    .对齐(Gravity.CENTER_VERTICAL)
                    .添加(
                            new 文本(this).文字("Quick Develop").字号(24f).粗体(true),
                            输入,
                            提交,
                            结果);

    线性布局 根 =
            new 线性布局(this)
                    .方向(线性布局.垂直)
                    .背景(Color.parseColor("#F5F5F5"))
                    .内边距(16f)
                    .添加(内容);

    return 根;
}
```

> 注意：模板真实产物里 `点击` 传的是匿名 `Runnable`（Java 7 风格），
> 上面为了可读性写成 lambda。

---

## 工具：tool/

包名 `<你的包名>.tool`，**5 个类，全部是静态方法**，无需实例化
（`QuickDevelopToolkits.kt:1451-1458`）。

| 类 | 职责 | 方法数 | 代表方法 |
|---|---|---|---|
| `字符` | 字符串 / 正则 / JSON | 21 | `转换大写(s)` `分割(s, regex)` `匹配组(s, re, i)` `json解析(s)` |
| `文件` | 路径 / 读写 / 压缩 | 18 | `读取文本(p)` `写入文本(p, t)` `复制(a, b)` `压缩(src, zip)` `解压(zip, dir)` |
| `数据` | SQLite / 类型转换 | 21 | `创建数据库(ctx, name)` `查询数据(db, sql)` `转整型(o)` `转颜色(o)` |
| `工具` | 动画 / 媒体 / 通知 | 21 | `淡入(view, ms)` `媒体播放(ctx, path)` `提示(ctx, text)` `发通知(ctx, t, c)` |
| `系统` | 设备信息 / 应用 / 截屏 | 16 | `品牌()` `型号()` `应用列表(ctx)` `震动(ctx, ms)` `截屏(activity)` |

设计原则：**所有方法对 `null` 入参安全**，不抛给调用方
（`QuickDevelopToolkits.kt:65` 注释）。完整方法表见生成工程里的 `tool/README.md`
（`QuickDevelopToolkits.kt:1466-1559`）。

### 权限

模板已在生成的 `AndroidManifest.xml` 里声明两个权限（`QuickDevelopSources.kt:235-266`）：

| 方法 | 权限 | 缺权限时 |
|---|---|---|
| `系统.震动` | `android.permission.VIBRATE` | 静默返回，不崩溃 |
| `工具.发通知` | `android.permission.POST_NOTIFICATIONS` | 通知不显示（Android 13+ 还需**运行时**申请） |
| `系统.截屏` / `截屏保存` | 无 | — （只能截本应用） |
| `文件.打开` | 无 | **API 24+ 直接返回 `false`**，见下 |

> `文件.打开` 在 API 24+ 不做尝试：`Uri.fromFile` 会把 `file://` 交给别的应用，
> 系统视为泄露文件路径并抛 `FileUriExposedException`。要用它得自己配
> `FileProvider`（manifest + `res/xml/file_paths.xml` + `getUriForFile`）
> —— `QuickDevelopToolkits.kt:605-639`（javadoc 在 `:608`，方法在 `:621`）。

---

## ticode 是什么、怎么用

**ticode 是「结绳」语言基本库到 Java 的移植**，333 个**中文命名**的 API 类，
包名 `ticode.zh.*`，以独立 Gradle 模块 `:ticode` 随工程生成
（`QuickDevelopTicode.kt:20-42`）。

四个子包（`QuickDevelopTicode.kt:155-160`）：

| 子包 | 内容 | 类数 |
|---|---|---|
| `ticode.zh.base` | 基础类型（文本 / 整数 / 字符…） | 23 |
| `ticode.zh.jvm` | 纯 JVM（集合 / 流 / ZIP / 反射 / 正则…） | 91 |
| `ticode.zh.android` | Android（控件 / 网络 / 加解密 / 文件…） | 200 |
| `ticode.zh.meng` | RecyclerView 系列（适配器 / 布局管理器 / 弹性布局） | 19 |

> 类数按 `core/app/src/main/assets/QuickDevelop/ticode/` 实际文件统计，
> 合计 333，与 `QuickDevelopTicode.EXPECTED_SOURCE_COUNT`（`QuickDevelopTicode.kt:64`）一致。

`app` 模块已依赖 `:ticode`（`QuickDevelop.kt:413`），所以**直接 import 就能用**。

### 可运行示例

**① 加解密 + GZIP** —— 这段就是模板生成的 `MainActivity` 里的真实代码
（`QuickDevelopSources.kt:697-710`，方法签名在 `加解密操作.java:20` 与 `GZIP操作.java:8,21`）：

```java
import ticode.zh.android.加解密操作;
import ticode.zh.jvm.GZIP操作;

String 摘要 = 加解密操作.MD5加密(value, "UTF-8");

byte[] gz = GZIP操作.压缩字节集(value.getBytes("UTF-8"));
byte[] back = GZIP操作.解压字节集(gz);
String 解压 = new String(back, "UTF-8");   // 与 value 相等
```

`加解密操作` 还提供 `SHA加密` / `Base64编码` / `AES加密` / `DES加密` / `RSA加密` / `RC4加密`
及对应解密（`加解密操作.java:48-320`）。

**② 大整数**（`大整数.java:6-54`）：

```java
import ticode.zh.jvm.大整数;

大整数 a = new 大整数("123456789012345678901234567890", 10);
大整数 b = a.加(new 大整数("1", 10));
String s = b.到文本(10);   // "123456789012345678901234567891"
```

**③ 正则**（`正则表达式.java:17-25`）：

```java
import ticode.zh.jvm.正则表达式;

String[] 命中 = 正则表达式.正则匹配("a1b22c333", "\\d+", 0);
// 命中 = {"1", "22", "333"}
```

**④ UUID**（`UUID.java:5-19`）：

```java
import ticode.zh.jvm.UUID;

java.util.UUID id = UUID.取随机标识符();
```

### 关于 ticode 的控件

`ticode.zh.android` / `ticode.zh.meng` 里也有控件（`文本框` / `按钮` / `画板` / `线性布局` /
`相对布局` / `弹性布局` …），**共 21 个实测可用**。它们不是 View 的子类，而是**包装** ——
对象本身不是 View，要用 `getView()` 拿到原生 View。

**写新界面请用 `ui/`**，理由见上文「三套中文 API 怎么选」。两套并存不冲突，
只是定位不同（`ui/` 是继承式现代封装，ticode 是兼容结绳老代码的原始包装）。
实测数据与完整控件清单见 [`tools/qd-selfcheck/README.md`](../../tools/qd-selfcheck/README.md)。

### 几个已知的移植坑

- **`_op` 后缀方法是运算符重载的占位**：`文本.java:10-35` 的 `加_op` / `等于_op` /
  `取索引_op` 等是翻译器为结绳运算符生成的，**很多返回 `null` / `0` / `false`，
  并未真正实现**。写新代码用带真实实现的静态方法（如 `文本.拼接文本数组`，
  `文本.java:140-146`）。
- **部分类已按需修复**：例如 `大整数.加()` 不用 `(大整数) this.add(...)` 强转
  （会 `ClassCastException`），而是用文本重建壳对象 —— 见 `大整数.java:27-34` 的注释。
  修复背景见 [`docs/ticode-port/PROGRESS.md`](../ticode-port/PROGRESS.md)。

---

## 构建

在 ACS 里打开工程点构建按钮，或命令行（`QuickDevelop.kt:670-680`）：

```bash
./gradlew assembleDebug
```

产物在 **`app/build/outputs/apk/debug/`**。

工程默认值（由向导传入，`QuickDevelop.kt:147-148`、`QuickDevelop.kt:391-401`）：

| 项 | 值 | 来源 |
|---|---|---|
| `compileSdk` | 36 | `PROJECTS_COMPILE_SDK_VERSION`（`utilities/templates-api/.../constants.kt:74`，= `Sdk.BakLava.api`） |
| `targetSdk` | 34 | `QuickDevelop.kt:396` |
| `minSdk` | 用户向导里选的（默认 21） | `Options.OPT_MIN_SDK`（`Options.kt:34`） |
| Java | 17 | `QuickDevelop.kt:401` |

---

## 深挖

| 你想知道 | 去哪看 |
|---|---|
| 结绳库移植到什么程度、修过哪些 bug、剩余未覆盖项 | [`docs/ticode-port/PROGRESS.md`](../ticode-port/PROGRESS.md) |
| 反射覆盖度怎么测的、89.9% 这个数字怎么来的 | [`docs/ticode-port/coverage/README.md`](../ticode-port/coverage/README.md) |
| 移植失败项明细 | [`docs/ticode-port/INVENTORY.md`](../ticode-port/INVENTORY.md) |
| 真机上把模板产出的东西全跑一遍（自检 harness） | [`tools/qd-selfcheck/README.md`](../../tools/qd-selfcheck/README.md) |

### 想改模板本身

模板由四个文件组成，都在
`core/app/src/main/java/com/tom/rv2ide/templates/android/`：

| 文件 | 职责 |
|---|---|
| `QuickDevelop.kt` | 工程骨架、Gradle 配置、资源拷贝、落盘编排 |
| `quickdevelop/QuickDevelopSources.kt` | `ui/` 45 个组件的源码文本 + `MainActivity` + `ui/README.md` |
| `quickdevelop/QuickDevelopToolkits.kt` | `tool/` 5 个工具类的源码文本 + `tool/README.md` |
| `quickdevelop/QuickDevelopTicode.kt` | `:ticode` 模块的 `build.gradle` + README + 选型指引 |

设计约定：

- **数量不硬编码**。45 从 `CHINESE_COMPONENT_COUNT` 取，333 从 `EXPECTED_SOURCE_COUNT` 取，
  5 从 `all().size` 取 —— 硬编码会在下次加控件时过期（`QuickDevelopSources.kt:752-763`）。
- **文档从规格表产出**。`ui/README.md` 与 `tool/README.md` 都是代码生成，
  改表即改文档（`QuickDevelopSources.kt:1498-1505`）。
- **ticode 源码放 assets**，不放 Kotlin 字符串常量 —— 否则会造出 700KB 的巨型 object，
  且每次改 ticode 都要重新生成字符串（`QuickDevelop.kt:513-517`）。

相关测试（JVM 上跑，不需要 Robolectric）：

```bash
./gradlew :core:app:testDebugUnitTest --tests '*QuickDevelop*'
```

- `QuickDevelopSourcesTest.java` — 组件数量、别名规则、README 完整性、`卡片`/`侧栏` 等特例
- `QuickDevelopTicodeTest.java` — 333 个源文件完整性、包结构、选型指引覆盖
- `QuickDevelopProjectGenTest.java` — 生成完整工程到 `core/app/build/qd-project/`

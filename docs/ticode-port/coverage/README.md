# ticode 真机反射覆盖度测量

## 结果（2026-10-06，黑鲨 SKW-A0 / Android 10 / SDK 29）

```
方法覆盖  2881 / 3204 = 89.9%
未覆盖     316
抛异常     516（绝大多数是合成参数/驱动环境产物，非库 bug）
```

分母 3204 = manifest 里 **真正声明在各 ticode 类上**的 public 方法数（已剔除
manifest 正则误挂到外层类的匿名/内部类方法）。全量反射方法数 3416。

## 怎么跑

```bash
# 1) 生成方法清单（334 类 / 3416 方法 → manifest.json）
python docs/ticode-port/coverage/gen_manifest.py \
  core/ticode/src/zh/java D:/android/tmp/manifest.json

# 2) 生成 QuickDevelop 工程（模板单测）
JAVA_HOME=... ./gradlew :core:app:testDebugUnitTest --tests '*QuickDevelopProjectGenTest*' --offline

# 3) 用覆盖驱动覆盖 MainActivity（并放 manifest 进 assets）
P=core/app/build/qd-project
cp docs/ticode-port/coverage/CoverageDriver.java       $P/app/src/main/java/com/example/qdtest/
cp docs/ticode-port/coverage/CoverageMainActivity.java $P/app/src/main/java/com/example/qdtest/MainActivity.java
mkdir -p $P/app/src/main/assets && cp D:/android/tmp/manifest.json $P/app/src/main/assets/ticode-manifest.json

# 4) 构建 + 安装 + 启动
bash tools/build-qd-project.sh --no-gen
adb -s 9c18cb30 install -r -d $P/app/build/outputs/apk/debug/app-debug.apk
adb -s 9c18cb30 shell am start -W -n com.example.qdtest/.MainActivity

# 5) 取结果（写文件 + logcat）
adb -s 9c18cb30 pull /sdcard/Android/data/com.example.qdtest/files/coverage.txt coverage-report.txt
adb -s 9c18cb30 logcat -d -s TICODE_COV | grep COVERAGE
```

## 驱动怎么工作

- 读 assets 里的 `ticode-manifest.json`，逐类 `Class.forName` + 逐个 public 方法反射调用。
- **合成真实参数**（不是 null）：null 会撞上入口的 `if (x == null) return`，看着"调过了"其实没进主体。
  参数合成顺序：基本类型 → String/集合/Map → 枚举首值 → 框架类型（MotionEvent/KeyEvent/
  Animator/Handler/Typeface/Sensor…）→ 接口/抽象用 JDK 动态代理 → 其余按**所有公开构造器**递归合成。
- **判定**：invoke 返回、或抛出**方法内部**的异常，都算"执行过"；只有「造不出实例 / 参数合成失败 /
  方法未找到」才算未覆盖。
- 每个方法在独立线程跑，300ms 超时保护（有些方法 `while(true)` 永不返回）。
- 记录**未覆盖原因**与**异常明细**，写文件 + logcat。

## 关键安全约束（踩过的坑）

1. **`sun.misc.Unsafe.allocateInstance` 对 abstract 类会 SIGABRT** —— CheckJNI 的
   `AllocObject` 检测到抽象类直接 abort（不是抛异常，Java 兜底抓不到，整个驱动进程死）。
   → 只在 `!Modifier.isAbstract` 时走 Unsafe。
2. **Unsafe 对 native 父类链会 SIGSEGV** —— `extends android.graphics.Path/View/Activity/...`
   的类，Unsafe 绕构造器后 native 字段未初始化，一用就段错误。
   → `不安全可用()` 两趟扫描父类链，命中 native 前缀即禁用 Unsafe，改走真实构造器。
   （`构建路径 extends android.graphics.Path` 曾是首个死因：单趟扫描时自身 `ticode.zh.` 前缀先放行。）
3. **Activity 参数要给真实 Activity**，不能给 `ContextWrapper` 包装 —— 否则 `安卓窗口.newActivity`
   等会 `IllegalArgumentException`。驱动里 `act` 用真实 Activity，`ctx` 才用阻断包装。
4. **覆盖 Activity 继承 `安卓窗口`** —— 这样本实例就是 `安卓窗口` 的真实子类实例，
   驱动用它直接覆盖该 abstract 类的 48 个实例方法（否则永远造不出实例）。
   ⚠️ 副作用：`安卓窗口.newActivity` 会真的 startActivity（设备上会弹 MIUI 确认框/文件选择器）。
   目前靠"先清后台再冷启动"规避，未在 Activity 里覆写 `startActivity`（覆写反而引入不稳定）。

## 静态扫描：壳类强转（不止跑覆盖，还要静态查"跑到了也会崩"）

覆盖率停在 89.9% 后，对这 316 个未覆盖方法做静态分析发现：它们集中在
Activity/Context/Drawable/PackageManager 等类，**恰好与已抓到的 CCE 是同一模式** ——
调框架方法后把返回值强转成壳子类。即"数字低反而掩盖了 bug"。

两个扫描脚本（均入库）：

| 脚本 | 覆盖的表达式形式 |
|---|---|
| `scan_shellcast.py` | `(壳) this.X()` —— 用 manifest 的声明返回类型判定 |
| `scan_all_casts.py` | 全量 `(壳) EXPR`：new 原生 / this.X() / 变量 / 静态工厂，按形式分类 |

**判定原则**：不只看模式。`(可扩展文本构建器) this.append()` 与
`(可绘制对象) this.getCurrent()` 形式相同，但前者返回 `this`（真实实例是子类）安全、
后者返回框架 `Drawable` 必崩 —— 故查**被强转方法的声明返回类型**（必要时 `javap` 核对）。

三轮共修 **28 处**运行时 CCE：

1. `(壳) new 原生类()` 系列（早期）
2. `(壳) this.框架方法()` 22 处：`getResources`/`getPackageManager`/`getPackageInfo`/
   `loadIcon`/`loadBanner`/`loadLogo`/`getCurrent`/`getJSONObject`/`getParent` …
3. 静态工厂 + 集合元素 6 处：`Drawable.createFromPath/createFromStream`、
   `Toast.makeText`、`(悬浮窗)集合元素`、冗余 `(可扩展文本构建器)追加对象()`

修法统一为**返回原生类型、去掉强转**（多数壳类 abstract，无法包装）；`安卓提示框`
因继承 Toast 且只能 `super(null)`，改为**持有原生 Toast + 全部委托**。

**全量扫描 DANGER：22 → 0**（剩余 UNKNOWN 均为安全形式：`return this`、已加 instanceof
防护的三元、以及 `窗口组件.取安卓窗口` 的假阳性——真实场景 context 即 Activity）。

### 修复验证（THROWN 差集）

对覆盖报告做"曾崩方法是否还在 THROWN"差集：**24/26 已消失**。剩 2 个是 harness 产物：
- `大整数#减_op` NPE —— Unsafe 造的 BigInteger 内部 `mag` 数组为 null（真实 `new 大整数("1",10)` 正常）
- `安卓提示框#新建提示框` —— 覆盖驱动在后台线程跑，Toast 要求 Looper（真实用法在主线程）

## 剩余 316 未覆盖 = 反射可达性的真实天花板

| 原因 | 数量 | 说明 |
|---|---|---|
| 无实例（native 父类/abstract） | 241 | `安卓环境`/`可绘制对象`/`X窗口`/`服务`/`记录集`/`安卓程序包管理器` 等，**必须真实 framework 实例**才能构造，反射无法安全造 |
| 参数无法合成 | 75 | `安卓程序包管理器`(26)、`输入流/输出流`(21，ticode 壳需自身实例)、`ViewHolder`(9)、`可绘制对象`(5)、`SensorEvent`/`缩放处理器` 等框架回调参数 |

这些类要么在 **设备自检 harness**（`tools/qd-selfcheck/`，真实 Activity/控件环境下）里覆盖，
要么属于框架自身回调，非 ticode 库的 API 面。

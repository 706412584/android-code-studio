# 结绳 (.t) → Java 移植进度锚点

> 最后更新：2026-10-06（从会话 `e5e3dfa8` 恢复后实测校准）
> 失败项明细见同目录 **`INVENTORY.md`**。

---

## 0. 一句话现状

**中文版（`src/zh/java`）编译 0 错误 + 运行时冒烟全绿**，
**已接入 QuickDevelop 模板**（生成独立 `:ticode` 模块），
**并已在真机端到端验证**（建工程 → 构建 APK → 安装 → 启动 → ticode 真实运行），
**反射覆盖度实测 89.9%（2881/3204，见 `coverage/README.md`）**。

**英文版（`src/main/java`）是 657 处** —— 比中文版差 5 倍，**不是同构**（早期误判已证伪，见 §1 注）。

### 反射覆盖驱动（2026-10-06 第三轮）

`docs/ticode-port/coverage/`：真机遍历 334 类全部 public 方法，2881/3204 = **89.9%**。
过程中暴露并修复了一批"编译通过、一调就崩"的真 bug（详见 §8 与 git `9e3366c`）：

- **ClassCastException 16→1**（`(大数字) this.subtract()`、`(安卓线程) Thread.currentThread()`、
  `(ArrayList) getPathSegments()`、`(String[]) Set.toArray()` 等强转子类壳/框架类型）
- **NoSuchMethodError 4→0**（`Canvas.quickReject` 是 @hide；`WifiInfo.getWifiStandard` 是 API30+）
- **StackOverflowError 1→0**（`自定义宫格列表框.加载布局` 调自身无限递归）
- **误标 abstract 48 类具体化**（自身无抽象方法、只是翻译器沿用结绳标注，导致用户无法 new）

剩余 316 未覆盖 = **反射可达性的真实天花板**：241 个方法所在类（Activity/Context/Drawable/
PackageManager/Service…）**必须真实 framework 实例**，反射无法安全构造；75 个是同类参数。
这部分只能在设备自检 harness 的真实环境下覆盖，属框架自身回调面，非库 API 缺陷。

### 端到端验证证据（2026-10-06，黑鲨 9c18cb30 / Android 10）

真机上 QuickDevelop 工程点击按钮后显示：
```
你输入了：ticode%E6%B5%8B%E8%AF%95
MD5：971c1b56f81c3f6969da4ba65e8e875d
GZIP：44B→24B ✓
```
即 `加解密操作.MD5加密()` 与 `GZIP操作.压缩/解压字节集()` 在设备上真实执行且结果正确。

### 语义残留处理（2026-10-06 第二轮）

1. **跨方法块宏** ✅ 已内联 5 处调用点（网络请求×2、局域网工具×2、悬浮窗权限窗口）。
   结绳 `提交到新线程运行() ... 结束提交到新线程()` 是跨方法块宏，转译器未展开 →
   中间用户代码裸露在主线程，`等待新线程执行完毕()` 的 `thread` 为 null（NPE 被吞）。
   现已展开为 `Thread thread = new Thread(new Runnable(){ run(){ ... }}); thread.start(); thread.join();`。
   `线程池` / `获取线程同步锁` / `容错处理` 等宏**无调用点**，无需处理。
2. **`分割线` 家族** ✅ 已评估：无法忠实恢复（依赖从未发布的
   `com.Meng.decoration.SpacesItemDecoration`）。保持框架类型 `RecyclerView.ItemDecoration` 回退。
3. **`简易无障碍.java`** 生成到输出根目录（源码 `包名` 被注释，设计如此），不在 `ticode/zh/*` 包内。

### ⚠️ 冒烟测试抓到的系统性 bug（已修，务必记住）

**编译通过 ≠ 运行时正确。** 第二轮用 `tools/ticode-smoke.sh` 真跑，抓到两类：

1. **`(abstract 壳) new 原生类()` → 运行时 `ClassCastException`**（父类实例不能转成子类）。
   转译器把「`变量 x : 壳` 后赋原生值」一律写成强转。壳类全是
   `abstract class X extends 原生类` 且**无子类** → 47 处强转全是定时炸弹。
   修法：**壳类去 `abstract` 具体化** + 构造器参数改原生类型 + 调用点 `new 壳(...)`。
2. **`ZIP条目[] = stream.toArray(ZIP条目[]::new)` → `ArrayStoreException`**（元素是原生 `ZipEntry`）。
   修法：`ZIP条目` 加包装构造器 `ZIP条目(ZipEntry)`，逐元素包装。

这两类**只有真跑才抓得到**。故：**任何涉及壳类/流/数组的改动，改完必须跑 `ticode-smoke.sh`**。

### ⚠️ 关键教训：绝不重新生成

手工修复直接改在 `src/zh/java`。**任何 `translate.py` 重新生成都会覆盖这些修复**。
要改转译器规则时，必须把改动**回灌**到已生成的树上，或先备份。

---

## 1. 产物地址（重要：仓库那份是旧的！）

| 产物 | 路径 | 错误数 | 说明 |
|---|---|---|---|
| **中文版（目标）** | `core/ticode/src/zh/java` | **0** | 手工修复后，**以此为准** |
| 英文版 | `core/ticode/src/main/java` | **657** | 另需一个 5 倍大的工程；暂缓 |
| 旧快照 | `D:\android\tmp\jbb-zh-java` | 135 | 修复前的中文版，仅作对照 |

> ⚠️ **英文版比中文版差 5 倍**：`src/main/java` 与 `D:\android\tmp\gen-en-new` 内容完全相同（332/334 文件 md5 一致），
> 差异只在 2 个文件。早期把「12 错」当成英文版基线是**错的** —— 那 12 处是级联假象，
> 打掉后英文版真实是 **657** 处（zh 树因 dedup 丢弃了 `ExtraResourceManager` 等更多问题类，所以干净得多）。
> `gen-en-new` 那 12 处是**同一棵树**（英文），与 zh 无关。
> **中文版始终是正确路线**，英文版是后续独立工程。

### 源码（结绳 .t）
```
D:\android\tmp\jbb\源代码\            42 个顶层 .t
D:\android\tmp\jbb\源代码\AndroidX\    8 个 .t
D:\android\tmp\jbb\依赖库\
```
**50 个 .t → 333 个类**（文件少但单个大：`安卓_可视化组件.t` 80KB、`结绳_工具类.t` 62KB）。

---

## 2. 编译方法（别再跑 Gradle，2 秒的事）

```bash
cd core/ticode
export JAVA_HOME="C:/Users/70641/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2"
CP="libs/android.jar"; for j in libs/*.jar libs/androidx/*.jar; do CP="$CP;$(cygpath -w "$j")"; done
find <产物目录> -name '*.java' | sed 's|^/d/|D:/|' > srcs.txt   # 路径必须 Windows 式
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -Xmaxerrs 2000 \
  -d <out> -classpath "$CP" @srcs.txt 2> err.txt
iconv -f GBK -t UTF-8 -c err.txt > err_utf8.txt
```

**单次全量 javac 约 2 秒**（不是会话里说的 3–9 分钟——那是 Gradle 转译+编译+转码全流程）。
**别再「改一处编译一次」**：拿一次全量清单，批量改完再编。

---

## 3. 错误轨迹（实测校准）

| 阶段 | 错误数 | 状态 |
|---|---|---|
| 初始 | 1002 | — |
| 别名表精简 | 992 | 已提交 `306775e` |
| 三线并行 | 439 | 已提交 `dc90b7b` |
| 完整补丁序列 | 415 | 已提交 `a07c173` |
| 会话后期修复 | 135 | 手工修复前的中文版基线 |
| **手工批量修复（2026-10-06）** | **0** | **未提交**（4 路并行：D 27 / C 26 / B 38 / 小簇 31，共改 144 文件） |

> 会话里报的「12 处」是**假象**：`属性写` 内的 `变量` 被生成成方法体内 `public int 方向;`（非法），
> 触发 parse 失败 → 整个文件级联报错，把后续 123 处真实错误全掩盖了。打掉这 1 处后暴露 135 处。

---

## 4. 失败项分组（详见 INVENTORY.md）

**全部完成（2026-10-06，4 路并行手工修复 + 主 agent 收尾级联）：**

| 组 | 处数 | 状态 | 修法摘要 |
|---|---|---|---|
| D java.lang.reflect / 泛型壳 | 27 | ✅ | 壳类补 `implements ParameterizedType/TypeVariable/...`；反射调用桥接 |
| C 流链包装类 ↔ 原生流 | 26 | ✅ | ZIP/GZIP/File 流构造与赋值 + try/catch IOException |
| 小簇 J1/J3/J4/J5/J6/I/K | 31 | ✅ | 属性读写转方法、`PendingIntent.*` 静态、Path 桥接、反射新建对象… |
| B 框架原生对象 ↔ 中文壳 | 38 | ✅ | 壳类改「持有内部对象」、类型转换、`Message.obtain`… |
| 收尾级联（主 agent） | ~30 | ✅ | 解开类型错误掩盖的「缺返回语句 / 未初始化变量 / IOException / 块宏」 |

> **级联效应**：修好类型错误后，原本被掩盖的**真实缺陷**才暴露（如 `安卓线程`/`流程处理`/`常用操作`
> 的「缺返回语句」、`网络请求`/`悬浮窗`/`颜色操作` 的「未初始化变量」、GZIP/ZIP 的 `IOException`）。
> 这些不是回归，是一直存在、被前序错误挡住的问题。

**另有 1 处机械性 bug（已修）**：`parse_switch` 生成 `case X:` **不补 `break;`** →
全库 **13 处 switch 真穿透**（编译能过，语义错，会覆盖返回值）。已手工插入 `break;`。
转译器 `parse_switch` **尚未修**，重新生成会复现。

---

## 5. 打法（用户已定）

- **不要「修一个跑一次编译」** —— 一次全量 2 秒，但改一处编一次仍浪费时间；批量改。
- **不要再用转译器去逐个修错误** —— 手动改更快；**只有机械性错误才回退到转译器**（如 switch 补 break、属性读写全局转换）。
- **并行代理/团队**：D / C / B 三组互相独立，各派一个 agent；小簇 J1/J3/… 是单点全局改造，留给主 agent。
- **手工优先于转译器**：只有**机械性**错误才回灌转译器（见 §4 的 switch break），其余一律手改。

### 工具

| 命令 | 作用 |
|---|---|
| `tools/ticode-compile.sh [zh\|en]` | 单次全量 javac（约 2 秒，不跑 Gradle），错误总数 + 按文件聚合 + 明细 |
| `tools/ticode-smoke.sh` | 编译并运行 `core/ticode/smoke/*.java` 的 JVM 断言（23 项），验证**运行时**行为 |

已配好 `JAVA_HOME` / classpath / GBK→UTF-8 转码。**改完一批再编，不要改一处编一次。**
**凡涉及壳类 / 流 / 数组的改动，改完必须跑 smoke —— 编译过不等于跑得过。**

---

## 6. 需要用户拍板的结构性决策（阻塞 B/A/C/D 的一部分）

结绳语义与 Java 类型系统**根本冲突**：
- `文本 : 字符串` + `@指代类("String")` —— 结绳要求 `文本` 可被继承，Java `String` 是 `final`。
- `整数` + `@指代类("int")` —— 结绳 `整数` 是类、有方法；Java `int` 是原始类型。
- `集合` + `@指代类("java.util.List")` —— `List` 是接口，不能 `extends`。
- `WifiInfo`/`DragEvent`/`InputEvent` —— 构造器包私有，包装类只能当类型别名。

**候选**：① 彻底双形态（原生值 + 壳实例并存）；② 允许降级（放弃部分可继承性换编译通过）。

---

## 7. 失败的实验（勿重试）

- **跨文件基类注入** → 421（比 309 差）。搬进来的方法体缺 import（`安卓X窗口` 注入后缺 `Build` → 8 处暴增到 87）。已回滚。
- **别名壳层移除 extends + this 重定向（持有者模式）** → 449 > 415，符号缺失归零但总数更差。已否定。
- **68 个类的单继承抉择**：别名优先 = 415（丢 `刷新`/`关闭`），基类优先 = 697（丢 `到字节集`）。两难，未解。

---

## 8. 关键陷阱

- **中文 Windows 是 GBK**：javac 默认按 GBK 读源文件会读坏中文标识符；必须 `-encoding UTF-8`。命令行输出过 `iconv -f GBK -t UTF-8 -c`。
- **javac 的 `@argfile` 必须用 Windows 路径**（`D:/...`），Linux 式 `/d/...` 会报「找不到文件」。
- **`public int 方向;` 类错误会级联**：方法体内非法声明 → parse 失败 → 整个文件后续真实错误被掩盖。看到「未命名类/需要 class」这类怪错误，先查文件里有没有方法体内的 `public` 字段。
- **KSP daemon 争用**：多 agent 同时编译会撞 `...kspCaches\debug\symbolLookups\file-to-id.tab is already registered`。并行时约定单一编译权。
- **`translate.py` 补丁锚点冲突**：`fix_misc2.py` 锚定 base415，在别的基线应用会失败（锚点落在已改动的 `subst` 区域）。合并补丁前先 rebase。
- **`parse_switch` 已补 `break;`**（2026-10-06）：结绳 `假如/是` 是匹配单支语义，Java switch 默认穿透。
  修复在 `parse_switch` 内用**局部** `_flush/_mark`（不能用实例属性，嵌套 switch 会互相覆盖）。
  `返回`/`容错处理` 等已跳出的块不补（避免不可达语句）。
  ⚠️ 该修复**尚未重新生成产物** —— `src/zh/java` 里的 13 处 break 是手工插的，二者一致。

---

## 9. 相关文件

```
tools/jieba-translate/
  translate.py          # 主转译器（TICODE_LANG=en|zh）；parse_switch 已补 break
  class_map.py          # 结绳类名/包名 → 英文映射
  alias_nonextend.py    # @指代类 里不可继承的目标
  dedup_set.py          # 与模板重叠需剔除的类
  fix_wrapper.py / fix_symbols.py / fix_misc.py   # 三份补丁
core/ticode/
  build.gradle.kts      # 中文版 sourceSet（-Pticode.lang=zh）
  libs/androidx/        # 48 个编译期依赖 jar（见 libs/README.md 重建）
docs/ticode-port/
  PROGRESS.md           # 本文件
  INVENTORY.md          # 135 处失败项逐条明细（按根因分组）
  coverage/             # 真机反射覆盖度测量（驱动 + 报告 + 复现脚本）
    README.md           # 结果、跑法、安全约束、天花板分析
    CoverageDriver.java / CoverageMainActivity.java
    gen_manifest.py / deabstract3.py
    coverage-report.txt / coverage-analysis.txt
```

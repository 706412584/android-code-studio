# QuickDevelop 真机自检（控件 + 工具 + ticode）

在真机上把模板产出的东西**全部跑一遍**并显示结果。

## 它测什么

| 部分 | 覆盖 | 方式 |
|---|---|---|
| ① 控件 | **44/44** 中文控件 | 反射 `Class.forName("...ui."+名).getConstructor(Context)` 逐个实例化，加进可见布局（折叠区），每个单独 try/catch |
| ② 工具库 `tool/` | **96 个静态方法中的 91 个** | 逐方法调用并断言返回值（SQLite / 文件 / 剪切板 / 截屏 / 动画 / 线程…） |
| ③ ticode | MD5 / Base64 / GZIP 往返 / 正则 | 已知向量断言 |

**跳过的 5 个**（会在屏幕注明）：`系统.打开应用`、`系统.卸载应用`（会切走自检界面）、
`工具.显示桌面`（同上）、`工具.媒体播放` 及 5 个媒体控制（需真实音视频文件）。

## 怎么用

```bash
# 1) 生成模板工程（复用真实 writer）
JAVA_HOME=... ./gradlew :core:app:testDebugUnitTest --tests '*QuickDevelopProjectGenTest*'
# 2) 用自检版覆盖 MainActivity
cp tools/qd-selfcheck/MainActivity.java \
   core/app/build/qd-project/app/src/main/java/com/example/qdtest/MainActivity.java
# 3) 本机构建 APK
bash tools/build-qd-project.sh --no-gen
# 4) 装到真机并启动
adb -s 9c18cb30 install -r -d core/app/build/qd-project/app/build/outputs/apk/debug/app-debug.apk
adb -s 9c18cb30 shell am start -W -n com.example.qdtest/.MainActivity
```

屏幕顶部显示 `自检汇总：PASS n FAIL m / 控件 44/44`。

## 踩过的时序坑（写测试时注意）

1. **截屏要在布局之后**：`系统.截屏` 依赖 `root.getWidth()>0`，在 `setContentView` 之前调用会返回 null。
2. **剪切板要在窗口获焦之后**：Android 10 起，后台/无焦点时读剪切板返回空。
3. **`工具.延迟执行` 不能在主线程 sleep 等**：它 post 到 main looper，主线程 sleep 会把该 looper 堵死 →
   永远不触发。应改为「排队 + 异步回调里写结果」。

前两条决定了测量要放在 `onWindowFocusChanged` 而非 `onCreate`。

## 历史结果

- 2026-10-06，黑鲨 SKW-A0（Android 10 / SDK 29）：**PASS 83 / FAIL 0，控件 44/44**。
  首轮曾 4 个 FAIL，全是上表时序坑（测试代码问题，非库 bug），修正后全绿。

## ticode 控件 vs ui/ 控件（决定「UI 层留不留」的依据）

同一 harness 换成对比模式（`TICODE_控件` + `UI_控件` 两组）跑出的结果：

| | 数量 | 结果 | 模式 |
|---|---|---|---|
| **ticode 控件** | 21 | **21/21** | **包装**：对象本身不是 View，`getView()` 暴露原生 View |
| **ui/ 控件** | 44 | **44/44** | **继承**：直接 `extends FrameLayout` |

ticode 的 21 个控件实测都能拿到原生 View：
`CheckBox / GridView / GridLayout / Toolbar / Switch / SeekBar / Button / TextView /
WebView / RelativeLayout / FrameLayout / ConstraintLayout / LinearLayout / AbsoluteLayout /
SurfaceView / VideoView / ProgressBar / FlexboxLayout / RecyclerView`（`画板` 返回匿名 View）。

**结论：ticode 的 UI 层不是半成品，真能用。** 但两套的**设计哲学不同**：

- `ui/` 是**为模板写的现代封装**（androidx、链式 API、中英双名、控件更全 44 个）——
  **新代码推荐用它**。
- ticode 的控件是**结绳移植的原始包装**（`getView()` 模式、API 偏旧、21 个）——
  价值在**跑结绳老代码时保持兼容**。

故：**两套并存，不删**；生成的 README 里写清「界面用 `ui/`，结绳兼容用 `ticode.zh.*`」。
`ticode` 真正不可替代的是 `jvm/` + `base/` 的 **114 个纯工具类**（加解密/ZIP/反射/集合族/大数），
那才是它相对 `tool/`（97 方法）的增量。

# ticode 中文版 · 失败项根因盘点

- 目标产物：`D:\android\tmp\jbb-zh-java`（会话最新，334 文件 / 333 类）
- 方法：单次 `javac -Xmaxerrs 2000` 全量编译（约 2 秒，非 Gradle）
- 先把 1 处级联根因（`属性写` 里 `变量` → 方法体内 `public int 方向;`）打掉后再统计

## 真实剩余：135 处（中文版；英文版同构，同 2 文件）

> 未打掉那 1 处前显示 12 处 —— 全是同一根因的级联，别被误导。

| 组 | 处数 | 修法 |
|---|---|---|
| D java.lang.reflect / 泛型壳 | 27 | |
| C 流链包装类 ↔ 原生流（ZIP/GZIP/File） | 25 | |
| B 框架原生对象 ↔ 中文壳 双向不匹配 | 20 | |
| J1 属性读/写残留（字段.属性 未转成方法调用） | 19 | |
| J5 库内方法缺失/签名不符 | 17 | |
| J6 类缺失（未生成/未导入） | 6 | |
| K 其他 | 6 | |
| A 包装类 extends 框架类（框架构造器包私有） | 5 | |
| J3 静态方法误当实例方法（PendingIntent.*） | 4 | |
| E 异常包装（Exception → 异常） | 3 | |
| J4 android.graphics.Path 方法未桥接 | 2 | |
| I 源码 bug（哈希表当数组下标） | 1 | |

> 另有 **1 处独立机械性 bug**：`parse_switch` 生成 `case X:` 不补 `break;` → 全库 15 处 switch 静默穿透（编译能过，语义错）。

---

## D java.lang.reflect / 泛型壳（27 处）

- `ticode/zh/jvm/Java参数化类型.java:22` 找不到符号  ⟵ `方法 getActualTypeArguments()`
- `ticode/zh/jvm/Java参数化类型.java:27` 找不到符号  ⟵ `方法 getRawType()`
- `ticode/zh/jvm/Java泛型变量.java:22` 找不到符号  ⟵ `方法 getName()`
- `ticode/zh/jvm/Java泛型变量.java:29` 找不到符号  ⟵ `方法 getBounds()`
- `ticode/zh/jvm/Java泛型数组类型.java:22` 找不到符号  ⟵ `方法 getGenericComponentType()`
- `ticode/zh/jvm/Java通配符类型.java:22` 找不到符号  ⟵ `方法 getUpperBounds()`
- `ticode/zh/jvm/Java通配符类型.java:27` 找不到符号  ⟵ `方法 getLowerBounds()`
- `ticode/zh/jvm/反射操作.java:13` 不兼容的类型: String无法转换为Class
- `ticode/zh/jvm/反射操作.java:35` 找不到符号  ⟵ `变量 可访问`
- `ticode/zh/jvm/反射操作.java:36` 找不到符号  ⟵ `方法 置对象值(Object,Object)`
- `ticode/zh/jvm/反射操作.java:46` 不兼容的类型: String无法转换为Class
- `ticode/zh/jvm/反射操作.java:66` 找不到符号  ⟵ `变量 可访问`
- `ticode/zh/jvm/反射操作.java:67` 找不到符号  ⟵ `方法 取对象值(Object)`
- `ticode/zh/jvm/反射操作.java:76` 不兼容的类型: String无法转换为Class
- `ticode/zh/jvm/反射操作.java:86` 找不到符号  ⟵ `方法 取方法(String)`
- `ticode/zh/jvm/反射操作.java:96` 不兼容的类型: String无法转换为Class
- `ticode/zh/jvm/反射操作.java:106` 找不到符号  ⟵ `方法 取方法(String,Class[])`
- `ticode/zh/jvm/反射操作.java:116` 找不到符号  ⟵ `变量 可访问`
- `ticode/zh/jvm/反射操作.java:117` 找不到符号  ⟵ `方法 执行(Object,Object[])`
- `ticode/zh/jvm/反射操作.java:126` 不兼容的类型: String无法转换为Class
- `ticode/zh/jvm/反射操作.java:136` 找不到符号  ⟵ `方法 取构造方法(Class[])`
- `ticode/zh/jvm/反射操作.java:137` 找不到符号  ⟵ `变量 可访问`
- `ticode/zh/jvm/反射操作.java:138` 找不到符号  ⟵ `方法 创建实例(Object[])`
- `ticode/zh/jvm/反射操作.java:147` 不兼容的类型: String无法转换为Class
- `ticode/zh/jvm/反射操作.java:158` 找不到符号  ⟵ `方法 取所有字段()`
- `ticode/zh/jvm/反射操作.java:162` 找不到符号  ⟵ `变量 类型`
- `ticode/zh/jvm/反射操作.java:175` 找不到符号  ⟵ `变量 完整类名`

---

## C 流链包装类 ↔ 原生流（ZIP/GZIP/File）（25 处）

- `ticode/zh/android/文件操作.java:76` 不兼容的类型: String无法转换为File
- `ticode/zh/jvm/GZIP操作.java:10` 不兼容的类型: 字节集输出流无法转换为GZIP输出流
- `ticode/zh/jvm/GZIP操作.java:18` 不兼容的类型: byte[]无法转换为字节集输入流
- `ticode/zh/jvm/GZIP操作.java:20` 不兼容的类型: 字节集输入流无法转换为GZIP输入流
- `ticode/zh/jvm/GZIP操作.java:35` 不兼容的类型: String无法转换为File
- `ticode/zh/jvm/GZIP操作.java:36` 不兼容的类型: File无法转换为文件输入流
- `ticode/zh/jvm/GZIP操作.java:37` 不兼容的类型: String无法转换为File
- `ticode/zh/jvm/GZIP操作.java:39` 不兼容的类型: 文件输出流无法转换为GZIP输出流
- `ticode/zh/jvm/GZIP操作.java:52` 不兼容的类型: String无法转换为File
- `ticode/zh/jvm/GZIP操作.java:53` 不兼容的类型: File无法转换为文件输入流
- `ticode/zh/jvm/GZIP操作.java:54` 不兼容的类型: String无法转换为File
- `ticode/zh/jvm/GZIP操作.java:56` 不兼容的类型: 文件输入流无法转换为GZIP输入流
- `ticode/zh/jvm/GZIP操作.java:70` 不兼容的类型: FileOutputStream无法转换为文件输出流
- `ticode/zh/jvm/ZIP操作.java:11` 不兼容的类型: String无法转换为File
- `ticode/zh/jvm/ZIP操作.java:12` 不兼容的类型: String无法转换为File
- `ticode/zh/jvm/ZIP操作.java:14` 不兼容的类型: 文件输出流无法转换为ZIP输出流
- `ticode/zh/jvm/ZIP操作.java:42` 不兼容的类型: File无法转换为文件输入流
- `ticode/zh/jvm/ZIP操作.java:56` 不兼容的类型: String无法转换为ZIP文件
- `ticode/zh/jvm/ZIP操作.java:57` 不兼容的类型: String无法转换为File
- `ticode/zh/jvm/ZIP操作.java:64` 找不到符号  ⟵ `变量 是文件夹条目`
- `ticode/zh/jvm/ZIP操作.java:69` 找不到符号  ⟵ `方法 寻找文本(String,int)`
- `ticode/zh/jvm/ZIP操作.java:91` 不兼容的类型: String无法转换为ZIP文件
- `ticode/zh/jvm/ZIP操作.java:95` 不兼容的类型: String无法转换为File
- `ticode/zh/jvm/ZIP操作.java:111` 不兼容的类型: FileOutputStream无法转换为文件输出流
- `ticode/zh/jvm/ZIP文件.java:58` 不兼容的类型: 推论变量 A 具有不兼容的上限

---

## B 框架原生对象 ↔ 中文壳 双向不匹配（20 处）

- `ticode/zh/android/WiFi管理器.java:20` 不兼容的类型: WifiInfo无法转换为WiFi信息
- `ticode/zh/android/可视化组件.java:472` 不兼容的类型: ViewPropertyAnimator无法转换为组件属性动画播放器
- `ticode/zh/android/可视化组件.java:631` 不兼容的类型: DragEvent无法转换为拖放事件
- `ticode/zh/android/可视化组件.java:663` 不兼容的类型: KeyEvent无法转换为按键事件
- `ticode/zh/android/图片操作.java:151` 不兼容的类型: ArrayList无法转换为集合
- `ticode/zh/android/安卓应用.java:43` 不兼容的类型: Application无法转换为应用
- `ticode/zh/android/提示框.java:15` 不兼容的类型: Toast无法转换为安卓提示框
- `ticode/zh/android/样式_字体大小.java:11` 不兼容的类型: int无法转换为样式_字体大小
- `ticode/zh/android/样式_字体大小.java:15` 不兼容的类型: AbsoluteSizeSpan无法转换为样式_字体大小
- `ticode/zh/android/画板.java:36` 不兼容的类型: Canvas无法转换为画布对象
- `ticode/zh/android/网络请求结果.java:60` 不兼容的类型: String无法转换为JSON对象
- `ticode/zh/android/网络请求结果.java:65` 不兼容的类型: String无法转换为JSON数组
- `ticode/zh/android/表层画板.java:76` 不兼容的类型: Canvas无法转换为画布对象
- `ticode/zh/jvm/双端队列.java:7` 不兼容的类型: ArrayDeque无法转换为双端队列
- `ticode/zh/jvm/反射操作.java:188` 不兼容的类型: DexClassLoader无法转换为Dex类加载器
- `ticode/zh/meng/弹性布局.java:113` 不兼容的类型: Drawable无法转换为可绘制对象
- `ticode/zh/meng/弹性布局.java:121` 不兼容的类型: Drawable无法转换为可绘制对象
- `ticode/zh/meng/高级适配器.java:94` 不兼容的类型: ArrayList无法转换为集合
- `ticode/zh/meng/高级适配器.java:227` 不兼容的类型: 集合无法转换为高级适配器
- `ticode/zh/meng/高级适配器.java:232` 不兼容的类型: 集合无法转换为高级适配器

---

## J1 属性读/写残留（字段.属性 未转成方法调用）（19 处）

- `ticode/zh/android/位图对象.java:133` 找不到符号  ⟵ `变量 宽度`
- `ticode/zh/android/位图对象.java:134` 找不到符号  ⟵ `变量 高度`
- `ticode/zh/android/位图对象.java:152` 找不到符号  ⟵ `变量 配置`
- `ticode/zh/android/提示框.java:22` 找不到符号  ⟵ `变量 显示时长`
- `ticode/zh/android/提示框.java:29` 找不到符号  ⟵ `变量 显示时长`
- `ticode/zh/android/提示框.java:36` 找不到符号  ⟵ `变量 布局`
- `ticode/zh/android/提示框.java:43` 找不到符号  ⟵ `变量 布局`
- `ticode/zh/android/提示框.java:50` 找不到符号  ⟵ `变量 内容`
- `ticode/zh/android/提示框.java:57` 找不到符号  ⟵ `变量 横向边距`
- `ticode/zh/android/提示框.java:64` 找不到符号  ⟵ `变量 纵向边距`
- `ticode/zh/android/提示框.java:71` 找不到符号  ⟵ `变量 横向偏移`
- `ticode/zh/android/提示框.java:78` 找不到符号  ⟵ `变量 纵向偏移`
- `ticode/zh/android/提示框.java:85` 找不到符号  ⟵ `变量 对齐方式`
- `ticode/zh/android/文本框.java:315` 找不到符号  ⟵ `变量 内容`
- `ticode/zh/android/构建路径.java:113` 找不到符号  ⟵ `变量 path1`
- `ticode/zh/android/构建路径.java:113` 找不到符号  ⟵ `变量 path2`
- `ticode/zh/android/流程处理.java:135` 找不到符号  ⟵ `变量 thread`
- `ticode/zh/android/网络请求.java:158` 找不到符号  ⟵ `变量 httpGetResult`
- `ticode/zh/android/自定义宫格列表框.java:21` 找不到符号  ⟵ `变量 适配器`

---

## J5 库内方法缺失/签名不符（17 处）

- `ticode/zh/android/加解密操作.java:66` 找不到符号  ⟵ `方法 到字节集(String)`
- `ticode/zh/android/可扩展文本构建器.java:24` 找不到符号  ⟵ `方法 取字符(int)`
- `ticode/zh/android/安卓环境.java:94` 找不到符号  ⟵ `方法 取程序包信息(String,int)`
- `ticode/zh/android/应用操作.java:13` 找不到符号  ⟵ `方法 取程序包信息(String,int)`
- `ticode/zh/android/应用操作.java:42` 找不到符号  ⟵ `方法 取程序启动信息(String)`
- `ticode/zh/android/文件操作.java:524` 找不到符号  ⟵ `方法 到数组()`
- `ticode/zh/android/文本框.java:319` 找不到符号  ⟵ `方法 匹配(String)`
- `ticode/zh/android/文本框.java:320` 找不到符号  ⟵ `方法 匹配下一个()`
- `ticode/zh/android/文本框.java:321` 找不到符号  ⟵ `方法 取匹配开始位置()`
- `ticode/zh/android/文本框.java:321` 找不到符号  ⟵ `方法 取匹配结束位置()`
- `ticode/zh/android/服务.java:42` 找不到符号  ⟵ `方法 取通信中间件()`
- `ticode/zh/android/浏览框.java:294` 找不到符号  ⟵ `方法 为空()`
- `ticode/zh/android/消息.java:7` 找不到符号  ⟵ `方法 obtain(消息处理器,int)`
- `ticode/zh/android/网络工具.java:405` 找不到符号  ⟵ `方法 randomUUID()`
- `ticode/zh/android/网络请求.java:86` 找不到符号  ⟵ `方法 添加项目(String,String)`
- `ticode/zh/android/转换操作.java:26` 找不到符号  ⟵ `方法 到文本()`
- `ticode/zh/meng/高级列表项目触摸辅助器.java:96` 找不到符号  ⟵ `方法 cancel()`

---

## J6 类缺失（未生成/未导入）（6 处）

- `ticode/zh/android/对象操作.java:39` 找不到符号  ⟵ `类 类型`
- `ticode/zh/android/对象操作.java:43` 找不到符号  ⟵ `类 类型`
- `ticode/zh/android/自定义宫格列表框.java:22` 找不到符号  ⟵ `类 订阅事件`
- `ticode/zh/jvm/可枚举类.java:4` 找不到符号  ⟵ `类 Iterable`
- `ticode/zh/meng/高级列表框.java:208` 找不到符号  ⟵ `类 分割线`
- `ticode/zh/meng/高级列表框.java:212` 找不到符号  ⟵ `类 分割线`

---

## K 其他（6 处）

- `ticode/zh/android/ROOT操作.java:59` 不兼容的类型: Object无法转换为String
- `ticode/zh/android/ROOT操作.java:60` 不兼容的类型: Object无法转换为String
- `ticode/zh/android/加解密操作.java:67` 不兼容的类型: Object无法转换为byte[]
- `ticode/zh/android/安卓窗口.java:358` 无法将类 安卓环境中的方法 取自身包名应用到给定类型;
- `ticode/zh/android/对象操作.java:33` 不兼容的类型: Object无法转换为int
- `ticode/zh/android/文本框.java:316` 不兼容的类型: String无法转换为可扩展文本

---

## A 包装类 extends 框架类（框架构造器包私有）（5 处）

- `ticode/zh/android/WiFi信息.java:4` WifiInfo()在WifiInfo中不是公共的; 无法从外部程序包中对其进行访问
- `ticode/zh/android/安卓资源标识符.java:17` Uri()在Uri中不是公共的; 无法从外部程序包中对其进行访问
- `ticode/zh/android/拖放事件.java:4` DragEvent()在DragEvent中不是公共的; 无法从外部程序包中对其进行访问
- `ticode/zh/android/组件属性动画播放器.java:4` ViewPropertyAnimator()在ViewPropertyAnimator中不是公共的; 无法从外部程序包中对其进行访问
- `ticode/zh/android/输入事件.java:4` InputEvent()在InputEvent中不是公共的; 无法从外部程序包中对其进行访问

---

## J3 静态方法误当实例方法（PendingIntent.*）（4 处）

- `ticode/zh/android/预备启动信息.java:11` 找不到符号  ⟵ `方法 getActivity(Context,int,Intent,int)`
- `ticode/zh/android/预备启动信息.java:15` 找不到符号  ⟵ `方法 getForegroundService(Context,int,Intent,int)`
- `ticode/zh/android/预备启动信息.java:19` 找不到符号  ⟵ `方法 getService(Context,int,Intent,int)`
- `ticode/zh/android/预备启动信息.java:23` 找不到符号  ⟵ `方法 getBroadcast(Context,int,Intent,int)`

---

## E 异常包装（Exception → 异常）（3 处）

- `ticode/zh/android/套接字.java:91` 不兼容的类型: Exception无法转换为异常
- `ticode/zh/android/套接字服务端.java:128` 不兼容的类型: Exception无法转换为异常
- `ticode/zh/android/数据报.java:93` 不兼容的类型: Exception无法转换为异常

---

## J4 android.graphics.Path 方法未桥接（2 处）

- `ticode/zh/android/构建路径.java:67` 找不到符号  ⟵ `方法 addPolygon(float[],int,int,boolean)`
- `ticode/zh/android/构建路径.java:107` 找不到符号  ⟵ `方法 contains(float,float)`

---

## I 源码 bug（哈希表当数组下标）（1 处）

- `ticode/zh/android/对象操作.java:33` 需要数组, 但找到哈希表

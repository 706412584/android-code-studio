package com.example.qdtest;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 反射覆盖驱动：调用 ticode 每个 public 方法，产出覆盖率报告。
 *
 * <p>核心思路：**合成真实参数**（而非 null）——传 null 会撞上入口的
 * `if (x == null) return`，看着"调过了"其实没进主体。合成真值才能真进方法体。
 *
 * <p>判定：invoke 返回（正常）或抛出**方法内部**的异常，都算"执行过"；
 * 只有「找不到构造器/参数合成失败/无法实例化」才算"未覆盖"。
 */
public class CoverageDriver {

    public interface Sink {
        void line(String s, String color);
    }

    /** 阻断会跳走 Activity 的调用（startActivity/startService），否则驱动会被系统页打断。 */
    public static class 阻断Context extends android.content.ContextWrapper {
        public 阻断Context(Context base) { super(base); }
        @Override public void startActivity(android.content.Intent i) { /* 阻断 */ }
        @Override public void startActivity(android.content.Intent i, Bundle o) { /* 阻断 */ }
        @Override public android.content.ComponentName startService(android.content.Intent i) { return null; }
        @Override public boolean stopService(android.content.Intent i) { return false; }
    }

    private final Context ctx;       // 用阻断后的 Context
    private final Activity act;
    private final Sink sink;
    private final JSONArray manifest;

    /**
     * 跳过会**改全局状态或挂死进程**的类：
     * - 程序崩溃处理.初始化 会 Thread.setDefaultUncaughtExceptionHandler → 之后异常被吞、进程静默死
     * - 应用 会替换 Application（破坏当前进程）
     */
    private static final Set<String> 跳过类 = new HashSet<>(java.util.Arrays.asList(
            "ticode.zh.android.程序崩溃处理",
            "ticode.zh.android.应用",
            // 会 new Thread + runOnUiThread：Unsafe 造出的窗口实例未初始化，
            // 后台线程 NPE 后（Java 兜底可拦）仍可能引发 native 崩溃。
            "ticode.zh.android._悬浮窗_权限申请窗口"
    ));

    /** 跳过会**终止进程 / 挂死 / 改全局**的个别方法（类仍测其余方法）。 */
    private static final Set<String> 跳过方法 = new HashSet<>(java.util.Arrays.asList(
            "关闭程序",      // System.exit(0) + killProcess(myPid())
            "重启",          // 同类
            "优化内存",      // 可能触发 GC 压力
            "初始化",        // 崩溃处理器的初始化（改全局 handler）
            "run"            // 表层画板/SurfaceView 的渲染循环 while(true)，永不返回
    ));

    // 统计
    public int 方法总数 = 0, 已执行 = 0, 未覆盖 = 0, 抛异常 = 0, 超时 = 0;
    private final List<String> 未覆盖明细 = new ArrayList<>();
    private final List<String> 异常明细 = new ArrayList<>();
    // 已执行的（类#方法）——给外部做差集
    public final Set<String> hit = new HashSet<>();

    public CoverageDriver(Activity ctx, Sink sink, String manifestJson) throws Exception {
        this.act = ctx;
        this.ctx = new 阻断Context(ctx);
        this.sink = sink;
        this.manifest = new JSONArray(manifestJson);
    }

    public void run() {
        for (int i = 0; i < manifest.length(); i++) {
            JSONObject c = manifest.optJSONObject(i);
            if (c == null) continue;
            String fqn = c.optString("class");
            Log.i("TICODE_COV", "CLASS " + i + "/" + manifest.length() + " " + fqn);
            if (跳过类.contains(fqn)) { continue; }
            JSONArray ctors = c.optJSONArray("ctors");
            JSONArray methods = c.optJSONArray("methods");

            // 类加载不了（默认包里的 简易无障碍 等）→ 整类跳过，不计入分母。
            Class<?> clazz;
            try {
                clazz = Class.forName(fqn);
            } catch (Throwable t) {
                Log.i("TICODE_COV", "  SKIP-UNLOADABLE " + fqn);
                continue;
            }
            // 只统计**真正声明在本类上**的方法。manifest 的正则会把匿名/内部类里
            // 写的方法（如 浏览框.java 里 MyWebViewClient.onPageStarted）误挂到外层类；
            // 它们不是本类的 API，不计入分母也不报未覆盖。
            Set<String> 本类方法 = new HashSet<>();
            for (Method dm : clazz.getDeclaredMethods()) 本类方法.add(dm.getName());

            // 1) 造实例。abstract 类也试 —— 纯 Java 父类链的（SpannableStringBuilder/
            // JSONObject/Vector/ArrayList…）能用 Unsafe 绕构造器造出可用实例。
            Object instance = 造实例(fqn, ctors);
            // 若造不出，但当前 Activity 本身就是该类的实例（如覆盖 Activity 继承 安卓窗口），
            // 直接用它 —— 这是唯一能安全覆盖 Activity 子类实例方法的途径。
            if (instance == null && act != null && clazz.isInstance(act)) instance = act;
            String 无实例因 = instance == null ? 上次造实例原因 : null;
            // 父类链里有 native 类的（Path/Paint/View/Activity…）：逐方法打点，
            // 万一某方法仍触发 SIGSEGV（Java 兜底抓不到），日志能精确到方法。
            boolean 风险 = 有native父类(fqn);

            // 2) 逐个方法
            for (int j = 0; j < methods.length(); j++) {
                JSONObject m = methods.optJSONObject(j);
                if (m == null) continue;
                String name = m.optString("name");
                if (!本类方法.contains(name)) { continue; }   // 内部类方法，非本类 API
                方法总数++;
                if (跳过方法.contains(name)) { continue; }
                boolean isStatic = m.optBoolean("static");
                String key = fqn + "#" + name;
                if (风险) Log.i("TICODE_COV", "  CALL " + key);

                if (!isStatic && instance == null) {
                    未覆盖++;
                    未覆盖明细.add(key + "  (无实例:" + 无实例因 + ")");
                    continue;
                }
                Object target = isStatic ? null : instance;
                String 因 = 调方法(fqn, name, m.optJSONArray("params"), target);
                if (因 == null) {
                    已执行++;
                    hit.add(key);
                } else {
                    未覆盖++;
                    未覆盖明细.add(key + "  (" + 因 + ")");
                }
            }
        }
    }

    /** 上次 造实例 失败原因（供未覆盖明细）。 */
    private String 上次造实例原因 = "?";

    /** 该类的父类链里是否含必须 native 初始化的类（决定是否逐方法打点）。 */
    private static boolean 有native父类(String fqn) {
        try {
            for (Class<?> p = Class.forName(fqn); p != null; p = p.getSuperclass()) {
                if (需native(p)) return true;
            }
        } catch (Throwable ignore) {}
        return false;
    }

    // ------------------------------------------------------------------ 实例化

    private Object 造实例(String fqn, JSONArray ctors) {
        Class<?> c;
        try {
            c = Class.forName(fqn);
        } catch (Throwable t) {
            上次造实例原因 = "类加载失败";
            return null;
        }
        boolean 抽象 = Modifier.isAbstract(c.getModifiers());
        boolean 安全 = 不安全 != null && 不安全可用(c);
        boolean 有构造器 = false, 构造器抛异常 = false, 参数合成失败 = false;
        // 顺序：**先走真实构造器**（有 native 字段的类靠 super() 完成 native init），
        // 只有构造器全失败才退到 Unsafe（Unsafe 只对纯 Java 父类链安全）。
        if (ctors != null) {
            for (int i = 0; i < ctors.length(); i++) {
                JSONArray ps = ctors.optJSONArray(i);
                if (ps == null) continue;
                Class<?>[] types = new Class<?>[ps.length()];
                Object[] args = new Object[ps.length()];
                boolean ok = true;
                for (int k = 0; k < ps.length(); k++) {
                    types[k] = 类(ps.optString(k));
                    if (types[k] == null) { ok = false; break; }
                    args[k] = 合成(types[k]);
                    if (args[k] == null && !types[k].isPrimitive()) { ok = false; break; }
                }
                if (!ok) { 参数合成失败 = true; continue; }
                有构造器 = true;
                try {
                    Constructor<?> ctor = c.getDeclaredConstructor(types);
                    ctor.setAccessible(true);
                    return ctor.newInstance(args);
                } catch (Throwable ignore) {
                    构造器抛异常 = true;
                }
            }
        }
        // 兜底：Unsafe.allocateInstance 绕过构造器。
        // **两个硬前提**：
        // 1) 非 abstract —— CheckJNI 的 AllocObject 对 abstract 类会直接 abort
        //    （不是抛异常，是 SIGABRT，Java 兜底抓不到，整个驱动进程死）。
        // 2) 父类链安全 —— Activity/Context/Drawable/View 等 native 字段未初始化，
        //    一用就 SIGSEGV。
        if (安全 && !抽象) {
            try {
                Object o = 不安全.getClass().getMethod("allocateInstance", Class.class)
                        .invoke(不安全, c);
                if (o != null) return o;
            } catch (Throwable ignore) {
            }
        }
        // 记录失败原因（写进未覆盖明细）
        上次造实例原因 = 抽象 ? (安全 ? "abstract" : "abstract+native父类")
                : !安全 ? "native父类"
                : 构造器抛异常 ? "构造器抛异常"
                : 参数合成失败 ? "构造器参数无法合成"
                : !有构造器 ? "无公开构造器"
                : "未知";
        return null;
    }

    /** 必须 native 初始化、Unsafe 绕构造器后一用就 SIGSEGV 的类前缀。 */
    private static boolean 需native(Class<?> c) {
        String n = c.getName();
        // 例外：这几个包在 framework 里是**纯 Java** 实现（无 native 句柄），
        // Unsafe 造出来可正常用：动画类、BaseAdapter。
        if (n.startsWith("android.view.animation.") || n.startsWith("android.animation.")
                || n.equals("android.widget.BaseAdapter")) {
            return false;
        }
        return n.startsWith("android.app.") || n.startsWith("android.content.Context")
                || n.startsWith("android.graphics.") || n.startsWith("android.view.")
                || n.startsWith("android.widget.") || n.startsWith("android.content.res.")
                || n.startsWith("androidx.") || n.startsWith("dalvik.");
    }

    /**
     * Unsafe 对哪些类安全：整条父类链里**没有**必须 native 初始化的类，且自身属于
     * 纯数据族（java./ticode.zh./android.content.pm./android.text./…）。
     *
     * <p>必须两趟扫描：单趟短路会被前缀抢先。例如 `构建路径 extends android.graphics.Path`
     * ——自身名匹配 `ticode.zh.` 会先放行，但它继承了 Path 的 native 字段，
     * Unsafe 造出来一碰就 SIGSEGV。所以 deny 必须先扫完整条链。
     */
    private static boolean 不安全可用(Class<?> c) {
        for (Class<?> p = c; p != null; p = p.getSuperclass()) {
            if (需native(p)) return false;
        }
        for (Class<?> p = c; p != null; p = p.getSuperclass()) {
            String n = p.getName();
            if (n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("org.json")
                    || n.startsWith("ticode.zh.") || n.startsWith("android.content.pm.")
                    || n.startsWith("android.content.IntentFilter") || n.startsWith("android.text.")) {
                return true;
            }
        }
        return false;
    }

    /** sun.misc.Unsafe 句柄（allocateInstance 绕构造器）。 */
    private static final Object 不安全 = 取不安全();

    private static Object 取不安全() {
        try {
            Class<?> uc = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field f = uc.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 调方法

    /** 调用方法。返回 null = 已执行；否则返回未执行原因（写进未覆盖明细）。 */
    private String 调方法(String fqn, String name, JSONArray ps, Object target) {
        Class<?> c;
        try {
            c = Class.forName(fqn);
        } catch (Throwable t) {
            return "类加载失败";
        }
        int n = ps == null ? 0 : ps.length();
        Class<?>[] types = new Class<?>[n];
        Object[] args = new Object[n];
        for (int k = 0; k < n; k++) {
            String tn = ps.optString(k);
            types[k] = 类(tn);
            if (types[k] == null) return "参数类型未解析:" + tn;
            args[k] = 合成(types[k]);
            if (args[k] == null && !types[k].isPrimitive()) return "参数无法合成:" + types[k].getSimpleName();
        }
        Method m = null;
        try {
            m = c.getDeclaredMethod(name, types);
        } catch (Throwable ignore) {
        }
        if (m == null) {
            // 按名字 + 参数个数兜底（manifest 类型名与真实签名可能不完全一致）
            for (Method cand : c.getDeclaredMethods()) {
                if (cand.getName().equals(name) && cand.getParameterTypes().length == n) {
                    m = cand;
                    break;
                }
            }
        }
        if (m == null) return "方法未找到";
        m.setAccessible(true);
        final Method mm = m;
        final Object tgt = target;
        final Object[] ar = args;
        // 超时保护：有些方法（如 SurfaceView.run 的 while(true)）永不返回，
        // 必须隔离在独立线程并限时，否则整个驱动挂死。
        final Throwable[] caught = new Throwable[1];
        Thread th = new Thread(() -> {
            try {
                mm.invoke(tgt, ar);
            } catch (Throwable t) {
                caught[0] = t.getCause() != null ? t.getCause() : t;
            }
        });
        th.setDaemon(true);
        th.start();
        try {
            th.join(300);   // 300ms 上限
        } catch (Throwable ignore) {
        }
        if (th.isAlive()) {
            // 超时：算"执行过"（确实进了方法体），但要记录
            超时++;
            异常明细.add(fqn + "#" + name + "  → TIMEOUT(>300ms)");
            return null;
        }
        if (caught[0] != null) {
            抛异常++;
            异常明细.add(fqn + "#" + name + "  → " + caught[0].getClass().getSimpleName()
                    + ": " + String.valueOf(caught[0].getMessage()));
        }
        return null;
    }

    // ------------------------------------------------------------------ 类型/值合成

    /** 类型名 → Class（支持基本类型、数组、泛型擦除、java.*、android.*、ticode.zh.*） */
    private Class<?> 类(String tn) {
        if (tn == null || tn.isEmpty()) return null;
        tn = tn.trim();
        // 泛型擦除：List<String> → List、ValueCallback<Uri[]> → ValueCallback、Class<?> → Class
        int lt = tn.indexOf('<');
        if (lt >= 0) tn = tn.substring(0, lt).trim();
        tn = tn.replace("...", "[]");
        if (tn.startsWith("final ")) tn = tn.substring(6).trim();
        int dim = 0;
        while (tn.endsWith("[]")) { dim++; tn = tn.substring(0, tn.length() - 2).trim(); }
        Class<?> base;
        switch (tn) {
            case "int": base = int.class; break;
            case "long": base = long.class; break;
            case "short": base = short.class; break;
            case "byte": base = byte.class; break;
            case "char": base = char.class; break;
            case "boolean": base = boolean.class; break;
            case "float": base = float.class; break;
            case "double": base = double.class; break;
            case "void": base = void.class; break;
            default:
                // 泛型类型变量：T、T1、E、K、V、R… → 擦除为 Object
                if (tn.matches("[A-Z][0-9]?") || tn.matches("T[0-9]+")) base = Object.class;
                else base = 查类(tn);
        }
        if (base == null) return null;
        for (int i = 0; i < dim; i++) base = java.lang.reflect.Array.newInstance(base, 0).getClass();
        return base;
    }

    private Class<?> 查类(String tn) {
        // 直接试
        try { return Class.forName(tn); } catch (Throwable ignore) {}
        // java.lang
        try { return Class.forName("java.lang." + tn); } catch (Throwable ignore) {}
        // 嵌套类：android.graphics.Bitmap.Config → android.graphics.Bitmap$Config
        // （javac 的 binary name 用 $；manifest 里是源码写法）
        int dot = tn.lastIndexOf('.');
        if (dot > 0) {
            String nested = tn.substring(0, dot) + "$" + tn.substring(dot + 1);
            try { return Class.forName(nested); } catch (Throwable ignore) {}
        }
        // RecyclerView.ViewHolder / ViewHolder → androidx.recyclerview.widget.RecyclerView$ViewHolder
        if (tn.equals("ViewHolder") || tn.equals("RecyclerView.ViewHolder")) {
            try { return Class.forName("androidx.recyclerview.widget.RecyclerView$ViewHolder"); }
            catch (Throwable ignore) {}
        }
        // android.widget / view / graphics / content / app / os / text / webkit / util
        for (String p : new String[]{"android.widget.", "android.view.", "android.graphics.",
                "android.graphics.drawable.", "android.content.", "android.content.pm.",
                "android.content.res.", "android.app.", "android.os.", "android.text.",
                "android.text.style.", "android.webkit.", "android.util.", "android.net.",
                "android.database.", "android.provider.", "android.animation.", "android.media.",
                "android.hardware.", "android.location.", "android.security.",
                "androidx.recyclerview.widget.", "androidx.appcompat.app.", "androidx.fragment.app.",
                "java.util.", "java.util.zip.", "java.util.regex.", "java.io.", "java.nio.",
                "java.math.", "java.security.", "java.security.cert.", "javax.crypto.",
                "javax.net.ssl.", "org.json.", "org.xmlpull.v1.",
                "ticode.zh.android.", "ticode.zh.jvm.", "ticode.zh.base.", "ticode.zh.meng."}) {
            try { return Class.forName(p + tn); } catch (Throwable ignore) {}
        }
        return null;
    }

    /** 合成一个"真实可用"的值 —— 关键：不传 null，才能进方法主体。 */
    private Object 合成(Class<?> t) {
        if (t == null) return null;
        if (t.isPrimitive()) {
            if (t == int.class) return 1;
            if (t == long.class) return 1L;
            if (t == short.class) return (short) 1;
            if (t == byte.class) return (byte) 1;
            if (t == char.class) return 'a';
            if (t == boolean.class) return Boolean.TRUE;
            if (t == float.class) return 1f;
            if (t == double.class) return 1d;
            return null;
        }
        if (t.isArray()) {
            try { return java.lang.reflect.Array.newInstance(t.getComponentType(), 1); }
            catch (Throwable e) { return null; }
        }
        if (t == String.class || t == CharSequence.class) return "test";
        if (t == Object.class) return "x";
        if (t == Class.class) return String.class;
        // Activity 参数必须给真实 Activity（ctx 是 阻断Context 包装，不是 Activity，
        // 直接传会 IllegalArgumentException）；Context 参数才用阻断包装。
        if (Activity.class.isAssignableFrom(t)) return act;
        if (Context.class.isAssignableFrom(t)) return ctx;
        if (t == View.class) return new View(ctx);
        if (ViewGroup.class.isAssignableFrom(t)) return new FrameLayout(ctx);
        if (t == Bitmap.class) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        if (t == Canvas.class) return new Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888));
        if (t == Paint.class) return new Paint();
        if (t == Path.class) return new Path();
        if (t == Rect.class) return new Rect();
        if (t == RectF.class) return new RectF();
        if (t == Bundle.class) return new Bundle();
        if (t == Uri.class) return Uri.parse("http://x/");
        if (t == File.class) return new File(ctx.getCacheDir(), "cov");
        // 流：仅对 JDK 流类型直接给原生流；ticode 壳（jvm.输入流 extends InputStream）
        // 走构造器递归包一层，否则返回的原生流不是壳类型、invoke 会参数类型不符。
        if (t.getName().startsWith("java.") && InputStream.class.isAssignableFrom(t))
            return new ByteArrayInputStream(new byte[]{1});
        if (t.getName().startsWith("java.") && OutputStream.class.isAssignableFrom(t))
            return new ByteArrayOutputStream();
        // 大数：用可解析的字符串走 (String[,int]) 构造器
        if (Number.class.isAssignableFrom(t) && t != Number.class) {
            for (Constructor<?> ctor : t.getConstructors()) {
                Class<?>[] pts = ctor.getParameterTypes();
                try {
                    if (pts.length == 1 && pts[0] == String.class) {
                        ctor.setAccessible(true);
                        return ctor.newInstance("1");
                    }
                    if (pts.length == 2 && pts[0] == String.class && pts[1] == int.class) {
                        ctor.setAccessible(true);
                        return ctor.newInstance("1", 10);
                    }
                } catch (Throwable ignore) {}
            }
        }
        if (t == Drawable.class) return ctx.getResources().getDrawable(android.R.drawable.ic_menu_info_details);
        if (List.class.isAssignableFrom(t) || t == java.util.Collection.class) return new ArrayList<>();
        if (Set.class.isAssignableFrom(t)) return new HashSet<>();
        if (Map.class.isAssignableFrom(t)) return new HashMap<>();
        // 常见框架类型：直接用公开工厂/构造器造，避免落到"无法合成"
        if (t == android.view.MotionEvent.class)
            return android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 1f, 1f, 0);
        if (t == android.view.KeyEvent.class)
            return new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_A);
        if (t == android.animation.Animator.class)
            return android.animation.ValueAnimator.ofFloat(0f, 1f);
        if (t == android.view.animation.Animation.class)
            return new android.view.animation.AlphaAnimation(0f, 1f);
        if (t == android.content.ComponentName.class)
            return new android.content.ComponentName(ctx, ctx.getClass());
        if (t == android.os.Handler.class)
            return new android.os.Handler(android.os.Looper.getMainLooper());
        if (t == android.graphics.Typeface.class) return android.graphics.Typeface.DEFAULT;
        if (t == android.hardware.Sensor.class) {
            try {
                android.hardware.SensorManager sm =
                        (android.hardware.SensorManager) ctx.getSystemService(android.content.Context.SENSOR_SERVICE);
                return sm == null ? null : sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER);
            } catch (Throwable e) { return null; }
        }
        if (t == android.view.ScaleGestureDetector.class) {
            try {
                return new android.view.ScaleGestureDetector(ctx,
                        new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener());
            } catch (Throwable e) { return null; }
        }
        if (t == Long.class) return Long.valueOf(1L);
        if (t == Integer.class) return Integer.valueOf(1);
        if (t == Float.class) return Float.valueOf(1f);
        if (t == Double.class) return Double.valueOf(1d);
        if (t == Boolean.class) return Boolean.TRUE;
        if (t == Short.class) return (short) 1;
        if (t == Byte.class) return (byte) 1;
        if (t == Character.class) return 'a';
        if (t == java.lang.reflect.Field.class)
            return String.class.getDeclaredFields().length > 0 ? String.class.getDeclaredFields()[0] : null;
        if (t == java.lang.reflect.Method.class) return String.class.getDeclaredMethods()[0];
        if (t == java.lang.reflect.Constructor.class) return String.class.getDeclaredConstructors()[0];
        if (t == java.lang.reflect.Parameter.class) {
            Method sm = String.class.getDeclaredMethods()[0];
            return sm.getParameters().length > 0 ? sm.getParameters()[0] : null;
        }
        // 枚举：取第一个常量（结绳的"常量"多半是枚举参数）
        if (t.isEnum()) {
            Object[] cs = t.getEnumConstants();
            return cs != null && cs.length > 0 ? cs[0] : null;
        }
        if (t.isInterface() || Modifier.isAbstract(t.getModifiers())) {
            // 接口/抽象：从 manifest 里找一个具体子类造出来（带缓存）
            return 找实现(t);
        }
        // 具体类：遍历**所有**公开构造器，逐参数递归合成（限深防环）。
        // 只试固定单参签名会漏掉 大整数(String,int)/坐标(int,int)/安卓资源标识符(Uri) 等。
        return 按构造器造(t);
    }

    /** 构造器递归深度（ThreadLocal：调方法在独立线程跑，避免跨线程污染）。 */
    private final ThreadLocal<Integer> 递归深 = ThreadLocal.withInitial(() -> 0);

    /** 用 t 的任意公开构造器造实例，参数递归合成。 */
    private Object 按构造器造(Class<?> t) {
        if (递归深.get() > 4) return null;
        Constructor<?>[] cs = t.getConstructors();
        // 参数少的优先（更可能造成功）
        java.util.Arrays.sort(cs, (a, b) -> a.getParameterCount() - b.getParameterCount());
        for (Constructor<?> ctor : cs) {
            Class<?>[] pts = ctor.getParameterTypes();
            Object[] a = new Object[pts.length];
            boolean ok = true;
            for (int i = 0; i < pts.length; i++) {
                递归深.set(递归深.get() + 1);
                try {
                    a[i] = 合成(pts[i]);
                } finally {
                    递归深.set(递归深.get() - 1);
                }
                if (a[i] == null && !pts[i].isPrimitive()) { ok = false; break; }
            }
            if (!ok) continue;
            try {
                ctor.setAccessible(true);
                return ctor.newInstance(a);
            } catch (Throwable ignore) {}
        }
        return null;
    }

    /** 接口/抽象参数 → 从 manifest 里找一个可造的具体子类（结果缓存，避免重复全扫）。 */
    private final Map<String, Object> 实现缓存 = new HashMap<>();
    private boolean 正在找实现 = false;

    private Object 找实现(Class<?> t) {
        String key = t.getName();
        if (实现缓存.containsKey(key)) return 实现缓存.get(key);
        if (正在找实现) return null;   // 防重入：造实例→合成→找实现 的递归环
        正在找实现 = true;
        Object result = null;
        for (int i = 0; i < manifest.length() && result == null; i++) {
            JSONObject c = manifest.optJSONObject(i);
            if (c == null || c.optBoolean("abstract")) continue;
            String fqn = c.optString("class");
            if (fqn.equals(key)) continue;
            Class<?> cc;
            try { cc = Class.forName(fqn); } catch (Throwable e) { continue; }
            if (!t.isAssignableFrom(cc)) continue;
            Object o = 造实例(fqn, c.optJSONArray("ctors"));
            if (o != null && t.isInstance(o)) result = o;
        }
        // 常见接口的快速兜底（ticode 的 集合/列表 等壳接口）
        if (result == null) {
            if (t == java.lang.Runnable.class) result = (Runnable) () -> {};
            else if (t == java.lang.CharSequence.class) result = "test";
            else if (t == java.util.Collection.class || t == java.lang.Iterable.class) result = new ArrayList<>();
        }
        // 接口兜底：JDK 动态代理（拦截所有回调方法，返回默认值）。
        // 覆盖 MotionEvent 回调、DialogInterface、SensorEventListener、ViewHolder 等。
        if (result == null && t.isInterface()) {
            try {
                final Class<?> it = t;
                result = java.lang.reflect.Proxy.newProxyInstance(
                        it.getClassLoader(), new Class<?>[]{it},
                        (proxy, method, args) -> 默认返回值(method.getReturnType()));
            } catch (Throwable ignore) {}
        }
        if (result != null) 实现缓存.put(key, result);
        正在找实现 = false;
        return result;
    }

    /** 动态代理拦截回调时的默认返回值。 */
    private static Object 默认返回值(Class<?> rt) {
        if (!rt.isPrimitive() || rt == void.class) return null;
        if (rt == boolean.class) return Boolean.FALSE;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        if (rt == short.class) return (short) 0;
        if (rt == byte.class) return (byte) 0;
        if (rt == char.class) return (char) 0;
        if (rt == float.class) return 0f;
        if (rt == double.class) return 0d;
        return null;
    }

    // ------------------------------------------------------------------ 报告

    public void 输出() {
        sink.line(String.format("覆盖：%d/%d = %.1f%%（未覆盖 %d，抛异常 %d）",
                已执行, 方法总数, 100.0 * 已执行 / Math.max(1, 方法总数), 未覆盖, 抛异常),
                "#1565C0");
        Log.i("TICODE_COV", String.format("COVERAGE %d/%d = %.1f%% uncovered=%d thrown=%d",
                已执行, 方法总数, 100.0 * 已执行 / Math.max(1, 方法总数), 未覆盖, 抛异常));
        // 把未覆盖与异常写入 logcat（便于脚本解析）
        for (String s : 未覆盖明细) Log.i("TICODE_COV", "UNCOVERED " + s);
        for (String s : 异常明细) Log.i("TICODE_COV", "THROWN " + s);
    }

    public List<String> get未覆盖明细() { return 未覆盖明细; }
    public List<String> get异常明细() { return 异常明细; }
}

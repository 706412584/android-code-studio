package com.example.qdtest;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.example.qdtest.tool.工具;
import com.example.qdtest.tool.字符;
import com.example.qdtest.tool.数据;
import com.example.qdtest.tool.文件;
import com.example.qdtest.tool.系统;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import ticode.zh.android.加解密操作;
import ticode.zh.jvm.GZIP操作;
import ticode.zh.jvm.正则表达式;

/**
 * 真机自检页：实例化全部中文控件 + 调用工具库 + 调用 ticode，结果直接显示。
 *
 * <p>用反射实例化控件：这样"全部 44 个控件"是**数据驱动**的，加一个控件只需加个名字，
 * 不会漏。每个控件单独 try/catch —— 一个失败不影响其它，失败信息也留在屏幕上。
 */
public class MainActivity extends Activity {

    /** 全部中文控件（`页面` 是 Activity 基类，不在此列）。 */
    private static final String[] 控件 = {
        "视图", "线性布局", "约束布局", "相对布局", "帧布局", "表格布局", "表格项",
        "文本", "按钮", "输入框", "图像", "图像按钮", "开关", "多选", "单选项", "单选布局",
        "拖动条", "进度条", "评分", "滚动", "水平滚动", "嵌套滚动", "网格视图", "列表",
        "v7列表", "滑动窗体", "垂直滑动窗体", "侧滑窗体", "下拉菜单", "下拉刷新控件",
        "协调布局", "应用栏布局", "工具栏布局", "折叠工具栏布局", "标签布局", "卡片",
        "浮动动作按钮", "文本输入布局", "日期选择器", "时间选择器", "动态图", "视频",
        "浏览器", "面控件",
    };

    private LinearLayout 容器;      // 结果文字区（含汇总）
    private LinearLayout 控件区;    // 折叠的控件画廊
    private TextView 控件切换;      // 展开/收起按钮
    private final List<String> 结果 = new ArrayList<>();
    private int 控件成功 = 0, 控件失败 = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 垂直排列：最上面是汇总与结果文字，控件画廊单独放进一个可折叠区域，
        // 这样首屏就能看到 PASS/FAIL，不会被一堆空控件淹没。
        LinearLayout 根 = new LinearLayout(this);
        根.setOrientation(LinearLayout.VERTICAL);
        根.setPadding(dp(12), dp(12), dp(12), dp(12));
        根.setBackgroundColor(Color.parseColor("#F5F5F5"));

        // 结果区（先建，测完再填；放在最前）
        容器 = new LinearLayout(this);
        容器.setOrientation(LinearLayout.VERTICAL);
        根.addView(容器);

        // 控件画廊（折叠）
        控件区 = new LinearLayout(this);
        控件区.setOrientation(LinearLayout.VERTICAL);
        控件区.setVisibility(View.GONE);
        根.addView(控件区);

        // 「显示/隐藏控件」按钮（最顶）
        控件切换 = new TextView(this);
        控件切换.setText("▶ 点此展开 44 个控件");
        控件切换.setTextSize(15);
        控件切换.setPadding(dp(12), dp(12), dp(12), dp(12));
        控件切换.setBackgroundColor(Color.parseColor("#E1BEE7"));
        控件切换.setTextColor(Color.parseColor("#4A148C"));
        控件切换.setOnClickListener(v -> {
            boolean 显示 = 控件区.getVisibility() == View.GONE;
            控件区.setVisibility(显示 ? View.VISIBLE : View.GONE);
            控件切换.setText(显示 ? "▼ 点此收起控件" : "▶ 点此展开 44 个控件");
        });
        根.addView(控件切换, 0);

        // 1) 逐个实例化控件（加进折叠区）—— 不需要布局，可立即做
        测控件();

        ScrollView scroll = new ScrollView(this);
        scroll.addView(根);
        setContentView(scroll);

        // 2)+3) 工具库与 ticode 必须等**布局完成 + 窗口获得焦点**后再跑：
        //   - 系统.截屏 依赖 root.getWidth()>0（布局后才有）
        //   - 系统.剪切板 在 Android 10 上需窗口有焦点才能读
        // 放在 onWindowFocusChanged 里触发一次（见下）。
    }

    private boolean 已跑 = false;

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || 已跑) {
            return;
        }
        已跑 = true;

        测工具();
        测Ticode();

        // 汇总插到结果区最前面
        容器.addView(汇总(), 0);
    }

    // ------------------------------------------------------------------ 控件

    private void 测控件() {
        List<String> 失败名 = new ArrayList<>();
        for (String name : 控件) {
            try {
                Class<?> c = Class.forName("com.example.qdtest.ui." + name);
                Constructor<?> ctor = c.getConstructor(Context.class);
                Object o = ctor.newInstance(this);
                if (!(o instanceof View)) {
                    throw new IllegalStateException("不是 View");
                }
                View v = (View) o;
                v.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));
                控件区.addView(v);
                控件成功++;
            } catch (Throwable t) {
                控件失败++;
                失败名.add(name + "(" + t.getClass().getSimpleName() + ")");
            }
        }
        // 只在结果区记一行（避免 44 行刷屏）；失败的逐个列出
        行("① 控件： " + 控件成功 + "/" + (控件成功 + 控件失败) + " 实例化成功",
                控件失败 == 0 ? "#2E7D32" : "#C62828");
        for (String f : 失败名) {
            行("    ✗ " + f, "#C62828");
        }
    }

    // ------------------------------------------------------------------ 工具库

    private void 测工具() {
        标题("② 工具库 tool/（全部 96 个方法）");

        // ---- 字符（21）----
        试("字符.长度", () -> 字符.长度("hello") == 5);
        试("字符.存在", () -> 字符.存在("abc") && !字符.存在(""));
        试("字符.转换大写", () -> "ABC".equals(字符.转换大写("abc")));
        试("字符.转换小写", () -> "abc".equals(字符.转换小写("ABC")));
        试("字符.去除头尾空白", () -> "x".equals(字符.去除头尾空白("  x  ")));
        试("字符.替换", () -> "a-b".equals(字符.替换("a_b", "_", "-")));
        试("字符.替换全部", () -> "a-b-c".equals(字符.替换全部("a_b_c", "_", "-")));
        试("字符.分割", () -> 字符.分割("a,b,c", ",").length == 3);
        试("字符.查询", () -> 字符.查询("abcabc", "b") == 1);
        试("字符.倒查", () -> 字符.倒查("abcabc", "b") == 4);
        试("字符.查开头", () -> 字符.查开头("abc", "a"));
        试("字符.查结尾", () -> 字符.查结尾("abc", "c"));
        试("字符.等于", () -> 字符.等于("a", "a") && !字符.等于("a", "b"));
        试("字符.取出", () -> "ell".equals(字符.取出("hello", 1, 4)));
        试("字符.随机数", () -> { int r = 字符.随机数(10); return r >= 0 && r < 10; });
        试("字符.是否匹配成功", () -> 字符.是否匹配成功("a1", "\\d"));
        试("字符.匹配", () -> "1".equals(字符.匹配("a1", "\\d")));
        试("字符.匹配组", () -> "a".equals(字符.匹配组("ab", "(a)", 1)));
        试("字符.json解析", () -> 字符.json解析("{\"a\":1}").optInt("a") == 1);
        试("字符.json数组解析", () -> 字符.json数组解析("[1,2,3]").length() == 3);
        试("字符.json取字符串", () ->
                 "v".equals(字符.json取字符串(字符.json解析("{\"k\":\"v\"}"), "k", "?")));

        // ---- 数据（20）----
        试("数据.dp转px", () -> 数据.dp转px(this, 16) > 0);
        试("数据.px转dp", () -> 数据.px转dp(this, 数据.dp转px(this, 16)) > 0);
        试("数据.px转sp", () -> 数据.px转sp(this, 数据.sp转px(this, 16)) > 0);
        试("数据.sp转px", () -> 数据.sp转px(this, 16) > 0);
        试("数据.转字符串", () -> "7".equals(数据.转字符串(7)));
        试("数据.转整型", () -> 数据.转整型("42") == 42);
        试("数据.转长整", () -> 数据.转长整("42") == 42L);
        试("数据.转双精小数", () -> 数据.转双精小数("1.5") == 1.5);
        试("数据.转小数", () -> 数据.转小数("1.5") == 1.5f);
        试("数据.转是否", () -> 数据.转是否(true));
        试("数据.转颜色", () -> 数据.转颜色("#FF0000") == 0xFFFF0000);
        试("数据.转字节组", () -> 数据.转字节组("abc").length == 3);
        // SQLite（用临时库）
        试("数据.创建数据库", () -> 数据.创建数据库(this, "自检.db") != null);
        试("数据.创建数据表+存在数据表", () -> {
            android.database.sqlite.SQLiteDatabase db = 数据.创建数据库(this, "自检.db");
            数据.创建数据表(db, "CREATE TABLE IF NOT EXISTS t(id INTEGER)");
            boolean e = 数据.存在数据表(db, "t");
            数据.释放数据库(db);
            return e;
        });
        试("数据.更新数据+查询行数", () -> {
            android.database.sqlite.SQLiteDatabase db = 数据.创建数据库(this, "自检.db");
            数据.更新数据(db, "INSERT INTO t(id) VALUES(1)");
            int n = 数据.查询行数(db, "t");
            数据.释放数据库(db);
            return n >= 1;
        });
        试("数据.删除数据表", () -> {
            android.database.sqlite.SQLiteDatabase db = 数据.创建数据库(this, "自检.db");
            数据.删除数据表(db, "t");
            boolean gone = !数据.存在数据表(db, "t");
            数据.释放数据库(db);
            return gone;
        });
        试("数据.删除数据库", () -> 数据.删除数据库(this, "自检.db"));

        // ---- 文件（18，用 filesDir 临时路径）----
        String 目录 = getFilesDir().getAbsolutePath() + "/自检";
        String 文本 = 目录 + "/a.txt";
        String 字节 = 目录 + "/b.bin";
        String 复制到 = 目录 + "/c.txt";
        String zip = 目录 + "/d.zip";
        试("文件.文件", () -> 文件.文件(文本) != null);
        试("文件.写入文本", () -> 文件.写入文本(文本, "hello 文件"));
        试("文件.存在", () -> 文件.存在(文本));
        试("文件.读取文本", () -> "hello 文件".equals(文件.读取文本(文本)));
        试("文件.大小", () -> 文件.大小(文本) > 0);
        试("文件.类型", () -> 文件.类型(文本) != null);
        试("文件.写入字节数组", () -> 文件.写入字节数组(字节, new byte[]{1, 2, 3}));
        试("文件.读取字节数组", () -> 文件.读取字节数组(字节).length == 3);
        试("文件.复制", () -> 文件.复制(文本, 复制到) && 文件.存在(复制到));
        试("文件.目录", () -> 文件.目录(目录));
        试("文件.列表", () -> 文件.列表(目录).length >= 2);
        试("文件.查找文件", () -> 文件.查找文件(目录, "a.txt") != null);
        试("文件.获取目录", () -> 目录.equals(文件.获取目录(文本)));
        试("文件.压缩", () -> 文件.压缩(文本, zip) && 文件.存在(zip));
        试("文件.解压", () -> 文件.解压(zip, 目录 + "/解压") && 文件.存在(目录 + "/解压/a.txt"));
        试("文件.转移", () -> 文件.转移(复制到, 目录 + "/e.txt") && 文件.存在(目录 + "/e.txt"));
        试("文件.删除", () -> 文件.删除(目录 + "/e.txt") && !文件.存在(目录 + "/e.txt"));
        试("文件.打开", () -> { 文件.打开(this, 文本); return true; });  // 触发 Intent，无返回值

        // ---- 系统（16；跳过 打开应用/卸载应用 —— 会打断测试）----
        试("系统.品牌", () -> !系统.品牌().isEmpty());
        试("系统.型号", () -> !系统.型号().isEmpty());
        试("系统.系统版本", () -> !系统.系统版本().isEmpty());
        试("系统.宽", () -> 系统.宽(this) > 0);
        试("系统.高", () -> 系统.高(this) > 0);
        试("系统.cpu型号", () -> 系统.cpu型号() != null);
        试("系统.包名", () -> "com.example.qdtest".equals(系统.包名(this)));
        试("系统.包信息", () -> 系统.包信息(this, "com.example.qdtest") != null);
        试("系统.应用列表", () -> !系统.应用列表(this).isEmpty());
        试("系统.剪切板写入", () -> { 系统.剪切板写入(this, "clip-自检"); return true; });
        // 读剪切板在 Android 10 上要求窗口有焦点；onWindowFocusChanged 里已满足。
        试("系统.剪切板获取", () -> "clip-自检".equals(系统.剪切板获取(this)));
        试("系统.震动", () -> { 系统.震动(this, 50); return true; });
        试("系统.截屏", () -> 系统.截屏(this) != null);
        试("系统.截屏保存", () -> {
            String p = 系统.截屏保存(this, 目录 + "/shot.png");
            return p != null && 文件.存在(p);
        });
        行("   （跳过：系统.打开应用 / 系统.卸载应用 —— 会打断自检）", "#9E9E9E");

        // ---- 工具（21；动画/线程/通知；跳过 媒体播放 —— 需真实音视频文件）----
        View v = new View(this);
        容器.addView(v);
        试("工具.位置移动", () -> { 工具.位置移动(v, 0, 50, 0, 0, 100); return true; });
        试("工具.淡入", () -> { 工具.淡入(v, 100); return true; });
        试("工具.淡出", () -> { 工具.淡出(v, 100); return true; });
        试("工具.透明度", () -> { 工具.透明度(v, 0, 1, 100); return true; });
        试("工具.动画背景", () -> { 工具.动画背景(v, android.R.color.holo_blue_light); return true; });
        试("工具.提示(Toast)", () -> { 工具.提示(this, "自检 Toast"); return true; });
        试("工具.发通知", () -> { 工具.发通知(this, "自检", "通知正文"); return true; });
        试("工具.线程", () -> {
            final boolean[] ran = {false};
            工具.线程(() -> ran[0] = true);
            Thread.sleep(200);
            return ran[0];
        });
        // 延迟执行：不能在主线程 sleep 等（会把 Handler 要跑的 main looper 堵死）。
        // 改为「排队后由该 Handler 自己回调」，回调里把结果写到屏幕。
        工具.延迟执行(
                () -> 行("✓ 工具.延迟执行（异步回调已触发）", "#2E7D32"), 300);
        行("… 工具.延迟执行 已排队，300ms 后异步回调", "#9E9E9E");
        试("工具.在主线程中", () -> 工具.在主线程中());
        行("   （跳过：工具.媒体播放 及 5 个媒体控制 —— 需真实音视频文件）", "#9E9E9E");
        行("   （跳过：工具.显示桌面 —— 会切走自检界面）", "#9E9E9E");
    }

    // ------------------------------------------------------------------ ticode

    private void 测Ticode() {
        标题("③ ticode（结绳移植库）");
        试("加解密.MD5(\"abc\")", () ->
                "900150983cd24fb0d6963f7d28e17f72".equals(加解密操作.MD5加密("abc", "UTF-8")));
        试("加解密.Base64(\"hello\")", () ->
                "aGVsbG8=".equals(加解密操作.Base64编码("hello", "UTF-8", 加解密操作.Base64编码集)));
        试("GZIP 往返", () -> {
            try {
                byte[] gz = GZIP操作.压缩字节集("ticode".getBytes("UTF-8"));
                return "ticode".equals(new String(GZIP操作.解压字节集(gz), "UTF-8"));
            } catch (Exception e) {
                return false;
            }
        });
        试("正则.正则匹配", () -> 正则表达式.正则匹配("a1b2", "\\d", 0).length == 2);
    }

    // ------------------------------------------------------------------ 辅助

    /** 一个断言：`check.run()==true` 记 PASS，抛异常记 FAIL。 */
    private void 试(String name, Check check) {
        try {
            boolean ok = check.run();
            行((ok ? "✓ " : "✗ ") + name, ok ? "#2E7D32" : "#C62828");
        } catch (Throwable t) {
            行("✗ " + name + " —— " + t, "#C62828");
        }
    }

    private interface Check {
        boolean run() throws Exception;
    }

    private void 标题(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(17);
        t.setPadding(0, dp(16), 0, dp(6));
        t.setTextColor(Color.parseColor("#4A148C"));
        容器.addView(t);
    }

    private void 行(String s, String color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setPadding(0, dp(2), 0, dp(2));
        t.setTextColor(Color.parseColor(color));
        容器.addView(t);
        结果.add(s);
    }

    private TextView 汇总() {
        int pass = 0, fail = 0;
        for (String s : 结果) {
            if (s.startsWith("✓")) pass++;
            else if (s.startsWith("✗")) fail++;
        }
        TextView t = new TextView(this);
        t.setText("自检汇总：PASS " + pass + "  FAIL " + fail
                + "\n控件 " + 控件成功 + "/" + (控件成功 + 控件失败));
        t.setTextSize(20);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(16), 0, dp(16));
        t.setBackgroundColor(fail == 0 ? Color.parseColor("#C8E6C9") : Color.parseColor("#FFCDD2"));
        t.setTextColor(Color.parseColor("#000000"));
        return t;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}

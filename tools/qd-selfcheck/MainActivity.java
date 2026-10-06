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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * ticode 控件可用性对比测试。
 *
 * <p>目的：回答「ticode 的 UI 层到底留不留」。与 `ui/`（QuickDevelop 自研控件）对比——
 * ticode 的控件是**包装模式**（对象本身不是 View，通过 getView() 暴露原生 View），
 * 与 `ui/` 的「直接继承 FrameLayout」完全不同。这里逐个实例化 ticode 控件，
 * 调 getView() 拿到 View 加进布局，验证它在真机上是否真能用。
 */
public class MainActivity extends Activity {

    /** ticode 里可当 View 用的具体控件（非抽象 + Context 构造 + getView()）。 */
    private static final String[] TICODE_控件 = {
        "多选框", "宫格列表框", "宫格布局", "工具栏", "开关", "拖动条", "按钮", "文本框",
        "未知组件", "浏览框", "画板", "相对布局", "空布局", "约束布局", "线性布局",
        "自适应布局", "表层画板", "视频播放器", "进度条", "弹性布局", "高级列表框",
    };

    /** ui/（自研）的 44 个控件，作对照。 */
    private static final String[] UI_控件 = {
        "视图", "线性布局", "约束布局", "相对布局", "帧布局", "表格布局", "表格项",
        "文本", "按钮", "输入框", "图像", "图像按钮", "开关", "多选", "单选项", "单选布局",
        "拖动条", "进度条", "评分", "滚动", "水平滚动", "嵌套滚动", "网格视图", "列表",
        "v7列表", "滑动窗体", "垂直滑动窗体", "侧滑窗体", "下拉菜单", "下拉刷新控件",
        "协调布局", "应用栏布局", "工具栏布局", "折叠工具栏布局", "标签布局", "卡片",
        "浮动动作按钮", "文本输入布局", "日期选择器", "时间选择器", "动态图", "视频",
        "浏览器", "面控件",
    };

    private LinearLayout 容器;
    private LinearLayout 控件区;
    private TextView 控件切换;
    private final List<String> 结果 = new ArrayList<>();
    private boolean 已跑 = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout 根 = new LinearLayout(this);
        根.setOrientation(LinearLayout.VERTICAL);
        根.setPadding(dp(12), dp(12), dp(12), dp(12));
        根.setBackgroundColor(Color.parseColor("#F5F5F5"));

        容器 = new LinearLayout(this);
        容器.setOrientation(LinearLayout.VERTICAL);
        根.addView(容器);

        控件区 = new LinearLayout(this);
        控件区.setOrientation(LinearLayout.VERTICAL);
        控件区.setVisibility(View.GONE);
        根.addView(控件区);

        控件切换 = new TextView(this);
        控件切换.setText("▶ 展开控件画廊（ticode 21 + ui 44）");
        控件切换.setTextSize(15);
        控件切换.setPadding(dp(12), dp(12), dp(12), dp(12));
        控件切换.setBackgroundColor(Color.parseColor("#E1BEE7"));
        控件切换.setTextColor(Color.parseColor("#4A148C"));
        控件切换.setOnClickListener(v -> {
            boolean 显示 = 控件区.getVisibility() == View.GONE;
            控件区.setVisibility(显示 ? View.VISIBLE : View.GONE);
            控件切换.setText(显示 ? "▼ 收起控件" : "▶ 展开控件画廊（ticode 21 + ui 44）");
        });
        根.addView(控件切换, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(根);
        setContentView(scroll);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || 已跑) {
            return;
        }
        已跑 = true;

        测Ticode控件();
        测Ui控件();
        容器.addView(汇总(), 0);
    }

    // ------------------------------------------------ ticode 控件（包装模式）

    private void 测Ticode控件() {
        标题("① ticode 控件（包装模式：对象→getView()→View）");
        int ok = 0, fail = 0;
        for (String name : TICODE_控件) {
            try {
                String 包 = name.equals("弹性布局") || name.equals("高级列表框") ? "ticode.zh.meng." : "ticode.zh.android.";
                Class<?> c = Class.forName(包 + name);
                Constructor<?> ctor = c.getConstructor(Context.class);
                Object 控件 = ctor.newInstance(this);

                // 关键：调 getView() 拿到原生 View
                Method gv = c.getMethod("getView");
                Object v = gv.invoke(控件);
                if (!(v instanceof View)) {
                    throw new IllegalStateException("getView() 未返回 View: "
                            + (v == null ? "null" : v.getClass().getName()));
                }
                View view = (View) v;
                view.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));
                控件区.addView(view);
                ok++;
                行("✓ " + name + "  → " + view.getClass().getSimpleName(), "#2E7D32");
            } catch (Throwable t) {
                fail++;
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                行("✗ " + name + "  —— " + cause.getClass().getSimpleName()
                        + ": " + cause.getMessage(), "#C62828");
            }
        }
        行("ticode 控件： " + ok + "/" + (ok + fail) + " 可用", fail == 0 ? "#2E7D32" : "#C62828");
    }

    // ------------------------------------------------ ui/ 控件（自研，对照）

    private void 测Ui控件() {
        标题("② ui/ 控件（自研：直接继承 FrameLayout）");
        int ok = 0, fail = 0;
        for (String name : UI_控件) {
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
                ok++;
            } catch (Throwable t) {
                行("✗ " + name + "  —— " + t, "#C62828");
                fail++;
            }
        }
        行("ui/ 控件： " + ok + "/" + (ok + fail) + " 可用", fail == 0 ? "#2E7D32" : "#C62828");
    }

    // ------------------------------------------------ 辅助

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
        t.setText("对比汇总：PASS " + pass + "  FAIL " + fail);
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

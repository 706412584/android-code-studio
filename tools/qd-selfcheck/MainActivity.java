package com.example.qdtest;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import ticode.zh.meng.弹性布局;
import ticode.zh.meng.线性布局管理器;
import ticode.zh.meng.高级列表框;
import ticode.zh.meng.高级适配器;

/**
 * 第二轮：跑「框架回调 + 交互」路径——这些只有真交互才触发，传 null 永远测不到。
 *
 * <p>重点验证：`getTag()` 系列强转、`meng` 包（上一轮覆盖 1.4%）、
 * 以及刚修的 abstract 壳强转。
 */
public class MainActivity extends Activity {

    private LinearLayout 容器;
    private final List<String> 结果 = new ArrayList<>();

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

        ScrollView scroll = new ScrollView(this);
        scroll.addView(根);
        setContentView(scroll);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || 容器.getChildCount() > 0) {
            return;
        }

        测刚修的3处();
        测getTag系列();
        测meng包();
        测适配器真实回调();
        容器.addView(汇总(), 0);
    }

    // ---------------------------------------------------------- 刚修的 3 处

    private void 测刚修的3处() {
        标题("① 刚修的 abstract 壳强转（原必崩）");
        try {
            android.app.Application app = getApplication();
            行("✓ getApplication() → " + (app != null ? app.getClass().getSimpleName() : "null"),
                    app != null ? "#2E7D32" : "#C62828");
        } catch (Throwable t) {
            行("✗ getApplication 抛: " + t, "#C62828");
        }

        try {
            弹性布局 f = new 弹性布局(this);
            Object v = f.分割线_纵向();
            Object h = f.分割线_横向();
            行("✓ 弹性布局.分割线_纵向() → " + (v == null ? "null(未设)" : v.getClass().getSimpleName()),
                    "#2E7D32");
            行("✓ 弹性布局.分割线_横向() → " + (h == null ? "null(未设)" : h.getClass().getSimpleName()),
                    "#2E7D32");
        } catch (Throwable t) {
            行("✗ 弹性布局.分割线 抛: " + t, "#C62828");
        }
    }

    // ---------------------------------------------------------- getTag 系列

    private void 测getTag系列() {
        标题("② getTag() 系列强转（框架回调才触发）");

        try {
            ticode.zh.android.文本框 文本框 = new ticode.zh.android.文本框(this);
            View v = 文本框.getView();
            Object 无key = v.getTag();
            Object 有key = v.getTag(-101);
            行("文本框 view.getTag()（无key）→ " + (无key == null ? "null" : 无key.getClass().getSimpleName()),
                    无key != null ? "#2E7D32" : "#C62828");
            行("文本框 view.getTag(组件容器.ID) → " + (有key == null ? "null" : 有key.getClass().getSimpleName()),
                    "#9E9E9E");
        } catch (Throwable t) {
            行("✗ 文本框 getTag 抛: " + t, "#C62828");
        }

        try {
            ticode.zh.android.组件容器 容器2 = new ticode.zh.android.组件容器(this);
            View rv = 容器2.getLayout().getView();
            Object 无key = rv.getTag();
            Object 有key = rv.getTag(-101);
            行("组件容器 root.getTag()（无key）→ " + (无key == null ? "null" : 无key.getClass().getSimpleName()),
                    无key != null ? "#2E7D32" : "#C62828");
            行("组件容器 root.getTag(ID) → " + (有key == null ? "null" : 有key.getClass().getSimpleName()),
                    有key != null ? "#2E7D32" : "#C62828");
        } catch (Throwable t) {
            行("✗ 组件容器 getTag 抛: " + t, "#C62828");
        }
    }

    // ---------------------------------------------------------- meng 包

    private void 测meng包() {
        标题("③ meng 包（上一轮覆盖 1.4%）");
        试("线性布局管理器 构造", () -> new 线性布局管理器(this) != null);
        试("高级列表框 构造 + getView()", () -> {
            高级列表框 l = new 高级列表框(this);
            return l.getView() != null;
        });
        试("高级适配器 构造", () -> new 高级适配器() != null);
        试("弹性布局 构造 + 子视图数量()", () -> {
            弹性布局 f = new 弹性布局(this);
            return f.子视图数量() == 0;
        });
        试("弹性布局.主轴方向(行)", () -> {
            弹性布局 f = new 弹性布局(this);
            f.主轴方向(0);
            return f.主轴方向() == 0;
        });
        试("弹性布局.换行策略(不换行)", () -> {
            弹性布局 f = new 弹性布局(this);
            f.换行策略(0);
            return f.换行策略() == 0;
        });
        试("弹性布局.主轴对齐方式", () -> {
            弹性布局 f = new 弹性布局(this);
            f.主轴对齐方式(0);
            return f.主轴对齐方式() == 0;
        });
    }

    // ---------------------------------------------------------- 适配器真实回调

    private void 测适配器真实回调() {
        标题("④ 适配器 + ListView 真实交互（触发 getView）");

        try {
            ListView lv = new ListView(this);
            lv.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(200)));
            容器.addView(lv);

            // 必须覆写 加载布局（它是给用户的事件）：库内默认返回 null，
            // 未覆写时 getView 拿不到布局 → ListView 内部 NPE。这是设计约束，非 bug。
            ticode.zh.android.简单适配器 适配器 = new ticode.zh.android.简单适配器() {
                @Override
                public ticode.zh.android.组件容器 加载布局(int 索引, ticode.zh.android.组件容器 项目布局) {
                    ticode.zh.android.组件容器 c = new ticode.zh.android.组件容器(MainActivity.this);
                    ticode.zh.android.文本框 t = new ticode.zh.android.文本框(MainActivity.this);
                    t.内容("项目 " + 索引);
                    c.getLayout().addComponent(t);
                    return c;
                }
            };
            适配器.项目数 = 5;
            lv.setAdapter(适配器);

            lv.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(200), View.MeasureSpec.EXACTLY));
            lv.layout(0, 0, 1080, dp(200));

            行("✓ ListView + ticode 简单适配器 布局完成（getView 已回调）", "#2E7D32");
        } catch (Throwable t) {
            行("✗ 适配器交互抛: " + t.getClass().getSimpleName() + ": " + t.getMessage(), "#C62828");
        }

        try {
            ticode.zh.android.通用适配器 a = new ticode.zh.android.通用适配器();
            View 复用view = new View(this);
            复用view.setTag(new ticode.zh.android.文本框(this));
            a.getView(0, 复用view, null);
            行("✓ 通用适配器.getView(复用view) 未崩", "#2E7D32");
        } catch (Throwable t) {
            行("✗ 通用适配器.getView(复用view) 抛: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage(), "#C62828");
        }
    }

    // ---------------------------------------------------------- 辅助

    private void 试(String name, Check c) {
        try {
            boolean ok = c.run();
            行((ok ? "✓ " : "✗ ") + name, ok ? "#2E7D32" : "#C62828");
        } catch (Throwable t) {
            行("✗ " + name + " —— " + t.getClass().getSimpleName() + ": " + t.getMessage(), "#C62828");
        }
    }

    private interface Check {
        boolean run() throws Exception;
    }

    private void 标题(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(16);
        t.setPadding(0, dp(14), 0, dp(6));
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
        t.setText("交互测试：PASS " + pass + "  FAIL " + fail);
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

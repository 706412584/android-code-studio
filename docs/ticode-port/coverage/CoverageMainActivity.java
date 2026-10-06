package com.example.qdtest;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 覆盖率驱动入口：后台线程跑（避免阻塞 UI / 被跳转打断），结果写文件 + logcat + 屏幕。
 *
 * <p>刻意继承 {@code ticode.zh.android.安卓窗口}（而非 android.app.Activity）：
 * 这样本 Activity 实例就是 安卓窗口 的真实子类实例，驱动可用它直接覆盖
 * 安卓窗口 的 48 个实例方法（否则该 abstract 类永远造不出实例）。
 */
public class MainActivity extends ticode.zh.android.安卓窗口 {

    private TextView 状态;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 关键：装兜底 UncaughtExceptionHandler。
        // Unsafe 造出的实例字段未初始化，某些方法会在**后台线程**抛异常；
        // 不兜底的话后台线程异常会直接杀掉进程，驱动跑不完。
        final Thread.UncaughtExceptionHandler 原 = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                Log.w("TICODE_COV", "BG-EXC in " + t.getName() + ": " + e));

        LinearLayout 根 = new LinearLayout(this);
        根.setOrientation(LinearLayout.VERTICAL);
        根.setPadding(dp(12), dp(12), dp(12), dp(12));
        状态 = new TextView(this);
        状态.setTextSize(14);
        状态.setText("跑覆盖中…");
        根.addView(状态);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(根);
        setContentView(scroll);

        final String json = 读资产("ticode-manifest.json");
        Log.i("TICODE_COV", "manifest classes=" + json.length());

        new Thread(() -> {
            try {
                final StringBuilder sb = new StringBuilder();
                CoverageDriver d = new CoverageDriver(this, (s, c) -> sb.append(s).append('\n'), json);
                d.run();
                d.输出();
                String 摘要 = String.format("覆盖率 %d/%d = %.1f%%  未覆盖 %d  抛异常 %d",
                        d.已执行, d.方法总数,
                        100.0 * d.已执行 / Math.max(1, d.方法总数), d.未覆盖, d.抛异常);
                // 写文件（最可靠，adb pull 取）
                File out = new File(getExternalFilesDir(null), "coverage.txt");
                try (FileOutputStream fo = new FileOutputStream(out)) {
                    fo.write(摘要.getBytes(StandardCharsets.UTF_8));
                    fo.write("\n\n=== UNCOVERED ===\n".getBytes(StandardCharsets.UTF_8));
                    for (String s : d.get未覆盖明细()) fo.write((s + "\n").getBytes(StandardCharsets.UTF_8));
                    fo.write("\n\n=== THROWN ===\n".getBytes(StandardCharsets.UTF_8));
                    for (String s : d.get异常明细()) fo.write((s + "\n").getBytes(StandardCharsets.UTF_8));
                }
                Log.i("TICODE_COV", "DONE " + 摘要 + "  file=" + out.getAbsolutePath());
                final String f = 摘要;
                runOnUiThread(() -> 状态.setText(f + "\n\n" + sb));
            } catch (Throwable t) {
                Log.e("TICODE_COV", "driver failed", t);
            }
        }, "coverage").start();
    }

    private String 读资产(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "[]";
        }
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}

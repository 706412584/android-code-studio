/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.tool;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.appcompat.view.ContextThemeWrapper;
import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.FileToolPathPolicy;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import com.tom.rv2ide.inflater.IView;
import com.tom.rv2ide.inflater.events.OnInflateViewEvent;
import com.tom.rv2ide.inflater.internal.ViewImpl;
import com.tom.rv2ide.resources.R;
import com.tom.rv2ide.uidesigner.utils.BackgroundPreviewExtensionsKt;
import com.tom.rv2ide.uidesigner.utils.UiLayoutInflater;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 布局预览：把 XML 布局渲染成图片回传给模型。
 *
 * <p><b>复用 IDE 现成的设计器基础设施</b>：
 * <ul>
 *   <li>{@link UiLayoutInflater}（继承 {@code LayoutInflaterImpl}）——用**真实控件**（含
 *       手工构造的 Material 组件）按 XML 明文（aaptcompiler 直读，不经 aapt2）inflate，
 *       不需要 Android Studio 的 layoutlib
 *   <li>{@code ViewImpl.applyPreview(...)}——设计器用来渲染背景 / ImageView src / 矢量图 /
 *       M3 组件的同一套逻辑。不调用它的话，布局里的 {@code android:background="@color/x"}
 *       等都不会出现在预览里，图会几乎全白
 * </ul>
 *
 * <p><b>必须在主线程运行</b>：inflate 会构造真实 View 并 `addView`，View 体系有线程亲和性，
 * 在后台线程挂载/测量是未定义行为。工具执行本身在后台线程，因此这里显式切主线程并等待。
 *
 * <p><b>保真度限制（工具描述与结果里都会如实说明）</b>：
 * <ul>
 *   <li>自定义 View、未编译的资源（{@code @drawable/mine}）无法解析，退化为占位/默认样式
 *   <li>需要项目已初始化并能找到该文件所属的 AndroidModule；否则 inflate 抛异常
 *   <li>这是静态渲染，不含运行时数据（RecyclerView 无数据、不含动画与真实字体度量）
 * </ul>
 */
public final class LayoutPreviewTool extends BaseTool {

  private static final Logger log = LoggerFactory.getLogger(LayoutPreviewTool.class);

  /** 默认画布尺寸（px）。接近常见手机宽度，便于模型判断布局形态。 */
  private static final int DEFAULT_WIDTH_PX = 1080;

  /** 画布最大边长，避免超大布局把位图撑爆。 */
  private static final int MAX_SIDE_PX = 2048;

  /** 渲染后写入文件的目录（工作区内，便于后续工具/用户查看）。 */
  private static final String OUTPUT_DIR = "ai-generated/layout-preview";

  private final Context appContext;

  public LayoutPreviewTool(Context context) {
    this.appContext = context.getApplicationContext();
  }

  @Override
  public String getName() {
    return ToolNames.LAYOUT_PREVIEW;
  }

  @Override
  public String getDescription() {
    return "把 XML 布局（res/layout/*.xml）渲染成预览图并返回给模型，用于改完界面后核对效果。"
        + "参数：file_path（必填，布局文件路径）、width（可选，画布宽度 px，默认 "
        + DEFAULT_WIDTH_PX
        + "）。"
        + "注意：这是**静态渲染**——自定义 View、未编译的资源（如 @drawable/自定义）会退化为"
        + "占位或默认样式，RecyclerView 等无运行时数据，因此仅供核对布局结构/间距/大致配色，"
        + "不要据此断言「界面完全正确」。需项目已在编辑器中打开（语言/模块信息就绪）。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.GENERIC;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    return true;
  }

  @Override
  public boolean isConcurrencySafe() {
    // inflate 依赖 inflater 的全局解析状态，并发会互相破坏。
    return false;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "file_path",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "布局文件路径，例如 app/src/main/res/layout/activity_main.xml"))
                .put(
                    "width",
                    new JSONObject()
                        .put("type", "integer")
                        .put("description", "画布宽度 px，默认 " + DEFAULT_WIDTH_PX + "，上限 " + MAX_SIDE_PX)))
        .put("required", new org.json.JSONArray().put("file_path"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String rawPath = input.optString("file_path", "").trim();
    if (rawPath.isEmpty()) {
      return error("file_path 不能为空：请给出要预览的布局文件路径。");
    }

    File file;
    try {
      file = FileToolPathPolicy.resolve(context, rawPath);
    } catch (Exception e) {
      return error("路径无效：" + e.getMessage());
    }
    if (!file.isFile()) {
      return error("文件不存在：" + displayPath(context, file));
    }
    if (!file.getName().toLowerCase(Locale.US).endsWith(".xml")) {
      return error("只支持 XML 布局文件（res/layout/*.xml）：" + displayPath(context, file));
    }

    int width = input.optInt("width", DEFAULT_WIDTH_PX);
    width = Math.max(240, Math.min(MAX_SIDE_PX, width));

    // inflate 与 View 挂载必须在主线程完成。
    final File target = file;
    final int canvasWidth = width;
    final BitmapHolder holder = new BitmapHolder();
    final StringHolder failure = new StringHolder();
    runOnMainThreadAndWait(
        () -> {
          try {
            holder.bitmap = renderLayout(target, canvasWidth);
          } catch (Throwable t) {
            failure.value = describeInflateFailure(t);
            log.warn("布局预览失败: {}", t.getMessage());
          }
        });

    if (failure.value != null) {
      return error(failure.value);
    }
    Bitmap bitmap = holder.bitmap;
    if (bitmap == null) {
      return error("渲染未产出图像（未知原因）。");
    }

    File output;
    byte[] png;
    try {
      png = encodePng(bitmap);
      output = writeOutput(context, target, png);
    } catch (Exception e) {
      return error("预览图保存失败：" + e.getMessage());
    } finally {
      if (!bitmap.isRecycled()) {
        bitmap.recycle();
      }
    }

    String note =
        "布局预览已生成。\n"
            + "文件: " + displayPath(context, target) + "\n"
            + "尺寸: " + bitmap.getWidth() + "x" + bitmap.getHeight() + "\n"
            + "说明: 静态渲染，未编译的资源与自定义 View 会退化为占位/默认样式。\n"
            + "文件路径:\n" + output.getAbsolutePath() + "\n";
    return ToolResult.withImage(
        getName(), note, "image/png", android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP));
  }

  // ---------------------------------------------------------------------------
  // 渲染
  // ---------------------------------------------------------------------------

  /** 在带主题的 Context 上 inflate、渲染到 Bitmap。只在主线程调用。 */
  private Bitmap renderLayout(File file, int canvasWidth) throws Exception {
    Context themed = new ContextThemeWrapper(appContext, R.style.Theme_AndroidIDE);

    // 父容器：inflate 要求一个 ViewGroup。用 FrameLayout 承载布局根节点，
    // 与设计器的 RootWorkspaceView 同构（后者是 internal，跨模块用不了）。
    FrameLayout root = new FrameLayout(themed);
    root.setBackgroundColor(0xFFFFFFFF);

    UiLayoutInflater inflater = new UiLayoutInflater();
    // 对**整棵树**的每个节点应用设计器的预览渲染（背景/图片/矢量图/M3）。
    // 只对根节点调是不够的：子节点的 android:background="@color/x" 等同样不会出现在图里。
    inflater.setInflationEventListener(
        event -> {
          if (event instanceof OnInflateViewEvent) {
            IView view = ((OnInflateViewEvent) event).getData();
            if (view instanceof ViewImpl) {
              BackgroundPreviewExtensionsKt.applyPreview((ViewImpl) view, themed, null, file);
            }
          }
        });

    List<IView> inflated;
    try {
      // inflate(file, parent: ViewGroup) 内部自行解析 module（startParse(file)）并在
      // close() 里清理解析状态，调用方无需手动 startParse/endParse。
      // 父容器是 FrameLayout 并被 wrap，所以根节点会挂到 root 上，无需手动 addView。
      inflated = inflater.inflate(file, root);
      if (inflated.isEmpty()) {
        throw new IllegalStateException("该布局没有可渲染的根节点（是否为空文件？）");
      }
    } finally {
      inflater.setInflationEventListener(null);
      inflater.close();
    }

    int widthSpec = View.MeasureSpec.makeMeasureSpec(canvasWidth, View.MeasureSpec.EXACTLY);
    int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
    root.measure(widthSpec, heightSpec);

    int width = Math.max(1, Math.min(MAX_SIDE_PX, root.getMeasuredWidth()));
    int height = Math.max(1, Math.min(MAX_SIDE_PX, root.getMeasuredHeight()));
    root.layout(0, 0, width, height);

    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    canvas.drawColor(0xFFFFFFFF);
    root.draw(canvas);
    return bitmap;
  }

  /** 把 inflate 失败翻译成模型能据以行动的话。 */
  private static String describeInflateFailure(Throwable t) {
    String raw = t.getMessage() == null ? t.toString() : t.getMessage();
    String lower = raw.toLowerCase(Locale.US);
    if (lower.contains("not initialized") || lower.contains("cannot find module")) {
      return "无法渲染：该布局所属的项目尚未初始化或找不到对应模块。"
          + "请先在编辑器中打开该项目（让 Gradle 项目模型就绪）后再试。\n原始错误：" + raw;
    }
    return "渲染布局失败：" + raw;
  }

  private static byte[] encodePng(Bitmap bitmap) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    return out.toByteArray();
  }

  private File writeOutput(ToolContext context, File source, byte[] png) throws Exception {
    File base;
    String home = context == null ? null : context.getHomePath();
    if (home != null && !home.isEmpty()) {
      base = new File(home, OUTPUT_DIR);
    } else {
      base = new File(appContext.getFilesDir(), OUTPUT_DIR);
    }
    if (!base.exists() && !base.mkdirs() && !base.isDirectory()) {
      throw new Exception("无法创建输出目录: " + base.getAbsolutePath());
    }
    String name = source.getName().replaceAll("\\.xml$", "") + ".png";
    File out = new File(base, name);
    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out, false)) {
      fos.write(png);
    }
    return out;
  }

  private static String displayPath(ToolContext context, File file) {
    try {
      return FileToolPathPolicy.displayPath(context.getHomePath(), file);
    } catch (Exception e) {
      return file.getAbsolutePath();
    }
  }

  /** 在任意线程上跑到主线程执行并等待完成（工具执行在后台线程）。 */
  private static void runOnMainThreadAndWait(Runnable action) {
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
      action.run();
      return;
    }
    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
    new android.os.Handler(android.os.Looper.getMainLooper())
        .post(
            () -> {
              try {
                action.run();
              } finally {
                latch.countDown();
              }
            });
    try {
      if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
        log.warn("主线程渲染超时（30s）");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** 简单可变容器：跨越 lambda 传回位图。 */
  private static final class BitmapHolder {
    Bitmap bitmap;
  }

  private static final class StringHolder {
    String value;
  }
}
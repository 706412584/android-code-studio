/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.templates.android.quickdevelop

/**
 * Quick Develop 模板的源码文本。
 *
 * 生成一套**中文命名**的 UI 组件封装（包名保持 ASCII 的 `<packageId>.ui`，
 * 只有类名与方法是中文），以及一个用这套封装搭界面的 MainActivity。
 *
 * <h3>与 Appv5 的关系</h3>
 *
 * 组件 API 的设计思路借鉴 Appv5（`页面` / `视图` / `线性布局` / `约束布局` /
 * `文本` / `按钮` / `输入框` 的分层与链式设置），但 Appv5 没有 LICENSE，
 * 因此这里**零代码拷贝**——所有实现均为按 Android 官方 API 重写，
 * 只保留「中文命名 + 链式 setter」这一层约定。
 *
 * <h3>为什么全是 Java</h3>
 *
 * 中文类名在 Java 与 Kotlin 里都合法，但本模板刻意只生成 Java：
 * 一是向导默认语言是 Kotlin，若不强制就会生成 Kotlin 源码（见
 * `AtcWizardDialog.createProject` 的强制逻辑）；二是 Java 的
 * `视图`/`线性布局` 继承链不需要处理 Kotlin 的空安全与属性语法差异，
 * 生成的代码更短、更接近「照着改就能用」。
 *
 * <h3>组件分层</h3>
 *
 * - [视图] 继承 `FrameLayout`，是所有容器的基类，提供尺寸/内外边距/背景/圆角/权重
 * - [线性布局] 继承 [视图]，追加 `添加(视图...)` 与 `方向`
 * - [约束布局] 继承 [视图]，只给 `居中` / `铺满` 两个预设（不做完整 DSL）
 * - [文本] 继承 `TextView`，[按钮] 继承 `MaterialButton`，[输入框] 继承 `TextInputEditText`
 * - [页面] 继承 `AppCompatActivity`，抽象方法 `视图 搭建()` 交给子类搭界面
 *
 * 所有组件都有 `static int dp(float)`，方便按 dp 写尺寸。
 */
object QuickDevelopSources {

  /** 生成的组件所在子包的包名后缀。最终包名为 `<packageId>.ui`。 */
  private const val UI_PACKAGE_SUFFIX = "ui"

  private fun uiPackage(packageId: String): String = "$packageId.$UI_PACKAGE_SUFFIX"

  /**
   * 生成 `视图`（FrameLayout 基类）。
   *
   * 之所以不直接继承 `LinearLayout`：`约束布局` 与 `线性布局` 都需要一个
   * 「能设置背景/圆角/内外边距」的共同基类，FrameLayout 恰好支持
   * `setForeground` 之外的常规能力，且不会像 LinearLayout 那样对子 View
   * 的 `layout_weight` 有隐式要求。
   */
  fun viewJava(packageId: String): String =
      """
      package ${uiPackage(packageId)};

      import android.content.Context;
      import android.graphics.Color;
      import android.graphics.drawable.GradientDrawable;
      import android.util.TypedValue;
      import android.view.View;
      import android.view.ViewGroup;
      import android.widget.FrameLayout;

      /**
       * 所有容器的基类。
       *
       * <p>链式设置方法一律返回 {@code this}，因此可以写成
       * {@code new 线性布局(ctx).背景(0xFFFFFFFF).圆角(12).内边距(16);}。
       */
      public class 视图 extends FrameLayout {

          private int 圆角值 = 0;
          private int 背景色 = Color.TRANSPARENT;

          public 视图(Context context) {
              super(context);
          }

          /** dp 转 px。静态方法，任何地方都能用。 */
          public static int dp(float value) {
              return Math.round(
                      TypedValue.applyDimension(
                              TypedValue.COMPLEX_UNIT_DIP,
                              value,
                              android.content.res.Resources.getSystem().getDisplayMetrics()));
          }

          /** 设置纯色背景。 */
          public 视图 背景(int color) {
              背景色 = color;
              应用背景();
              return this;
          }

          /**
           * 设置圆角半径（单位 dp）。
           *
           * <p>用 {@link GradientDrawable} 而不是 {@code ViewOutlineProvider}：
           * 前者在 API 21 以下也能裁剪背景，且不要求调用方自己设置 clipToOutline。
           */
          public 视图 圆角(float radiusDp) {
              圆角值 = dp(radiusDp);
              应用背景();
              return this;
          }

          /** 同时设置内边距（四边一致，单位 dp）。 */
          public 视图 内边距(float paddingDp) {
              int p = dp(paddingDp);
              setPadding(p, p, p, p);
              return this;
          }

          /**
           * 设置外边距（四边一致，单位 dp）。
           *
           * <p>只有父容器给出的 LayoutParams 是 {@code MarginLayoutParams} 时才生效；
           * 视图尚未加入父容器时静默忽略——调用方应在添加进容器后再设置外边距。
           */
          public 视图 外边距(float marginDp) {
              int m = dp(marginDp);
              ViewGroup.LayoutParams params = getLayoutParams();
              if (params instanceof ViewGroup.MarginLayoutParams) {
                  ((ViewGroup.MarginLayoutParams) params).setMargins(m, m, m, m);
                  requestLayout();
              }
              return this;
          }

          /**
           * 设置线性布局里的权重。
           *
           * <p>只有父容器是 {@code LinearLayout} 时才生效；其它情况静默忽略，
           * 避免调用方因为父容器类型不同而崩溃。
           */
          public 视图 权重(float weight) {
              ViewGroup.LayoutParams params = getLayoutParams();
              if (params instanceof android.widget.LinearLayout.LayoutParams) {
                  ((android.widget.LinearLayout.LayoutParams) params).weight = weight;
                  requestLayout();
              }
              return this;
          }

          private void 应用背景() {
              GradientDrawable drawable = new GradientDrawable();
              drawable.setColor(背景色);
              drawable.setCornerRadius(圆角值);
              setBackground(drawable);
          }

          // ==================================================================
          // 静态辅助：供「不继承 视图 的控件」复用同一套样式能力。
          //
          // 表格布局 / 滚动 / 卡片 这类控件的父类已经定死（TableLayout、
          // ScrollView…），无法再继承 视图，但它们同样需要背景/圆角/边距。
          // 与其在几十个类里各抄一份 GradientDrawable 逻辑，不如放这里共用。
          //
          // 读写都走 View 自己的 background：先设背景再设圆角（或反过来）
          // 都不会互相覆盖——取或建() 会复用已有的 GradientDrawable。
          // ==================================================================

          /** 给任意 View 设置纯色背景，保留已设的圆角。 */
          public static void 设背景(View view, int color) {
              GradientDrawable drawable = 取或建(view);
              drawable.setColor(color);
              view.setBackground(drawable);
          }

          /** 给任意 View 设置圆角（dp），保留已设的背景色。 */
          public static void 设圆角(View view, float radiusDp) {
              GradientDrawable drawable = 取或建(view);
              drawable.setCornerRadius(dp(radiusDp));
              view.setBackground(drawable);
          }

          /** 给任意 View 设置四边一致的内边距（dp）。 */
          public static void 设内边距(View view, float paddingDp) {
              int p = dp(paddingDp);
              view.setPadding(p, p, p, p);
          }

          /** 给任意 View 设置四边一致的外边距（dp）；父容器不支持时静默忽略。 */
          public static void 设外边距(View view, float marginDp) {
              int m = dp(marginDp);
              ViewGroup.LayoutParams params = view.getLayoutParams();
              if (params instanceof ViewGroup.MarginLayoutParams) {
                  ((ViewGroup.MarginLayoutParams) params).setMargins(m, m, m, m);
                  view.requestLayout();
              }
          }

          /** 设置线性布局权重；父容器不是 LinearLayout 时静默忽略。 */
          public static void 设权重(View view, float weight) {
              ViewGroup.LayoutParams params = view.getLayoutParams();
              if (params instanceof android.widget.LinearLayout.LayoutParams) {
                  ((android.widget.LinearLayout.LayoutParams) params).weight = weight;
                  view.requestLayout();
              }
          }

          /** 取 View 已有的 GradientDrawable 背景；没有就新建一个。 */
          private static GradientDrawable 取或建(View view) {
              android.graphics.drawable.Drawable background = view.getBackground();
              if (background instanceof GradientDrawable) {
                  return (GradientDrawable) background;
              }
              return new GradientDrawable();
          }
      }
  """
          .trimIndent()

  /**
   * 生成 `AndroidManifest.xml` 的内容。
   *
   * <p>放在生成器里而不是 {@code QuickDevelop.kt} 里，是为了能在 JVM 上断言——
   * 工具类（{@code 震动}/{@code 发通知}）需要 manifest 权限，漏声明时行为是
   * **静默失效**（不崩溃），最容易一直没人发现。落在这里就能被单测钉住。
   */
  fun manifestXml(): String =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">

          <!-- 工具类里会用到、且必须在 manifest 声明的权限。
               VIBRATE：系统.震动 需要，否则静默返回（不崩溃但没效果）。
               POST_NOTIFICATIONS：工具.发通知 在 Android 13+ 需要运行时授权，
               不声明的话 notify() 直接无效，且连申请入口都没有。 -->
          <uses-permission android:name="android.permission.VIBRATE" />
          <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

          <application
              android:allowBackup="true"
              android:icon="@mipmap/ic_launcher"
              android:label="@string/app_name"
              android:roundIcon="@mipmap/ic_launcher_round"
              android:supportsRtl="true"
              android:theme="@style/AppTheme">
              <activity
                  android:name=".MainActivity"
                  android:exported="true">
                  <intent-filter>
                      <action android:name="android.intent.action.MAIN" />
                      <category android:name="android.intent.category.LAUNCHER" />
                  </intent-filter>
              </activity>
          </application>

      </manifest>
      """
          .trimIndent()

  /** 生成 `线性布局`。 */
  fun linearLayoutJava(packageId: String): String =
      """
      package ${uiPackage(packageId)};

      import android.content.Context;
      import android.view.View;
      import android.view.ViewGroup;
      import android.widget.LinearLayout;

      /** 垂直/水平排列的容器。 */
      public class 线性布局 extends 视图 {

          public static final int 垂直 = LinearLayout.VERTICAL;
          public static final int 水平 = LinearLayout.HORIZONTAL;

          private final LinearLayout 内部;

          public 线性布局(Context context) {
              super(context);
              内部 = new LinearLayout(context);
              内部.setOrientation(LinearLayout.VERTICAL);
              addView(
                      内部,
                      new LayoutParams(
                              ViewGroup.LayoutParams.MATCH_PARENT,
                              ViewGroup.LayoutParams.WRAP_CONTENT));
          }

          /** 设置排列方向，传 {@link #垂直} 或 {@link #水平}。 */
          public 线性布局 方向(int orientation) {
              内部.setOrientation(orientation);
              return this;
          }

          /** 依次添加子视图，返回自身以便链式继续。 */
          public 线性布局 添加(View... children) {
              for (View child : children) {
                  if (child == null) {
                      continue;
                  }
                  ViewGroup parent = (ViewGroup) child.getParent();
                  if (parent != null) {
                      parent.removeView(child);
                  }
                  内部.addView(child);
              }
              return this;
          }

          /** 把内容对齐方式透传给内部 LinearLayout。 */
          public 线性布局 对齐(int gravity) {
              内部.setGravity(gravity);
              return this;
          }

          // 以下重写基类的链式方法，把返回类型收窄为 线性布局。
          //
          // Java 里继承来的方法返回的是声明类型 视图，因此
          // `new 线性布局(ctx).背景(c).对齐(...)` 在 `.背景(c)` 之后静态类型变成
          // 视图，`.对齐` 就找不到了。协变返回类型（子类返回更具体的类型）可以
          // 解决这个问题，代价是每个方法一行样板。

          @Override
          public 线性布局 背景(int color) {
              super.背景(color);
              return this;
          }

          @Override
          public 线性布局 圆角(float radiusDp) {
              super.圆角(radiusDp);
              return this;
          }

          @Override
          public 线性布局 内边距(float paddingDp) {
              super.内边距(paddingDp);
              return this;
          }

          @Override
          public 线性布局 外边距(float marginDp) {
              super.外边距(marginDp);
              return this;
          }

          @Override
          public 线性布局 权重(float weight) {
              super.权重(weight);
              return this;
          }
      }
  """
          .trimIndent()

  /**
   * 生成 `约束布局`。
   *
   * <p>只提供 `居中` 与 `铺满` 两个预设，不做完整 DSL——完整约束 DSL 需要
   * 一整套属性名与解析逻辑，超出「快速开发」的定位。
   */
  fun constraintLayoutJava(packageId: String): String =
      """
      package ${uiPackage(packageId)};

      import android.content.Context;
      import android.view.View;
      import android.view.ViewGroup;
      import androidx.constraintlayout.widget.ConstraintLayout;
      import androidx.constraintlayout.widget.ConstraintSet;

      /** 只提供居中 / 铺满两个预设的约束容器。 */
      public class 约束布局 extends 视图 {

          private final ConstraintLayout 内部;
          private View 内容;

          public 约束布局(Context context) {
              super(context);
              内部 = new ConstraintLayout(context);
              addView(
                      内部,
                      new LayoutParams(
                              ViewGroup.LayoutParams.MATCH_PARENT,
                              ViewGroup.LayoutParams.MATCH_PARENT));
          }

          /**
           * 把子视图放进容器并分配一个 id。
           *
           * <p>ConstraintSet 是按 id 描述约束的，子视图没有 id 时
           * {@code setId} 会得到 {@code View.NO_ID(-1)}，clone/apply 全部失效。
           * 因此这里统一用 {@code View.generateViewId()} 兜底。
           */
          private void 装载(View child) {
              内容 = child;
              内部.removeAllViews();
              if (child.getId() == View.NO_ID) {
                  child.setId(View.generateViewId());
              }
              内部.addView(child);
          }

          /** 设置唯一子视图，并让它水平垂直居中。 */
          public 约束布局 居中(View child) {
              装载(child);
              ConstraintSet set = new ConstraintSet();
              set.clone(内部);
              set.centerHorizontally(child.getId(), ConstraintSet.PARENT_ID);
              set.centerVertically(child.getId(), ConstraintSet.PARENT_ID);
              set.applyTo(内部);
              return this;
          }

          /** 设置唯一子视图，并让它铺满父容器。 */
          public 约束布局 铺满(View child) {
              装载(child);
              ConstraintSet set = new ConstraintSet();
              set.clone(内部);
              set.constrainWidth(child.getId(), ConstraintSet.MATCH_CONSTRAINT);
              set.constrainHeight(child.getId(), ConstraintSet.MATCH_CONSTRAINT);
              set.connect(
                      child.getId(),
                      ConstraintSet.START,
                      ConstraintSet.PARENT_ID,
                      ConstraintSet.START);
              set.connect(
                      child.getId(),
                      ConstraintSet.END,
                      ConstraintSet.PARENT_ID,
                      ConstraintSet.END);
              set.connect(
                      child.getId(),
                      ConstraintSet.TOP,
                      ConstraintSet.PARENT_ID,
                      ConstraintSet.TOP);
              set.connect(
                      child.getId(),
                      ConstraintSet.BOTTOM,
                      ConstraintSet.PARENT_ID,
                      ConstraintSet.BOTTOM);
              set.applyTo(内部);
              return this;
          }
      }
  """
          .trimIndent()

  /** 生成 `文本`。 */
  fun textJava(packageId: String): String =
      """
      package ${uiPackage(packageId)};

      import android.content.Context;
      import android.graphics.Color;
      import android.view.ViewGroup;
      import android.widget.TextView;

      /** 文本显示。 */
      public class 文本 extends TextView {

          public 文本(Context context) {
              super(context);
              setTextColor(Color.parseColor("#212121"));
              setTextSize(16f);
          }

          public 文本 文字(CharSequence text) {
              setText(text);
              return this;
          }

          /** 字号，单位 sp。 */
          public 文本 字号(float sizeSp) {
              setTextSize(sizeSp);
              return this;
          }

          public 文本 颜色(int color) {
              setTextColor(color);
              return this;
          }

          public 文本 粗体(boolean bold) {
              setTypeface(getTypeface(), bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
              return this;
          }

          public 文本 外边距(float marginDp) {
              int m = 视图.dp(marginDp);
              ViewGroup.LayoutParams params = getLayoutParams();
              if (params instanceof ViewGroup.MarginLayoutParams) {
                  ((ViewGroup.MarginLayoutParams) params).setMargins(m, m, m, m);
                  requestLayout();
              }
              return this;
          }
      }
  """
          .trimIndent()

  /** 生成 `按钮`。 */
  fun buttonJava(packageId: String): String =
      """
      package ${uiPackage(packageId)};

      import android.content.Context;
      import android.view.ViewGroup;
      import com.google.android.material.button.MaterialButton;

      /** Material 按钮。 */
      public class 按钮 extends MaterialButton {

          public 按钮(Context context) {
              super(context);
              setTextSize(16f);
              setAllCaps(false);
          }

          public 按钮 文字(CharSequence text) {
              setText(text);
              return this;
          }

          /** 点击回调。 */
          public 按钮 点击(final Runnable action) {
              setOnClickListener(
                      new OnClickListener() {
                          @Override
                          public void onClick(android.view.View v) {
                              action.run();
                          }
                      });
              return this;
          }

          /**
           * 宽度铺满，高度自适应。
           *
           * <p>在加入父容器**之前**调用也有效：没有 LayoutParams 时先建一个，
           * 之后父容器 addView 时会按自身类型转换并保留 width=MATCH_PARENT。
           */
          public 按钮 铺满宽度() {
              ViewGroup.LayoutParams params = getLayoutParams();
              if (params == null) {
                  params =
                          new ViewGroup.LayoutParams(
                                  ViewGroup.LayoutParams.MATCH_PARENT,
                                  ViewGroup.LayoutParams.WRAP_CONTENT);
              } else {
                  params.width = ViewGroup.LayoutParams.MATCH_PARENT;
              }
              setLayoutParams(params);
              return this;
          }
      }
  """
          .trimIndent()

  /**
   * 生成 `输入框`。
   *
   * <p>继承 `TextInputEditText` 而非 `EditText`：前者配合
   * `TextInputLayout` 才能用 Material 的下标/错误提示，且是 Material 官方推荐用法。
   */
  fun inputJava(packageId: String): String =
      """
      package ${uiPackage(packageId)};

      import android.content.Context;
      import com.google.android.material.textfield.TextInputEditText;

      /** 文本输入框。 */
      public class 输入框 extends TextInputEditText {

          public 输入框(Context context) {
              super(context);
              setTextSize(16f);
          }

          public 输入框 提示(CharSequence hint) {
              setHint(hint);
              return this;
          }

          /** 单行输入。 */
          public 输入框 单行(boolean singleLine) {
              setSingleLine(singleLine);
              return this;
          }

          /** 取当前文本，未输入时返回空串（不会返回 null）。 */
          public String 取值() {
              CharSequence text = getText();
              return text == null ? "" : text.toString();
          }
      }
  """
          .trimIndent()

  /**
   * 生成 `页面`（AppCompatActivity 基类）。
   *
   * <p>子类只需实现 `视图 搭建()`，`onCreate` 会把它返回的视图设为内容视图。
   * 这样 MainActivity 就只剩「搭界面」这一件事，不用重复写 setContentView。
   */
  fun pageJava(packageId: String): String =
      """
      package ${uiPackage(packageId)};

      import android.os.Bundle;
      import androidx.appcompat.app.AppCompatActivity;

      /** 页面基类：子类实现 {@link #搭建()} 返回根视图即可。 */
      public abstract class 页面 extends AppCompatActivity {

          /** 由子类实现，返回页面根视图。 */
          protected abstract android.view.View 搭建();

          @Override
          protected void onCreate(Bundle savedInstanceState) {
              super.onCreate(savedInstanceState);
              setContentView(搭建());
          }
      }
  """
          .trimIndent()

  /**
   * 生成 MainActivity（用中文组件搭一个示例界面）。
   *
   * <p>示例保持最小但有闭环：输入框 + 按钮 + 文本，点按钮把输入回显到文本。
   */
  fun mainActivityJava(packageId: String): String =
      """
      package $packageId;

      import android.graphics.Color;
      import android.view.Gravity;
      import android.view.ViewGroup;
      import android.widget.LinearLayout;
      import ${uiPackage(packageId)}.按钮;
      import ${uiPackage(packageId)}.文本;
      import ${uiPackage(packageId)}.线性布局;
      import ${uiPackage(packageId)}.视图;
      import ${uiPackage(packageId)}.页面;
      import ${uiPackage(packageId)}.输入框;

      /**
       * 用中文组件搭出的示例页面。
       *
       * <p>界面结构：标题 + 输入框 + 按钮 + 结果文本，点按钮回显输入内容。
       */
      public class MainActivity extends 页面 {

          private 输入框 输入;
          private 文本 结果;

          @Override
          protected 视图 搭建() {
              输入 = new 输入框(this).提示("请输入内容").单行(true);
              输入.setLayoutParams(
                      new LinearLayout.LayoutParams(
                              ViewGroup.LayoutParams.MATCH_PARENT,
                              ViewGroup.LayoutParams.WRAP_CONTENT));

              结果 = new 文本(this).文字("等待操作…").字号(16f).颜色(Color.parseColor("#616161"));
              结果.setLayoutParams(
                      new LinearLayout.LayoutParams(
                              ViewGroup.LayoutParams.MATCH_PARENT,
                              ViewGroup.LayoutParams.WRAP_CONTENT));

              按钮 提交 = new 按钮(this).文字("显示输入").铺满宽度();
              提交.点击(
                      new Runnable() {
                          @Override
                          public void run() {
                              String value = 输入.取值();
                              结果.文字(value.isEmpty() ? "你还没有输入内容" : "你输入了：" + value);
                          }
                      });

              线性布局 内容 =
                      new 线性布局(this)
                              .方向(线性布局.垂直)
                              .背景(Color.parseColor("#FFFFFF"))
                              .圆角(12f)
                              .内边距(20f)
                              .对齐(Gravity.CENTER_VERTICAL)
                              .添加(
                                      new 文本(this).文字("Quick Develop").字号(24f).粗体(true),
                                      new 文本(this).文字("用中文组件快速搭界面").字号(14f).颜色(Color.parseColor("#757575")),
                                      输入,
                                      提交,
                                      结果);

              内容.setLayoutParams(
                      new LinearLayout.LayoutParams(
                              ViewGroup.LayoutParams.MATCH_PARENT,
                              ViewGroup.LayoutParams.WRAP_CONTENT));

              线性布局 根 =
                      new 线性布局(this)
                              .方向(线性布局.垂直)
                              .背景(Color.parseColor("#F5F5F5"))
                              .内边距(16f)
                              .添加(内容);

              return 根;
          }
      }
  """
          .trimIndent()

  /**
   * 组件清单：`类名 -> Java 源码`。
   *
   * 模板按顺序写文件；MainActivity 由 `ActivityWriter` 单独写（它需要
   * 目录推导逻辑）。
   */
  fun components(packageId: String): List<Pair<String, String>> {
    val base =
        listOf(
            "视图" to viewJava(packageId),
            "线性布局" to linearLayoutJava(packageId),
            "约束布局" to constraintLayoutJava(packageId),
            "文本" to textJava(packageId),
            "按钮" to buttonJava(packageId),
            "输入框" to inputJava(packageId),
            "页面" to pageJava(packageId),
        )
    val extra = EXTRA_WIDGETS.flatMap { w ->
      listOf(w.cn to widgetJava(packageId, w), w.en to aliasJava(packageId, w))
    }
    return base + extra
  }

  // =====================================================================================
  // 规格表驱动的控件生成
  //
  // 为什么用规格表而不是 38 段手写字符串：每个控件的 Java 骨架（构造器、样式方法、
  // 英文别名）高度同构，只有「继承谁 / 有哪些特有方法」不同。一张表 + 一个引擎，
  // 改一处约定就全局生效；手写 38 段则要改 38 处。
  //
  // 规格来源：Appv5 的控件清单（`runlibrary/app/v/`，109 文件）。Appv5 的代码本身
  // 不可搬运——基类 `VC` 依赖已从仓库丢失的 `ClientsUDP.a`、反编译有重复声明
  // （`an.java` 里 `st` 与 `f94` 同指一个 Button），整包无法编译。但「哪些控件
  // 值得封装」这份清单与骨架设计是可复用的，本表即为它的落地。
  // =====================================================================================

  /** 控件能力来源：容器继承 [视图]（可添加子视图），叶子控件走 [视图] 的静态辅助。 */
  private enum class Style { CONTAINER, LEAF }

  /**
   * 一个控件特有方法的签名与实现体。
   *
   * [body] 为空表示生成 `abstract` 空壳，交给子类实现（如 `页面.搭建()`）。
   */
  private data class Method(
      val ret: String,
      val name: String,
      val params: String = "",
      val body: List<String> = emptyList(),
  )

  private data class Widget(
      val cn: String,
      val en: String,
      /** Java 继承的类（全限定名）。 */
      val extends: String,
      val style: Style,
      /** 构造器体（`super(context)` 之后执行）。 */
      val ctor: List<String> = emptyList(),
      val methods: List<Method> = emptyList(),
      val isAbstract: Boolean = false,
      /**
       * 不生成自动样式方法的名字。
       *
       * 仅 [CardView] 需要：它自带 `setRadius`，背景式圆角对它无效，
       * 因此跳过后由规格表提供一个走 `setRadius` 的 `圆角`。
       */
      val skipStyles: Set<String> = emptySet(),
      /**
       * 方法体里用到的**简单名**类型所需的 import。
       *
       * 必须显式声明：`extends` 用的是全限定名，不会把父类的简单名带进作用域，
       * 因此方法体里写 `LinearLayoutManager` 必须自己 import。
       */
      val extraImports: Set<String> = emptySet(),
      /**
       * 非空时改为「包装」形态：类继承 `视图`，内部持有一个该类型的实例。
       *
       * 用于 **final 类**——`ViewPager2` 是 final，无法被继承，只能包装。
       * 包装后方法体里通过 `内部` 调用目标对象。
       */
      val wraps: String? = null,
  )

  /** 五个样式方法：名称 → 参数声明 → 转发用的实参名。 */
  private val STYLE_SPECS =
      listOf(
          Triple("背景", "int color", "color"),
          Triple("圆角", "float radiusDp", "radiusDp"),
          Triple("内边距", "float paddingDp", "paddingDp"),
          Triple("外边距", "float marginDp", "marginDp"),
          Triple("权重", "float weight", "weight"),
      )

  private fun widgetJava(packageId: String, w: Widget): String {
    val imports =
        buildSet {
              add("android.content.Context")
              add("android.view.View")
              if (w.style == Style.CONTAINER) add("android.view.ViewGroup")
              // 包装形态的字段类型与 new 表达式都用简单名，必须 import 目标类。
              if (w.wraps != null) add(w.wraps)
              addAll(w.extraImports)
            }
            .sorted()

    return buildString {
      append("package ").append(uiPackage(packageId)).append(";\n\n")
      for (i in imports) append("import ").append(i).append(";\n")
      append('\n')
      append("/** ").append(w.cn).append("：")
      append(w.wraps?.substringAfterLast('.') ?: w.extends.substringAfterLast('.'))
      append(" 的中文封装。 */\n")

      if (w.wraps != null) {
        // ---- 包装形态：目标类是 final（无法继承），改为继承 视图 + 内部持有 ----
        append(if (w.isAbstract) "public abstract class " else "public class ")
        append(w.cn).append(" extends 视图 {\n\n")
        append("    private final ").append(w.wraps.substringAfterLast('.')).append(" 内部;\n\n")
      } else {
        append(if (w.isAbstract) "public abstract class " else "public class ")
        append(w.cn).append(" extends ").append(w.extends).append(" {\n\n")
      }

      // 构造器
      append("    public ").append(w.cn).append("(Context context) {\n        super(context);\n")
      if (w.wraps != null) {
        append("        内部 = new ").append(w.wraps.substringAfterLast('.')).append("(context);\n")
        append("        addView(内部, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));\n")
      }
      for (line in w.ctor) append("        ").append(line).append('\n')
      append("    }\n\n")

      // 包装形态：暴露内部实例，方便调用方做未封装的操作。
      if (w.wraps != null) {
        append("    /** 内部持有的 ").append(w.wraps.substringAfterLast('.')).append(" 实例。 */\n")
        append("    public ").append(w.wraps.substringAfterLast('.')).append(" 控件() {\n")
        append("        return 内部;\n    }\n\n")
      }

      // 样式方法。
      //
      // 一律走 视图 的静态辅助，不写 super.背景(...)：表格里的容器虽然能加子视图
      // （RelativeLayout / ScrollView / CardView…），但它们**并不继承 视图**，
      // 调 super 会编译失败。静态辅助对所有 View 都成立。
      for ((name, params, arg) in STYLE_SPECS) {
        if (name in w.skipStyles) continue
        append("    public ").append(w.cn).append(' ').append(name).append('(').append(params).append(") {\n")
        append("        视图.设").append(name).append("(this, ").append(arg).append(");\n")
        append("        return this;\n    }\n\n")
      }

      // 特有方法
      for (m in w.methods) {
        if (m.body.isEmpty()) {
          append("    public abstract ").append(m.ret).append(' ').append(m.name)
              .append('(').append(m.params).append(");\n\n")
        } else {
          append("    public ").append(m.ret).append(' ').append(m.name)
              .append('(').append(m.params).append(") {\n")
          for (line in m.body) append("        ").append(line).append('\n')
          append("    }\n\n")
        }
      }

      append("}\n")
    }
  }

  /**
   * 38 个新增控件。列名对应 Appv5 `runlibrary/app/v/` 的控件清单。
   *
   * 容器类（style=CONTAINER）继承 `视图`，因此自带 背景/圆角/内边距/外边距/权重
   * 且返回本类类型，链式调用不会断。
   */
  private val EXTRA_WIDGETS: List<Widget> =
      listOf(
          // ---------- 批 1：布局 / 容器（17） ----------
          Widget(
              cn = "相对布局",
              en = "RelativeLayoutBox",
              extends = "android.widget.RelativeLayout",
              style = Style.CONTAINER,
          ),
          Widget(
              cn = "帧布局",
              en = "FrameLayoutBox",
              extends = "android.widget.FrameLayout",
              style = Style.CONTAINER,
          ),
          Widget(
              cn = "表格布局",
              en = "TableLayoutBox",
              extends = "android.widget.TableLayout",
              style = Style.CONTAINER,
          ),
          Widget(
              cn = "表格项",
              en = "TableRowBox",
              extends = "android.widget.TableRow",
              style = Style.CONTAINER,
          ),
          Widget(
              cn = "滚动",
              en = "ScrollBox",
              extends = "android.widget.ScrollView",
              style = Style.CONTAINER,
              methods =
                  listOf(
                      Method(
                          ret = "滚动",
                          name = "内容",
                          params = "View child",
                          body =
                              listOf(
                                  "removeAllViews();",
                                  "if (child != null) addView(child);",
                                  "return this;",
                              ),
                      ),
                  ),
          ),
          Widget(
              cn = "水平滚动",
              en = "HorizontalScrollBox",
              extends = "android.widget.HorizontalScrollView",
              style = Style.CONTAINER,
              methods =
                  listOf(
                      Method(
                          ret = "水平滚动",
                          name = "内容",
                          params = "View child",
                          body =
                              listOf(
                                  "removeAllViews();",
                                  "if (child != null) addView(child);",
                                  "return this;",
                              ),
                      ),
                  ),
          ),
          Widget(
              cn = "嵌套滚动",
              en = "NestedScrollBox",
              extends = "androidx.core.widget.NestedScrollView",
              style = Style.CONTAINER,
              methods =
                  listOf(
                      Method(
                          ret = "嵌套滚动",
                          name = "内容",
                          params = "View child",
                          body =
                              listOf(
                                  "removeAllViews();",
                                  "if (child != null) addView(child);",
                                  "return this;",
                              ),
                      ),
                  ),
          ),
          Widget(
              cn = "卡片",
              en = "CardBox",
              extends = "androidx.cardview.widget.CardView",
              style = Style.CONTAINER,
              ctor =
                  listOf(
                      "setCardElevation(视图.dp(2));",
                      "setUseCompatPadding(true);",
                  ),
              // 背景与圆角都必须走 CardView 自己的 API。
              //
              // CardView 的圆角是由它内部那个 RoundRectDrawable 画的。`视图.设背景`
              // 走的是 `View.setBackground`，会把那个 drawable **整个换掉**成普通
              // GradientDrawable——于是卡片变直角，且随后 `setRadius` 改的是已经被
              // 换掉的那个 drawable，肉眼毫无变化（顺序反过来同样无效）。
              // 因此两者都跳过自动生成，改用 setCardBackgroundColor / setRadius，
              // 它们改的是同一个内部 drawable，与调用顺序无关。
              skipStyles = setOf("背景", "圆角"),
              methods =
                  listOf(
                      Method(
                          ret = "卡片",
                          name = "背景",
                          params = "int color",
                          body =
                              listOf(
                                  "setCardBackgroundColor(color);",
                                  "return this;",
                              ),
                      ),
                      Method(
                          ret = "卡片",
                          name = "圆角",
                          params = "float radiusDp",
                          body =
                              listOf(
                                  "setRadius(视图.dp(radiusDp));",
                                  "return this;",
                              ),
                      ),
                      Method(
                          ret = "卡片",
                          name = "内容",
                          params = "View child",
                          body =
                              listOf(
                                  "removeAllViews();",
                                  "if (child != null) addView(child);",
                                  "return this;",
                              ),
                      ),
                  ),
          ),
          Widget(
              cn = "协调布局",
              en = "CoordinatorBox",
              extends = "androidx.coordinatorlayout.widget.CoordinatorLayout",
              style = Style.CONTAINER,
          ),
          Widget(
              cn = "应用栏布局",
              en = "AppBarBox",
              extends = "com.google.android.material.appbar.AppBarLayout",
              style = Style.CONTAINER,
          ),
          Widget(
              cn = "工具栏布局",
              en = "ToolbarBox",
              extends = "com.google.android.material.appbar.MaterialToolbar",
              style = Style.LEAF,
              methods =
                  listOf(
                      Method(
                          ret = "工具栏布局",
                          name = "标题",
                          params = "CharSequence title",
                          body = listOf("setTitle(title);", "return this;"),
                      ),
                  ),
          ),
          Widget(
              cn = "折叠工具栏布局",
              en = "CollapsingToolbarBox",
              extends = "com.google.android.material.appbar.CollapsingToolbarLayout",
              style = Style.LEAF,
              methods =
                  listOf(
                      Method(
                          ret = "折叠工具栏布局",
                          name = "标题",
                          params = "CharSequence title",
                          body = listOf("setTitle(title);", "return this;"),
                      ),
                  ),
          ),
          Widget(
              cn = "侧滑窗体",
              en = "DrawerBox",
              extends = "androidx.drawerlayout.widget.DrawerLayout",
              style = Style.LEAF,
              extraImports = setOf("android.view.Gravity"),
              methods =
                  listOf(
                      Method(
                          ret = "侧滑窗体",
                          name = "侧栏",
                          params = "View drawer",
                          body =
                              listOf(
                                  "if (drawer != null) {",
                                  "    // gravity 必须显式设为 START：DrawerLayout 只把带 gravity 的子视图",
                                  "    // 当作抽屉，而 LayoutParams 的**双参构造器会把 gravity 置 0**",
                                  "    // （源码里就是 `this(0)`），于是抽屉永远打不开。",
                                  "    LayoutParams params = new LayoutParams(视图.dp(280), LayoutParams.MATCH_PARENT);",
                                  "    params.gravity = Gravity.START;",
                                  "    addView(drawer, params);",
                                  "}",
                                  "return this;",
                              ),
                      ),
                  ),
          ),
          // ViewPager2 是 final 类，无法继承，只能包装。
          Widget(
              cn = "滑动窗体",
              en = "PagerBox",
              extends = "androidx.viewpager2.widget.ViewPager2",
              style = Style.CONTAINER,
              wraps = "androidx.viewpager2.widget.ViewPager2",
          ),
          Widget(
              cn = "垂直滑动窗体",
              en = "VerticalPagerBox",
              extends = "androidx.viewpager2.widget.ViewPager2",
              style = Style.CONTAINER,
              wraps = "androidx.viewpager2.widget.ViewPager2",
              ctor = listOf("内部.setOrientation(ViewPager2.ORIENTATION_VERTICAL);"),
          ),
          Widget(
              cn = "标签布局",
              en = "TabBox",
              extends = "com.google.android.material.tabs.TabLayout",
              style = Style.LEAF,
              methods =
                  listOf(
                      Method(
                          ret = "标签布局",
                          name = "标签",
                          params = "CharSequence... titles",
                          body =
                              listOf(
                                  "for (CharSequence t : titles) {",
                                  "    addTab(newTab().setText(t));",
                                  "}",
                                  "return this;",
                              ),
                      ),
                  ),
          ),
          Widget(
              cn = "下拉刷新控件",
              en = "SwipeRefreshBox",
              extends = "androidx.swiperefreshlayout.widget.SwipeRefreshLayout",
              style = Style.LEAF,
              methods =
                  listOf(
                      Method(
                          ret = "下拉刷新控件",
                          name = "刷新回调",
                          params = "final Runnable action",
                          body =
                              listOf(
                                  "setOnRefreshListener(new OnRefreshListener() {",
                                  "    @Override",
                                  "    public void onRefresh() {",
                                  "        action.run();",
                                  "    }",
                                  "});",
                                  "return this;",
                              ),
                      ),
                  ),
          ),
          // ---------- 批 2：数据展示（10） ----------
          Widget(
              cn = "列表",
              en = "ListBox",
              extends = "android.widget.ListView",
              style = Style.LEAF,
          ),
          Widget(
              cn = "v7列表",
              en = "RecyclerBox",
              extends = "androidx.recyclerview.widget.RecyclerView",
              style = Style.LEAF,
              extraImports = setOf("androidx.recyclerview.widget.LinearLayoutManager"),
              methods =
                  listOf(
                      Method(
                          ret = "v7列表",
                          name = "纵向",
                          body =
                              listOf(
                                  "setLayoutManager(new LinearLayoutManager(getContext()));",
                                  "return this;",
                              ),
                      ),
                      Method(
                          ret = "v7列表",
                          name = "适配器",
                          params = "androidx.recyclerview.widget.RecyclerView.Adapter<?> adapter",
                          body = listOf("setAdapter(adapter);", "return this;"),
                      ),
                  ),
          ),
          Widget(
              cn = "网格视图",
              en = "GridBox",
              extends = "android.widget.GridView",
              style = Style.LEAF,
          ),
          Widget(
              cn = "下拉菜单",
              en = "SpinnerBox",
              extends = "android.widget.Spinner",
              style = Style.LEAF,
          ),
          Widget(
              cn = "浏览器",
              en = "WebBox",
              extends = "android.webkit.WebView",
              style = Style.LEAF,
              methods =
                  listOf(
                      Method(
                          ret = "浏览器",
                          name = "加载",
                          params = "String url",
                          body = listOf("loadUrl(url);", "return this;"),
                      ),
                  ),
          ),
          Widget(
              cn = "图像",
              en = "ImageBox",
              extends = "android.widget.ImageView",
              style = Style.LEAF,
              methods =
                  listOf(
                      Method(
                          ret = "图像",
                          name = "图片",
                          params = "int resId",
                          body = listOf("setImageResource(resId);", "return this;"),
                      ),
                      Method(
                          ret = "图像",
                          name = "适应",
                          params = "android.widget.ImageView.ScaleType type",
                          body = listOf("setScaleType(type);", "return this;"),
                      ),
                  ),
          ),
          Widget(
              cn = "图像按钮",
              en = "ImageButtonBox",
              extends = "android.widget.ImageButton",
              style = Style.LEAF,
              methods =
                  listOf(
                      Method(
                          ret = "图像按钮",
                          name = "图片",
                          params = "int resId",
                          body = listOf("setImageResource(resId);", "return this;"),
                      ),
                  ),
          ),
          Widget(
              cn = "视频",
              en = "VideoBox",
              extends = "android.widget.VideoView",
              style = Style.LEAF,
          ),
          Widget(
              cn = "面控件",
              en = "SurfaceBox",
              extends = "android.view.SurfaceView",
              style = Style.LEAF,
          ),
          Widget(
              cn = "动态图",
              en = "GifBox",
              extends = "android.widget.ImageView",
              style = Style.LEAF,
              methods =
                  listOf(
                      Method(
                          ret = "动态图",
                          name = "图片",
                          params = "int resId",
                          body = listOf("setImageResource(resId);", "return this;"),
                      ),
                  ),
          ),
          // ---------- 批 3：输入与选择（11） ----------
          Widget(
              cn = "开关",
              en = "SwitchBox",
              extends = "androidx.appcompat.widget.SwitchCompat",
              style = Style.LEAF,
              methods =
                  listOf(
                      Method(
                          ret = "开关",
                          name = "文字",
                          params = "CharSequence text",
                          body = listOf("setText(text);", "return this;"),
                      ),
                      Method(
                          ret = "开关",
                          name = "选中",
                          params = "boolean checked",
                          body = listOf("setChecked(checked);", "return this;"),
                      ),
                  ),
          ),
          Widget(
              cn = "单选项",
              en = "RadioBox",
              extends = "android.widget.RadioButton",
              style = Style.LEAF,
              methods =
                  listOf(
                      Method(
                          ret = "单选项",
                          name = "文字",
                          params = "CharSequence text",
                          body = listOf("setText(text);", "return this;"),
                      ),
                  ),
          ),
          Widget(
              cn = "多选",
              en = "CheckBox",
              extends = "android.widget.CheckBox",
              style = Style.LEAF,
              methods =
                  listOf(
                      Method(
                          ret = "多选",
                          name = "文字",
                          params = "CharSequence text",
                          body = listOf("setText(text);", "return this;"),
                      ),
                  ),
          ),
          Widget(
              cn = "单选布局",
              en = "RadioGroupBox",
              extends = "android.widget.RadioGroup",
              style = Style.CONTAINER,
              methods =
                  listOf(
                      Method(
                          ret = "单选布局",
                          name = "选项",
                          params = "View... children",
                          body =
                              listOf(
                                  "for (View child : children) {",
                                  "    if (child != null) addView(child);",
                                  "}",
                                  "return this;",
                              ),
                      ),
                  ),
          ),
          Widget(
              cn = "拖动条",
              en = "SeekBox",
              extends = "android.widget.SeekBar",
              style = Style.LEAF,
              methods =
                  listOf(
                      Method(
                          ret = "拖动条",
                          name = "上限",
                          params = "int max",
                          body = listOf("setMax(max);", "return this;"),
                      ),
                  ),
          ),
          Widget(
              cn = "评分",
              en = "RatingBox",
              extends = "android.widget.RatingBar",
              style = Style.LEAF,
              methods =
                  listOf(
                      Method(
                          ret = "评分",
                          name = "星级",
                          params = "float stars",
                          body = listOf("setRating(stars);", "return this;"),
                      ),
                  ),
          ),
          Widget(
              cn = "进度条",
              en = "ProgressBox",
              extends = "android.widget.ProgressBar",
              style = Style.LEAF,
              methods =
                  listOf(
                      Method(
                          ret = "进度条",
                          name = "进度",
                          params = "int progress",
                          body = listOf("setProgress(progress);", "return this;"),
                      ),
                  ),
          ),
          Widget(
              cn = "日期选择器",
              en = "DatePickerBox",
              extends = "android.widget.DatePicker",
              style = Style.LEAF,
          ),
          Widget(
              cn = "时间选择器",
              en = "TimePickerBox",
              extends = "android.widget.TimePicker",
              style = Style.LEAF,
          ),
          Widget(
              cn = "文本输入布局",
              en = "TextInputLayoutBox",
              extends = "com.google.android.material.textfield.TextInputLayout",
              style = Style.LEAF,
              methods =
                  listOf(
                      Method(
                          ret = "文本输入布局",
                          name = "提示",
                          params = "CharSequence hint",
                          body = listOf("setHint(hint);", "return this;"),
                      ),
                  ),
          ),
          Widget(
              cn = "浮动动作按钮",
              en = "FabBox",
              extends = "com.google.android.material.floatingactionbutton.FloatingActionButton",
              style = Style.LEAF,
              methods =
                  listOf(
                      Method(
                          ret = "浮动动作按钮",
                          name = "点击",
                          params = "final Runnable action",
                          body =
                              listOf(
                                  "setOnClickListener(new OnClickListener() {",
                                  "    @Override",
                                  "    public void onClick(View v) {",
                                  "        action.run();",
                                  "    }",
                                  "});",
                                  "return this;",
                              ),
                      ),
                  ),
          ),
      )

  /**
   * 生成 `ui/README.md`——**从规格表产出**，不是手写。
   *
   * <p>为什么必须从表产出：控件清单与样式方法都在 [EXTRA_WIDGETS] / [STYLE_SPECS] 里，
   * 手写文档会在下一次加控件时立刻过期。这样改表即改文档，不会漂移。
   *
   * <p>带目录锚点：45 个控件的表格很长，顶部导航让读者能直接跳到关心的分区。
   */
  fun uiReadme(packageId: String): String {
    val ui = uiPackage(packageId)
    val builder = StringBuilder()

    builder.append("# UI 组件（").append(ui).append("）\n\n")
    builder.append("本包由 Quick Develop 模板生成，共 **").append(BASE_CN_NAMES.size + EXTRA_WIDGETS.size)
        .append(" 个中文组件**，其中 ").append(EXTRA_WIDGETS.size)
        .append(" 个扩展控件另有等量英文别名。全部支持链式调用，例如：\n\n")
    builder.append("```java\n")
    builder.append("线性布局 根 = new 线性布局(this).方向(线性布局.垂直).内边距(16f);\n")
    builder.append("根.添加(new 文本(this).文字(\"你好\").字号(20f).粗体(true));\n")
    builder.append("```\n\n")

    // ---- 导航 ----
    builder.append("## 目录\n\n")
    builder.append("- [通用样式方法](#通用样式方法)\n")
    builder.append("- [容器类](#容器类)\n")
    builder.append("- [基础控件](#基础控件)\n")
    builder.append("- [扩展控件](#扩展控件)\n")
    builder.append("- [命名规则](#命名规则)\n\n")

    // ---- 通用样式 ----
    builder.append("## 通用样式方法\n\n")
    builder.append("所有组件都提供下面五个方法，返回自身以便链式继续：\n\n")
    builder.append("| 方法 | 参数 | 说明 |\n|---|---|---|\n")
    builder.append("| `背景(int color)` | ARGB 颜色 | 纯色背景 |\n")
    builder.append("| `圆角(float dp)` | 半径 dp | 圆角（`卡片` 走 CardView 自身的 radius） |\n")
    builder.append("| `内边距(float dp)` | 四边一致 | 内边距 |\n")
    builder.append("| `外边距(float dp)` | 四边一致 | 外边距；父容器不支持时静默忽略 |\n")
    builder.append("| `权重(float w)` | 线性权重 | 仅父容器是 `线性布局` 时生效 |\n\n")
    builder.append("静态工具：`视图.dp(float)` 把 dp 转成像素。\n\n")

    // ---- 容器 ----
    builder.append("## 容器类\n\n")
    builder.append("容器都能装子视图，也都具备上表五个样式方法，且返回类型是本类（链式不会断）。\n\n")
    builder.append("除 `视图` 本身外，**所有容器都直接继承各自的 Android 类**")
        .append("（`LinearLayout` / `ConstraintLayout` / `RelativeLayout` / `ScrollView` / `CardView` …）。\n")
    builder.append("样式方法一律通过 `视图` 的静态辅助实现，因此对任意 `View` 都成立，效果相同。\n\n")
    builder.append("| 中文名 | 英文别名 | 基于 | 特有方法 |\n|---|---|---|---|\n")
    for (w in containerEntries()) {
      builder.append("| `").append(w.cn).append("` | ").append(aliasCell(w)).append(" | ")
          .append(w.extends.substringAfterLast('.')).append(" | ").append(specialMethods(w)).append(" |\n")
    }
    builder.append('\n')

    // ---- 基础控件 ----
    builder.append("## 基础控件\n\n")
    builder.append("| 中文名 | 基于 | 特有方法 |\n|---|---|---|\n")
    for ((cn, base, special) in BASE_CN_SPECS) {
      builder.append("| `").append(cn).append("` | `").append(base).append("` | ")
          .append(special).append(" |\n")
    }
    builder.append('\n')

    // ---- 扩展控件 ----
    builder.append("## 扩展控件\n\n")
    builder.append("| 中文名 | 英文别名 | 基于 | 特有方法 |\n|---|---|---|---|\n")
    for (w in EXTRA_WIDGETS.filter { it.style == Style.LEAF }) {
      builder.append("| `").append(w.cn).append("` | `").append(w.en).append("` | ")
          .append(w.extends.substringAfterLast('.')).append(" | ").append(specialMethods(w)).append(" |\n")
    }
    builder.append('\n')

    // ---- 命名规则 ----
    builder.append("## 命名规则\n\n")
    builder.append("扩展控件都有**中文名**与**英文别名**两个类，行为完全一致")
        .append("（别名继承中文类，因此不会随时间分叉）。\n")
    builder.append("基础控件（`视图` / `线性布局` / `约束布局` / `文本` / `按钮` / `输入框` / `页面`）")
        .append("**只有中文名**，没有英文别名。\n\n")
    builder.append("```java\n")
    builder.append("文本 t1 = new 文本(this);      // 基础控件：只有中文名\n")
    builder.append("文本 t2 = new Text(this);      // ❌ 编译失败：基础控件没有别名\n")
    builder.append("卡片 c1 = new 卡片(this);      // 扩展控件：中文名\n")
    builder.append("CardBox c2 = new CardBox(this); // ✅ 扩展控件的英文别名\n")
    builder.append("```\n\n")
    builder.append("包名保持 ASCII（`").append(ui).append("`），只有类名与方法是中文。\n")

    return builder.toString()
  }

  /** 容器类清单：基础三件（手写类）+ 扩展里的容器。 */
  private fun containerEntries(): List<Widget> {
    val base =
        listOf(
            Widget("视图", "ViewBox", "android.widget.FrameLayout", Style.CONTAINER),
            Widget("线性布局", "LinearBox", "android.widget.LinearLayout", Style.CONTAINER),
            Widget("约束布局", "ConstraintBox", "androidx.constraintlayout.widget.ConstraintLayout", Style.CONTAINER),
        )
    return base + EXTRA_WIDGETS.filter { it.style == Style.CONTAINER }
  }

  /**
   * 英文别名单元格。
   *
   * <p>基础三件（`视图` / `线性布局` / `约束布局`）是**手写类，没有英文别名**——
   * 别名只对规格表里的扩展控件生成（见 [aliasJava]）。表格若不区分，会让人照抄
   * `new ViewBox(this)` 然后编译失败。
   */
  private fun aliasCell(w: Widget): String =
      if (BASE_CN_NAMES.contains(w.cn)) "—（无别名）" else "`" + w.en + "`"

  /** 把 [Widget.methods] 渲染成表格里的一格；无特有方法时显示 `—`。 */
  private fun specialMethods(w: Widget): String {
    if (w.methods.isEmpty()) {
      return "—"
    }
    return w.methods.joinToString(" / ") { "`${it.name}(${it.params})`" }
  }

  /** 现有 7 个手写类的「中文名 / 基于 / 特有方法」，供 README 与代码保持同源。 */
  private val BASE_CN_NAMES =
      listOf("视图", "线性布局", "约束布局", "文本", "按钮", "输入框", "页面")

  private val BASE_CN_SPECS =
      listOf(
          Triple("视图", "FrameLayout", "`dp(float)` 静态"),
          // 线性布局 / 约束布局 直接继承各自的 Android 类，**不是**继承 视图——
          // 它们只是复用了 视图 的静态样式辅助。写「基于 视图」会让人以为能当 视图 用。
          Triple("线性布局", "LinearLayout", "`方向(int)` / `添加(View...)` / `对齐(int)`"),
          Triple("约束布局", "ConstraintLayout", "`居中(View)` / `铺满(View)`"),
          Triple("文本", "TextView", "`文字(CharSequence)` / `字号(float)` / `颜色(int)` / `粗体(boolean)`"),
          Triple("按钮", "MaterialButton", "`文字(CharSequence)` / `点击(Runnable)` / `铺满宽度()`"),
          Triple("输入框", "TextInputEditText", "`提示(CharSequence)` / `单行(boolean)` / `取值()`"),
          Triple("页面", "AppCompatActivity", "`protected 视图 搭建()` 抽象"),
      )

  /**
   * 英文别名：继承中文类即可获得全部链式方法，零重复代码。
   *
   * 为什么用继承而不是再写一遍：别名若各自实现，两边的 setter 会逐渐分叉；
   * 继承保证 `Text` 与 `文本` 永远是同一个东西。
   */
  private fun aliasJava(packageId: String, w: Widget): String =
      """
      package ${uiPackage(packageId)};

      import android.content.Context;

      /** ${w.en}：{@link ${w.cn}} 的英文别名，行为完全一致。 */
      public class ${w.en} extends ${w.cn} {

          public ${w.en}(Context context) {
              super(context);
          }
      }
      """
          .trimIndent() + "\n"
}

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
      }
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
  fun components(packageId: String): List<Pair<String, String>> =
      listOf(
          "视图" to viewJava(packageId),
          "线性布局" to linearLayoutJava(packageId),
          "约束布局" to constraintLayoutJava(packageId),
          "文本" to textJava(packageId),
          "按钮" to buttonJava(packageId),
          "输入框" to inputJava(packageId),
          "页面" to pageJava(packageId),
      )
}

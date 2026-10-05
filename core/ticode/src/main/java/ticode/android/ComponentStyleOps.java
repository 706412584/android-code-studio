package ticode.android;

import android.graphics.drawable.Drawable;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.*;
import android.graphics.drawable.*;
import android.graphics.drawable.shapes.*;
import android.content.res.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;

import ticode.base.LongBox;
import ticode.jvm.FileInputStream2;
import ticode.jvm.FileOutputStream2;
import ticode.jvm.JInputStream;
import ticode.jvm.JOutputStream;

public class ComponentStyleOps extends android.graphics.drawable.GradientDrawable.Orientation {
public static void 渐变(VisualComponent 组件, int[] 颜色值, Drawable2 绘制, int 形状, int 宽度, int 高度, int 圆角) {
GradientDrawable drawable = new GradientDrawable();
if(宽度 != -1 && 高度 != -1)
drawable.setSize(宽度, 高度);
drawable.setColors(颜色值);
drawable.setCornerRadius(圆角);
drawable.setGradientType(形状);
drawable.setOrientation(绘制);
if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN)
组件.getView().setBackground(drawable);
else
组件.getView().setBackgroundDrawable(drawable);
}
//设置组件水波纹样式，有背景，有圆角，有水波纹反馈
public static void 置水波纹样式(VisualComponent 欲设置组件, double 圆角度数, int 默认展示颜色, int 水波纹颜色) {
int[][] stateList = new int[][]{
new int[]{android.R.attr.state_pressed},
new int[]{android.R.attr.state_focused},
new int[]{android.R.attr.state_activated},
new int[]{}
};
int[] stateColorList = new int[]{
水波纹颜色,
水波纹颜色,
水波纹颜色,
默认展示颜色
};
ColorStateList colorStateList = new ColorStateList(stateList, stateColorList);

float[] outRadius = new float[]{
(float) 圆角度数,
(float) 圆角度数,
(float) 圆角度数,
(float) 圆角度数,
(float) 圆角度数,
(float) 圆角度数,
(float) 圆角度数,
(float) 圆角度数
};
RoundRectShape roundRectShape = new RoundRectShape(outRadius, null, null);
ShapeDrawable maskDrawable = new ShapeDrawable();
maskDrawable.setShape(roundRectShape);
maskDrawable.getPaint().setColor(默认展示颜色);
maskDrawable.getPaint().setStyle(Paint.Style.FILL);

ShapeDrawable contentDrawable = new ShapeDrawable();
contentDrawable.setShape(roundRectShape);
contentDrawable.getPaint().setColor(默认展示颜色);
contentDrawable.getPaint().setStyle(Paint.Style.FILL);
RippleDrawable rippleDrawable = new RippleDrawable(colorStateList, contentDrawable, maskDrawable);
欲设置组件.getView().setBackground(rippleDrawable);
}

//设置组件水波纹样式，无背景，没有默认展示颜色，有向外扩散的水波纹圆圈
public static void 置水波纹样式2(VisualComponent 欲设置组件, int 水波纹颜色) {
int[][] stateList = new int[][]{
new int[]{android.R.attr.state_pressed},
new int[]{android.R.attr.state_focused},
new int[]{android.R.attr.state_activated},
new int[]{}
};
int[] stateColorList = new int[]{
水波纹颜色,
水波纹颜色,
水波纹颜色,
水波纹颜色
};
ColorStateList colorStateList = new ColorStateList(stateList, stateColorList);

RippleDrawable rippleDrawable = new RippleDrawable(colorStateList, null, null);
欲设置组件.getView().setBackground(rippleDrawable);
}

//设置组件水波纹样式，无背景，没有默认展示颜色，只有水波纹反馈
public static void 置水波纹样式3(VisualComponent 欲设置组件, float 圆角度数, int 水波纹颜色) {
int[][] stateList = new int[][]{
new int[]{android.R.attr.state_pressed},
new int[]{android.R.attr.state_focused},
new int[]{android.R.attr.state_activated},
new int[]{}
};
int[] stateColorList = new int[]{
水波纹颜色,
水波纹颜色,
水波纹颜色,
水波纹颜色
};
ColorStateList colorStateList = new ColorStateList(stateList, stateColorList);

float[] outRadius = new float[]{
(float) 圆角度数,
(float) 圆角度数,
(float) 圆角度数,
(float) 圆角度数,
(float) 圆角度数,
(float) 圆角度数,
(float) 圆角度数,
(float) 圆角度数
};
RoundRectShape roundRectShape = new RoundRectShape(outRadius, null, null);
ShapeDrawable maskDrawable = new ShapeDrawable();
maskDrawable.setShape(roundRectShape);
maskDrawable.getPaint().setColor(水波纹颜色);
maskDrawable.getPaint().setStyle(Paint.Style.FILL);
RippleDrawable rippleDrawable = new RippleDrawable(colorStateList, null, maskDrawable);
欲设置组件.getView().setBackground(rippleDrawable);
}

//设置圆角，且有背景
public static void 置圆角背景(VisualComponent 欲设置组件, int 背景颜色, double 左上圆角, double 右上圆角, double 右下圆角, double 左下圆角) {
float[] outRadius = new float[]{
(float) 左上圆角,
(float) 左上圆角,
(float) 右上圆角,
(float) 右上圆角,
(float) 右下圆角,
(float) 右下圆角,
(float) 左下圆角,
(float) 左下圆角
};
android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
drawable.setCornerRadii(outRadius);
drawable.setColor(背景颜色);
欲设置组件.getView().setBackground(drawable);
}

//设置圆角，且有背景
public static void 置圆背景2(VisualComponent 欲设置组件, int 背景颜色, double 圆角度数) {
置圆角背景(欲设置组件, 背景颜色, 圆角度数, 圆角度数, 圆角度数, 圆角度数);
}

//设置圆角，没有背景，但有边框
public static void 置圆角边框(VisualComponent 欲设置组件, int 边框宽度, int 边框颜色, double 左上圆角, double 右上圆角, double 右下圆角, double 左下圆角) {
float[] outRadius = new float[]{
(float) 左上圆角,
(float) 左上圆角,
(float) 右上圆角,
(float) 右上圆角,
(float) 右下圆角,
(float) 右下圆角,
(float) 左下圆角,
(float) 左下圆角
};
android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
drawable.setCornerRadii(outRadius);
drawable.setStroke(边框宽度,边框颜色);
欲设置组件.getView().setBackground(drawable);
}

//设置圆角，没有背景，但有边框
public static void 置圆角边框2(VisualComponent 欲设置组件, int 边框宽度, int 边框颜色, double 圆角度数) {
置圆角边框(欲设置组件, 边框宽度, 边框颜色, 圆角度数, 圆角度数, 圆角度数, 圆角度数);
}

//设置圆角，有背景，有边框
public static void 置圆角背景边框(VisualComponent 欲设置组件, int 背景颜色, int 边框宽度, int 边框颜色, double 左上圆角, double 右上圆角, double 右下圆角, double 左下圆角) {
float[] outRadius = new float[]{
(float) 左上圆角,
(float) 左上圆角,
(float) 右上圆角,
(float) 右上圆角,
(float) 右下圆角,
(float) 右下圆角,
(float) 左下圆角,
(float) 左下圆角
};
android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
drawable.setCornerRadii(outRadius);
drawable.setStroke(边框宽度,边框颜色);
drawable.setColor(背景颜色);
欲设置组件.getView().setBackground(drawable);
}

//设置圆角，有背景，有边框
public static void 置圆角背景边框2(VisualComponent 欲设置组件, int 背景颜色, int 边框宽度, int 边框颜色, double 圆角) {
置圆角背景边框(欲设置组件, 背景颜色, 边框宽度,边框颜色,圆角,圆角,圆角,圆角);
}

//设置圆角，但没有背景
public static void 置圆角(VisualComponent 欲设置组件, double 左上圆角, double 右上圆角, double 右下圆角, double 左下圆角) {
float[] outRadius = new float[]{
(float) 左上圆角,
(float) 左上圆角,
(float) 右上圆角,
(float) 右上圆角,
(float) 右下圆角,
(float) 右下圆角,
(float) 左下圆角,
(float) 左下圆角
};
android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
drawable.setCornerRadii(outRadius);
//drawable.setColor(0xffffffff);
欲设置组件.getView().setBackground(drawable);
}

//设置普通单击反馈样式
public static void 置普通样式(VisualComponent 欲设置组件, double 圆角度数, int 默认展示颜色, int 按下颜色) {
float[] outRadius = new float[]{
(float) 圆角度数,
(float) 圆角度数,
(float) 圆角度数,
(float) 圆角度数,
(float) 圆角度数,
(float) 圆角度数,
(float) 圆角度数,
(float) 圆角度数
};
RoundRectShape roundRectShape = new RoundRectShape(outRadius, null, null);
ShapeDrawable maskDrawable = new ShapeDrawable();
maskDrawable.setShape(roundRectShape);
maskDrawable.getPaint().setColor(按下颜色);
maskDrawable.getPaint().setStyle(Paint.Style.FILL);
ShapeDrawable contentDrawable = new ShapeDrawable();
contentDrawable.setShape(roundRectShape);
contentDrawable.getPaint().setColor(默认展示颜色);
contentDrawable.getPaint().setStyle(Paint.Style.FILL);
StateListDrawable sd = new StateListDrawable();
sd.addState(new int[]{android.R.attr.state_pressed}, maskDrawable);
sd.addState(new int[]{0}, contentDrawable);
欲设置组件.getView().setBackground(sd);
}

}
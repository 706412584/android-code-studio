package ticode.zh.android;

import android.graphics.drawable.GradientDrawable;
import android.graphics.*;
import android.graphics.drawable.*;
import android.graphics.drawable.shapes.*;
import android.content.res.*;

public abstract class 绘制和形状 {
public static final android.graphics.drawable.GradientDrawable.Orientation 绘制_从上往下;
public static final android.graphics.drawable.GradientDrawable.Orientation 绘制_从右上角到左下角;
public static final android.graphics.drawable.GradientDrawable.Orientation 绘制_从右往左;
public static final android.graphics.drawable.GradientDrawable.Orientation 绘制_从右下角到左上角;
public static final android.graphics.drawable.GradientDrawable.Orientation 绘制_从下往上;
public static final android.graphics.drawable.GradientDrawable.Orientation 绘制_从左下角到右上角;
public static final android.graphics.drawable.GradientDrawable.Orientation 绘制_从左往右;
public static final android.graphics.drawable.GradientDrawable.Orientation 绘制_从左上角到右下角;
public static final Integer 形状_矩形;
public static final Integer 形状_圆形;
public static final Integer 形状_线;
public static final Integer 形状_环;

static {
绘制_从上往下 = GradientDrawable.Orientation.TOP_BOTTOM;
绘制_从右上角到左下角 = GradientDrawable.Orientation.TR_BL;
绘制_从右往左 = GradientDrawable.Orientation.RIGHT_LEFT;
绘制_从右下角到左上角 = GradientDrawable.Orientation.BR_TL;
绘制_从下往上 = GradientDrawable.Orientation.BOTTOM_TOP;
绘制_从左下角到右上角 = GradientDrawable.Orientation.BL_TR;
绘制_从左往右 = GradientDrawable.Orientation.LEFT_RIGHT;
绘制_从左上角到右下角 = GradientDrawable.Orientation.TL_BR;
形状_矩形 = GradientDrawable.RECTANGLE;
形状_圆形 = GradientDrawable.OVAL;
形状_线 = GradientDrawable.LINE;
形状_环 = GradientDrawable.RING;
}
}
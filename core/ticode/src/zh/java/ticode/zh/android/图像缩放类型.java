package ticode.zh.android;

import android.widget.ImageView.ScaleType;
import android.content.*;
import android.content.res.*;
import android.view.*;
import android.widget.*;
import android.text.*;
import android.text.style.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.webkit.*;

public class 图像缩放类型 {
public static final android.widget.ImageView.ScaleType 矩阵;
public static final android.widget.ImageView.ScaleType 完全拉伸;
public static final android.widget.ImageView.ScaleType 左上;
public static final android.widget.ImageView.ScaleType 自适应居中;
public static final android.widget.ImageView.ScaleType 右下;
public static final android.widget.ImageView.ScaleType 居中;
public static final android.widget.ImageView.ScaleType 裁切居中;
public static final android.widget.ImageView.ScaleType 内置居中;

static {
矩阵=ScaleType.MATRIX;
完全拉伸=ScaleType.FIT_XY;
左上=ScaleType.FIT_START;
自适应居中=ScaleType.FIT_CENTER;
右下=ScaleType.FIT_END;
居中=ScaleType.CENTER;
裁切居中=ScaleType.CENTER_CROP;
内置居中=ScaleType.CENTER_INSIDE;
}

}
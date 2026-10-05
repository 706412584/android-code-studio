package ticode.zh.android;

import android.graphics.drawable.Drawable;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Bitmap.Config;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.*;
import android.graphics.drawable.*;
import android.graphics.drawable.shapes.*;
import android.content.res.*;
import android.os.Build;

import ticode.zh.base.长整数类;
import ticode.zh.jvm.文件输入流;
import ticode.zh.jvm.文件输出流;
import ticode.zh.jvm.输入流;
import ticode.zh.jvm.输出流;

public class 位图配置 {

public static final android.graphics.Bitmap.Config ALPHA_8;

public static final android.graphics.Bitmap.Config RGB_565;

public static final android.graphics.Bitmap.Config ARGB_4444;

public static final android.graphics.Bitmap.Config ARGB_8888;

public static final android.graphics.Bitmap.Config RGBA_F16;

public static final android.graphics.Bitmap.Config HARDWARE;

public static android.graphics.Bitmap.Config 到配置(String 配置名) {
return Config.valueOf(配置名);
}

public static android.graphics.Bitmap.Config[] 配置列表() {
return Config.values();
}

static {
ALPHA_8=Config.ALPHA_8;
RGB_565=Config.RGB_565;
ARGB_4444=Config.ARGB_4444;
ARGB_8888=Config.ARGB_8888;
RGBA_F16=Config.RGBA_F16;
HARDWARE=Config.HARDWARE;
}
}
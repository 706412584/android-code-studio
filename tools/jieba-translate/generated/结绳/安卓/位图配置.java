package 结绳.安卓;

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

public class 位图配置 {

public static final 位图配置 ALPHA_8;

public static final 位图配置 RGB_565;

public static final 位图配置 ARGB_4444;

public static final 位图配置 ARGB_8888;

public static final 位图配置 RGBA_F16;

public static final 位图配置 HARDWARE;

public 位图配置 到配置(String 配置名) {
return Config.valueOf(配置名);
}

public 位图配置[] 配置列表() {
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

//=============================================================

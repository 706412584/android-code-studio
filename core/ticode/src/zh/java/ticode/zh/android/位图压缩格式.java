package ticode.zh.android;

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
import android.os.Build;

import ticode.zh.base.长整数类;
import ticode.zh.jvm.文件输入流;
import ticode.zh.jvm.文件输出流;
import ticode.zh.jvm.输入流;
import ticode.zh.jvm.输出流;

public class 位图压缩格式 {
public static final android.graphics.Bitmap.CompressFormat JPEG = CompressFormat.JPEG;

public static final android.graphics.Bitmap.CompressFormat PNG = CompressFormat.PNG;

public static final android.graphics.Bitmap.CompressFormat WEBP = CompressFormat.WEBP;

public static final android.graphics.Bitmap.CompressFormat WEBP_有损 = CompressFormat.WEBP_LOSSY;

public static final android.graphics.Bitmap.CompressFormat WEBP_无损 = CompressFormat.WEBP_LOSSLESS;

}
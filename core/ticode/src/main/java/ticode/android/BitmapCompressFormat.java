package ticode.android;

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

import ticode.base.LongBox;
import ticode.jvm.FileInputStream2;
import ticode.jvm.FileOutputStream2;
import ticode.jvm.JInputStream;
import ticode.jvm.JOutputStream;

public class BitmapCompressFormat {
public static final android.graphics.Bitmap.CompressFormat JPEG = CompressFormat.JPEG;

public static final android.graphics.Bitmap.CompressFormat PNG = CompressFormat.PNG;

public static final android.graphics.Bitmap.CompressFormat WEBP = CompressFormat.WEBP;

public static final android.graphics.Bitmap.CompressFormat WEBP_有损 = CompressFormat.WEBP_LOSSY;

public static final android.graphics.Bitmap.CompressFormat WEBP_无损 = CompressFormat.WEBP_LOSSLESS;

}
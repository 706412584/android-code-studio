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

public class BitmapCompressFormat extends android.graphics.Bitmap.CompressFormat {
public static final BitmapCompressFormat JPEG = CompressFormat.JPEG;

public static final BitmapCompressFormat PNG = CompressFormat.PNG;

public static final BitmapCompressFormat WEBP = CompressFormat.WEBP;

public static final BitmapCompressFormat WEBP_有损 = CompressFormat.WEBP_LOSSY;

public static final BitmapCompressFormat WEBP_无损 = CompressFormat.WEBP_LOSSLESS;

}
package ticode.zh.android;

import android.graphics.Bitmap.CompressFormat;
import android.graphics.*;
import android.graphics.drawable.*;
import android.graphics.drawable.shapes.*;
import android.content.res.*;

public class 位图压缩格式 {
public static final android.graphics.Bitmap.CompressFormat JPEG = CompressFormat.JPEG;

public static final android.graphics.Bitmap.CompressFormat PNG = CompressFormat.PNG;

public static final android.graphics.Bitmap.CompressFormat WEBP = CompressFormat.WEBP;

// WEBP_LOSSY / WEBP_LOSSLESS 是 API 30 枚举常量；低版本类加载时静态初始化会
// NoSuchFieldError。用反射取，缺失则回退到 WEBP。
public static final android.graphics.Bitmap.CompressFormat WEBP_有损 = 压缩格式("WEBP_LOSSY");

public static final android.graphics.Bitmap.CompressFormat WEBP_无损 = 压缩格式("WEBP_LOSSLESS");

private static android.graphics.Bitmap.CompressFormat 压缩格式(String 名) {
try {
return (android.graphics.Bitmap.CompressFormat)
android.graphics.Bitmap.CompressFormat.class.getField(名).get(null);
} catch (Throwable t) {
return CompressFormat.WEBP;
}
}
}
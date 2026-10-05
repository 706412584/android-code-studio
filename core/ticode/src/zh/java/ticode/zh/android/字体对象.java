package ticode.zh.android;

import android.view.View;
import android.content.Context;
import android.graphics.Canvas;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import android.os.SystemClock;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.SurfaceHolder;
import android.graphics.PixelFormat;
import java.util.Map;
import java.util.HashMap;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Rect;

import ticode.zh.base.对象;

public class 字体对象 {
public android.graphics.Typeface 默认字体() {
return android.graphics.Typeface.DEFAULT;
}

public android.graphics.Typeface 默认粗体字体() {
return android.graphics.Typeface.DEFAULT_BOLD;
}

public android.graphics.Typeface 等宽字体() {
return android.graphics.Typeface.MONOSPACE;
}

public android.graphics.Typeface 衬线字体() {
return android.graphics.Typeface.SERIF;
}

public android.graphics.Typeface 无衬线字体() {
return android.graphics.Typeface.SANS_SERIF;
}

public static android.graphics.Typeface 从资源文件创建字体(安卓窗口 窗口环境, String 文件名) {
return android.graphics.Typeface.createFromAsset(窗口环境.getAssets(), 文件名);
}

public static android.graphics.Typeface 从文件路径创建字体(String 文件路径) {
return android.graphics.Typeface.createFromFile(文件路径);
}
}
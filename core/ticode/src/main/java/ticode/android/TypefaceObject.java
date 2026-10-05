package ticode.android;

import android.view.View;
import android.content.Context;
import android.graphics.Canvas;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import android.os.SystemClock;
import android.view.View;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.SurfaceHolder;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import java.util.Map;
import java.util.HashMap;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Path;
import android.graphics.RectF;

public class TypefaceObject extends android.graphics.Typeface {
public TypefaceObject 默认字体() {
return android.graphics.Typeface.DEFAULT;
}

public TypefaceObject 默认粗体字体() {
return android.graphics.Typeface.DEFAULT_BOLD;
}

public TypefaceObject 等宽字体() {
return android.graphics.Typeface.MONOSPACE;
}

public TypefaceObject 衬线字体() {
return android.graphics.Typeface.SERIF;
}

public TypefaceObject 无衬线字体() {
return android.graphics.Typeface.SANS_SERIF;
}

public static TypefaceObject 从资源文件创建字体(AndroidActivity 窗口环境, String 文件名) {
return android.graphics.Typeface.createFromAsset(窗口环境.getAssets(), 文件名);
}

public static TypefaceObject 从文件路径创建字体(String 文件路径) {
return android.graphics.Typeface.createFromFile(文件路径);
}
}
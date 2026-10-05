package 结绳.安卓;

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

public class 画板 extends 可视化组件 {
public 画板(Context context) {
super(context);
}

public View onCreateView(Context context) {
View view = new View(context) {
protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
super.onMeasure(widthMeasureSpec, heightMeasureSpec);
被测量(widthMeasureSpec, heightMeasureSpec);
}

protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
super.onLayout(changed, left, top, right, bottom);
被布局(changed, left, top, right - left, bottom - top);
}

protected void onSizeChanged(int w, int h, int oldw, int oldh) {
super.onSizeChanged(w, h, oldw, oldh);
被改变(w, h, oldw, oldh);
}

protected void onDraw(Canvas canvas) {
super.onDraw(canvas);
绘制操作(canvas);
}
};
return view;
}

public View getView() {
return view;
}

public void 被测量(int 宽度, int 高度) { return null; } // 事件

public void 被布局(boolean 是否变化, int 左, int 上, int 宽度, int 高度) { return null; } // 事件

public void 被改变(int 新宽度, int 新高度, int 旧宽度, int 旧高度) { return null; } // 事件

public void 绘制操作(画布对象 画布) { return null; } // 事件
}


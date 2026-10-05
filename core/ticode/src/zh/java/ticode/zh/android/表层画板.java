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
import android.graphics.Color;
import android.graphics.PixelFormat;
import java.util.Map;
import java.util.HashMap;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Rect;

import ticode.zh.base.对象;

public class 表层画板 extends 可视化组件 {
private AtomicInteger drawCount;
private Surface surface;
private SurfaceView surfaceView;
private SurfaceHolder surfaceHolder;
private ReentrantLock surfaceLock;
private long lastLockTime;
private long lastFpsTime;
private int tempFps, fps;
private Thread thread;

public 表层画板(Context context) {
super(context);
drawCount = new AtomicInteger();
surfaceHolder = surfaceView.getHolder();
if (android.os.Build.VERSION.SDK_INT < 26) {
surface = surfaceHolder.getSurface();
try {
Field mSurfaceLockField = SurfaceView.class.getDeclaredField("mSurfaceLock");
mSurfaceLockField.setAccessible(true);
surfaceLock = (ReentrantLock) mSurfaceLockField.get(surfaceView);
} catch (Exception e) {
throw new RuntimeException(e);
}
}
surfaceHolder.addCallback(this);
thread = new Thread(this);
thread.setPriority(Thread.MAX_PRIORITY);
thread.start();
}

@Override
public SurfaceView onCreateView(Context context) {
surfaceView = new SurfaceView(context);
return surfaceView;
}

@Override
public SurfaceView getView() {
return surfaceView;
}

@Override
public void run() {
while (true) {
if (drawCount.get() <= 0) continue;
Canvas canvas = null;
if (surface != null) {
if (surfaceLock != null) surfaceLock.lock();
if (drawCount.get() > 0) {
if (android.os.Build.VERSION.SDK_INT >= 23) {
canvas = surface.lockHardwareCanvas();
} else {
canvas = surface.lockCanvas(null);
}
}
} else {
canvas = surfaceHolder.lockHardwareCanvas();
}
if (canvas != null) {
lastLockTime = SystemClock.uptimeMillis();
try {
绘制操作(canvas);
drawCount.decrementAndGet();
if (SystemClock.uptimeMillis() - lastFpsTime >= 1000) {
fps = tempFps;
tempFps = 0;
drawCount.set(1);
lastFpsTime = SystemClock.uptimeMillis();
} else {
tempFps++;
}
} finally {
if (surface != null) {
try {
surface.unlockCanvasAndPost(canvas);
} finally {
surfaceLock.unlock();
}
} else {
surfaceHolder.unlockCanvasAndPost(canvas);
}
}
continue;
}
if (surface != null) {
long nowTime = SystemClock.uptimeMillis();
long nextTime = lastLockTime + 100;
if (nextTime > nowTime) {
try {
Thread.sleep(nextTime - nowTime);
} catch (Exception e) {
}
nowTime = SystemClock.uptimeMillis();
}
lastLockTime = nowTime;
if (surfaceLock != null) surfaceLock.unlock();
}
}
}

@Override
public void surfaceCreated(SurfaceHolder holder) {
被创建();
}

@Override
public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
被改变(width, height);
boolean needLock = (surface != null && surfaceLock != null);
if (needLock) surfaceLock.lock();
drawCount.set(1);
if (needLock) surfaceLock.unlock();
}

@Override
public void surfaceDestroyed(SurfaceHolder holder) {
被销毁();
boolean needLock = (surface != null && surfaceLock != null);
if (needLock) surfaceLock.lock();
drawCount.set(0);
if (needLock) surfaceLock.unlock();
}

public int FPS帧数() {
return fps;
}

public void 常亮显示(boolean 是否常亮显示) {
surfaceHolder.setKeepScreenOn(是否常亮显示);
}

public void 顶层显示(boolean 是否顶层显示) {
surfaceHolder.setFormat(PixelFormat.TRANSPARENT);
surfaceView.setZOrderOnTop(是否顶层显示);
}

public void 覆盖显示(boolean 是否覆盖显示) {
surfaceView.setZOrderMediaOverlay(是否覆盖显示);
}

public void 刷新显示() {
drawCount.incrementAndGet();
}

public void 绘制操作(画布对象 画布) { } // 事件

public void 被创建() { } // 事件

public void 被改变(int 宽度, int 高度) { } // 事件

public void 被销毁() { } // 事件
}
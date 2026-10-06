package ticode.zh.android;

import android.graphics.Canvas;
import java.util.Map;
import java.util.HashMap;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Bitmap;

public class 画布对象 extends android.graphics.Canvas {
public 画布对象(android.graphics.Bitmap 位图) { super(位图); }
public static 画笔对象 默认画笔 = 画笔对象.创建画笔();

private static Path path;
private static RectF rectF;
private static BitmapCacheHandler bitmapCacheHandler;

private static class BitmapCache {
long lastTime;
Bitmap bitmap;
}

private static class BitmapCacheHandler extends Thread {
private Map<Object, BitmapCache> caches = new HashMap<>();
private Object lock = new Object();

public BitmapCacheHandler() {
start();
}

public BitmapCache getCache(Object key) {
return caches.get(key);
}

public void putCache(Object key, BitmapCache cache) {
synchronized (lock) {
caches.put(key, cache);
lock.notify();
}
}

@Override
public void run() {
while (true) {
synchronized (lock) {
if (caches.isEmpty()) {
try {
lock.wait();
} catch (Exception e) {
}
}
for (Map.Entry<Object, BitmapCache> entry : caches.entrySet()) {
long time = System.currentTimeMillis();
if (time - entry.getValue().lastTime >= 60000) {
caches.remove(entry.getKey());
}
}
}
try {
Thread.sleep(30000);
} catch (Exception e) {
}
}
}
}

private static BitmapCacheHandler getBitmapCacheHandler() {
if (bitmapCacheHandler == null) {
synchronized (BitmapCacheHandler.class) {
if (bitmapCacheHandler == null) {
bitmapCacheHandler = new BitmapCacheHandler();
}
}
}
return bitmapCacheHandler;
}

public static 画布对象 创建画布(android.graphics.Bitmap 位图) {
return new 画布对象(位图);
}

public int 保存() {
return this.save();
}

public void 恢复() {
this.restore();
}

public int 当前状态() {
return this.getSaveCount();
}

public void 恢复到指定状态(int 状态) {
this.restoreToCount(状态);
}

public void 平移(float X坐标, float Y坐标) {
this.translate(X坐标, Y坐标);
}

public void 旋转(float 角度) {
this.rotate(角度);
}

public void 缩放(float X缩放比例, float Y缩放比例) {
this.scale(X缩放比例, Y缩放比例);
}

// x：x轴倾斜角度的正切值，y：y轴倾斜角度的正切值)
public void 倾斜画布(float x, float y) {
this.skew(x, y);
}

public void 填充(int 颜色值) {
this.drawColor(颜色值);
}

public void 画点(float X坐标, float Y坐标, 画笔对象 画笔) {
this.drawPoint(X坐标, Y坐标, 画笔);
}

public void 画线(float 起始X坐标, float 起始Y坐标, float 结束X坐标, float 结束Y坐标, 画笔对象 画笔) {
this.drawLine(起始X坐标, 起始Y坐标, 结束X坐标, 结束Y坐标, 画笔);
}

public void 画圆(float X坐标, float Y坐标, float 半径, 画笔对象 画笔) {
this.drawCircle(X坐标, Y坐标, 半径, 画笔);
}

public void 画椭圆(float X坐标, float Y坐标, float 宽度, float 高度, 画笔对象 画笔) {
this.drawOval(X坐标, Y坐标, X坐标 + 宽度, Y坐标 + 高度, 画笔);
}

public void 画矩形(float X坐标, float Y坐标, float 宽度, float 高度, 画笔对象 画笔) {
this.drawRect(X坐标, Y坐标, X坐标 + 宽度, Y坐标 + 高度, 画笔);
}

public void 画圆角矩形(float X坐标, float Y坐标, float 宽度, float 高度, float X圆角, float Y圆角, 画笔对象 画笔) {
this.drawRoundRect(X坐标, Y坐标, X坐标 + 宽度, Y坐标 + 高度, X圆角, Y圆角, 画笔);
}

public void 画圆弧(float X坐标, float Y坐标, float 宽度, float 高度, float 起始角度, float 扫描角度, boolean 椭圆中心点连接, 画笔对象 画笔) {
this.drawArc(X坐标, Y坐标, X坐标 + 宽度, Y坐标 + 高度, 起始角度, 扫描角度, 椭圆中心点连接, 画笔);
}

public void 画文字(float X坐标, float Y坐标, String 文字, 画笔对象 画笔) {
this.drawText(文字, X坐标, Y坐标 + (画笔.descent() - 画笔.ascent()), 画笔);
}

public void 画路径(构建路径 路径, 画笔对象 画笔) {
this.drawPath(路径, 画笔);
}

public void 矩形裁剪(float 左, float 上, float 右, float 下) {
this.clipRect(左,上,右,下);
}

public void 矩形裁剪2(android.graphics.Rect 参数) {
this.clipRect(参数);
}

public void 路径裁剪(构建路径 路径) {
this.clipPath(路径);
}

public boolean 是否在裁剪区域(float x, float y) {
// quickReject 是 Canvas 的隐藏 API（@hide），设备 framework 里没有 → NoSuchMethodError。
// 用 getClipBounds 做等价判定：点所在的 1x1 矩形是否完全落在裁剪区之外。
android.graphics.Rect 裁剪 = new android.graphics.Rect();
if (!this.getClipBounds(裁剪)) return false;   // 无裁剪区 → 不会被裁剪
android.graphics.Rect 点 = new android.graphics.Rect((int) x, (int) y, (int) x + 1, (int) y + 1);
return !android.graphics.Rect.intersects(裁剪, 点);
}

public void 画贝塞尔曲线(float 起始X坐标, float 起始Y坐标, float 辅助X坐标, float 辅助Y坐标, float 结束X坐标, float 结束Y坐标, 画笔对象 画笔) {
if (path == null) {
path = new Path();
}
path.moveTo(起始X坐标, 起始Y坐标);
path.quadTo(辅助X坐标, 辅助Y坐标, 结束X坐标, 结束Y坐标);
this.drawPath(path, 画笔);
}

public void 画位图(float X坐标, float Y坐标, android.graphics.Bitmap 位图, 画笔对象 画笔) {
if (位图 == null || 位图.isRecycled()) return;
this.drawBitmap(位图, X坐标, Y坐标, 画笔);
}

public void 画缩放位图(float X坐标, float Y坐标, float 宽度, float 高度, android.graphics.Bitmap 位图, 画笔对象 画笔) {
if (位图 == null || 位图.isRecycled()) return;
if (rectF == null) {
rectF = new RectF();
}
rectF.left = X坐标;
rectF.top = Y坐标;
rectF.right = X坐标 + 宽度;
rectF.bottom = Y坐标 + 高度;
this.drawBitmap(位图, null, rectF, 画笔);
}

public void 画图片(float X坐标, float Y坐标, String 图片路径, 画笔对象 画笔) {
BitmapCache bitmapCache = getBitmapCacheHandler().getCache(图片路径);
if (bitmapCache == null) {
bitmapCache = new BitmapCache();
bitmapCache.bitmap = 位图对象.从文件路径创建位图(图片路径);
bitmapCacheHandler.putCache(图片路径, bitmapCache);
}
bitmapCache.lastTime = System.currentTimeMillis();
画位图(X坐标, Y坐标, bitmapCache.bitmap, 画笔);
}

public void 画资源图片(float X坐标, float Y坐标, String 图片路径, 画笔对象 画笔) {
BitmapCache bitmapCache = getBitmapCacheHandler().getCache(图片路径);
if (bitmapCache == null) {
bitmapCache = new BitmapCache();
bitmapCache.bitmap = 位图对象.从资源文件创建位图(安卓应用.取安卓应用(), 图片路径);
bitmapCacheHandler.putCache(图片路径, bitmapCache);
}
bitmapCache.lastTime = System.currentTimeMillis();
画位图(X坐标, Y坐标, bitmapCache.bitmap, 画笔);
}

public void 画缩放图片(float X坐标, float Y坐标, float 宽度, float 高度, String 图片路径, 画笔对象 画笔) {
BitmapCache bitmapCache = getBitmapCacheHandler().getCache(图片路径);
if (bitmapCache == null) {
bitmapCache = new BitmapCache();
bitmapCache.bitmap = 位图对象.从文件路径创建位图(图片路径);
bitmapCacheHandler.putCache(图片路径, bitmapCache);
}
bitmapCache.lastTime = System.currentTimeMillis();
画缩放位图(X坐标, Y坐标, 宽度, 高度, bitmapCache.bitmap, 画笔);
}

public void 画资源缩放图片(float X坐标, float Y坐标, float 宽度, float 高度, String 图片路径, 画笔对象 画笔) {
BitmapCache bitmapCache = getBitmapCacheHandler().getCache(图片路径);
if (bitmapCache == null) {
bitmapCache = new BitmapCache();
bitmapCache.bitmap = 位图对象.从资源文件创建位图(安卓应用.取安卓应用(), 图片路径);
bitmapCacheHandler.putCache(图片路径, bitmapCache);
}
bitmapCache.lastTime = System.currentTimeMillis();
画缩放位图(X坐标, Y坐标, 宽度, 高度, bitmapCache.bitmap, 画笔);
}
}
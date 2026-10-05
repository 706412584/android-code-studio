package ticode.android;

import android.graphics.drawable.Drawable;
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

public class BitmapObject extends android.graphics.Bitmap {

public BitmapObject 赋值_op(String 文件路径) {
return android.graphics.BitmapFactory.decodeFile(文件路径);
}

public static BitmapObject 从文件路径创建位图(String 文件路径) {
return android.graphics.BitmapFactory.decodeFile(文件路径);
}

public static BitmapObject 从字节集创建位图(byte[] 位图数据, int 偏移量, int 数据长度) {
return android.graphics.BitmapFactory.decodeByteArray(位图数据,偏移量,数据长度);
}

public static BitmapObject 从输入流创建位图(JInputStream 位图数据) {
return android.graphics.BitmapFactory.decodeStream(位图数据);
}

public static BitmapObject 从资源文件创建位图(AndroidEnv 上下文环境, String 文件名称) {
try {
return android.graphics.BitmapFactory.decodeStream(上下文环境.getAssets().open(文件名称));
} catch (Exception e) {
return null;
}
}

public static BitmapObject 创建缩放位图(BitmapObject 源位图, int 新宽度, int 新高度, boolean 启用双线性过滤) {
return Bitmap.createScaledBitmap(源位图,新宽度,新高度,启用双线性过滤);
}

public static BitmapObject 创建位图(BitmapObject 源位图) {
return Bitmap.createBitmap(源位图);
}

public static BitmapObject 创建位图2(int 宽度, int 高度, android.graphics.Bitmap.Config 配置) {
return Bitmap.createBitmap(宽度,高度,配置);
}

public static BitmapObject 创建位图3(int 宽度, int 高度, android.graphics.Bitmap.Config 配置, boolean 存在透明度) {
return Bitmap.createBitmap(宽度,高度,配置,存在透明度);
}

public static BitmapObject 创建位图4(int[] 颜色组, int 宽度, int 高度, android.graphics.Bitmap.Config 配置) {
return Bitmap.createBitmap(颜色组,宽度,高度,配置);
}

public static BitmapObject 创建位图5(DrawableObject 转换对象, android.graphics.Bitmap.Config 配置) {
// 创建一个Bitmap对象，尺寸与Drawable一致
Bitmap 位图 = Bitmap.createBitmap(转换对象.getIntrinsicWidth(),转换对象.getIntrinsicHeight(),配置);
// 使用Canvas在Bitmap上绘制Drawable
Canvas 画布 = new Canvas(位图);
转换对象.setBounds(0, 0, 画布.getWidth(), 画布.getHeight());
转换对象.draw(画布);
// 返回绘制好的Bitmap对象
return 位图;
}

public int 密度() {
return this.getDensity();
}

public void 密度(int 欲设置密度) {
this.setDensity(欲设置密度);
}

public void 宽度(int 欲设置宽度) {
this.setWidth(欲设置宽度);
}

public int 宽度() {
return this.getWidth();
}

public void 高度(int 欲设置高度) {
this.setHeight(欲设置高度);
}

public int 高度() {
return this.getHeight();
}

public void 配置(android.graphics.Bitmap.Config 位图配置) {
this.setConfig(位图配置);
}

public android.graphics.Bitmap.Config 配置() {
return this.getConfig();
}

public void 重新配置(int 宽度, int 高度, android.graphics.Bitmap.Config 配置) {
this.reconfigure(宽度,高度,配置);
}

public void 回收() {
this.recycle();
}

public boolean 是否已被回收() {
return this.isRecycled();
}

public BitmapObject 拷贝(android.graphics.Bitmap.Config 配置, boolean 是否可以改变) {
return this.copy(配置,是否可以改变);
}

public boolean 压缩输出(android.graphics.Bitmap.CompressFormat 压缩格式, int 压缩质量, String 输出路径) {
return 压缩输出_输出流(压缩格式, 压缩质量, FileOutputStream2.从路径创建(输出路径));
}

public boolean 压缩输出_输出流(android.graphics.Bitmap.CompressFormat 压缩格式, int 压缩质量, JOutputStream 输出流) {
return this.compress(压缩格式,压缩质量,输出流);
}

public boolean 取是否预乘位图() {
return this.isPremultiplied();
}

public void 预乘位图(boolean 是否预乘位图) {
this.setPremultiplied(是否预乘位图);
}

public int 取缩放高度(int 目标位图密度) {
return this.getScaledHeight(目标位图密度);
}

public int 取缩放宽度(int 目标位图密度) {
return this.getScaledWidth(目标位图密度);
}
//返回位图像素中行之间的字节数
public int 取行字节() {
return this.getRowBytes();
}
//返回可用于存储此位图像素的最小字节数
public int 取字节() {
return this.getByteCount();
}
//返回用于存储此位图像素的分配内存的大小
public int 取分配字节数() {
return this.getAllocationByteCount();
}

public boolean 取是否存在透明值() {
return this.hasAlpha();
}

public void 透明值(boolean 存在透明值) {
this.setHasAlpha(存在透明值);
}

public boolean 取是否细化纹理() {
return this.hasMipMap();
}

public void 细化纹理(boolean 是否细化纹理) {
this.setHasMipMap(是否细化纹理);
}

public void 擦除颜色(int 颜色) {
this.eraseColor(颜色);
}

public void 擦除颜色2(LongBox 颜色) {
this.eraseColor(颜色);
}
//返回指定位置的Color
public int 取像素(int 横坐标, int 纵坐标) {
return this.getPixel(横坐标,纵坐标);
}

public BitmapObject 取透明通道() {
return this.extractAlpha();
}

public boolean 等同(BitmapObject BitmapObject) {
return this.sameAs(BitmapObject);
}

public static BitmapObject 放大图片(BitmapObject 图片, int 倍率) {
if ((图片 == null || 倍率 <= 0)) {
return null;
}
int 宽度 = 图片.宽度;
int 高度 = 图片.高度;
int 新宽度 = 宽度 * 倍率;
int 新高度 = 高度 * 倍率;
int[] 像素数据组 = new int[宽度*高度];
图片.getPixels(像素数据组, 0, 宽度, 0, 0, 宽度, 高度);
int[] 新像素数据组 = new int[新宽度*新高度];
for (int y = 0; y < 高度; y++) {
for (int x = 0; x < 宽度; x++) {
int 像素数据 = 像素数据组[y*宽度+x];
for (int y2 = 0; y2 < 倍率; y2++) {
for (int x2 = 0; x2 < 倍率; x2++) {
int 位置y = y*倍率+y2;
int 位置x = x*倍率+x2;
新像素数据组[位置y*新宽度+位置x] = 像素数据;
}
}
}
}
BitmapObject 新图片 = BitmapObject.创建位图2(新高度,新宽度,图片.配置);
新图片.setPixels(新像素数据组, 0, 新宽度, 0, 0, 新宽度, 新高度);
return 新图片;
}

}
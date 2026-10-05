package ticode.zh.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.*;
import android.graphics.drawable.*;
import android.graphics.drawable.shapes.*;
import android.content.res.*;

import ticode.zh.base.长整数类;
import ticode.zh.jvm.输入流;
import ticode.zh.jvm.输出流;

public class 位图对象 {

public android.graphics.Bitmap 赋值_op(String 文件路径) {return null; }

public static android.graphics.Bitmap 从文件路径创建位图(String 文件路径) {
return android.graphics.BitmapFactory.decodeFile(文件路径);
}

public static android.graphics.Bitmap 从字节集创建位图(byte[] 位图数据, int 偏移量, int 数据长度) {
return android.graphics.BitmapFactory.decodeByteArray(位图数据,偏移量,数据长度);
}

public static android.graphics.Bitmap 从输入流创建位图(输入流 位图数据) {
return android.graphics.BitmapFactory.decodeStream(位图数据);
}

public static android.graphics.Bitmap 从资源文件创建位图(android.content.Context 上下文环境, String 文件名称) {
try {
return android.graphics.BitmapFactory.decodeStream(上下文环境.getAssets().open(文件名称));
} catch (Exception e) {
return null;
}
}

public static android.graphics.Bitmap 创建缩放位图(android.graphics.Bitmap 源位图, int 新宽度, int 新高度, boolean 启用双线性过滤) {
return Bitmap.createScaledBitmap(源位图,新宽度,新高度,启用双线性过滤);
}

public static android.graphics.Bitmap 创建位图(android.graphics.Bitmap 源位图) {
return Bitmap.createBitmap(源位图);
}

public static android.graphics.Bitmap 创建位图2(int 宽度, int 高度, android.graphics.Bitmap.Config 配置) {
return Bitmap.createBitmap(宽度,高度,配置);
}

public static android.graphics.Bitmap 创建位图3(int 宽度, int 高度, android.graphics.Bitmap.Config 配置, boolean 存在透明度) {
return Bitmap.createBitmap(宽度,高度,配置,存在透明度);
}

public static android.graphics.Bitmap 创建位图4(int[] 颜色组, int 宽度, int 高度, android.graphics.Bitmap.Config 配置) {
return Bitmap.createBitmap(颜色组,宽度,高度,配置);
}

public static android.graphics.Bitmap 创建位图5(可绘制对象 转换对象, android.graphics.Bitmap.Config 配置) {
// 创建一个Bitmap对象，尺寸与Drawable一致
Bitmap 位图 = Bitmap.createBitmap(转换对象.getIntrinsicWidth(),转换对象.getIntrinsicHeight(),配置);
// 使用Canvas在Bitmap上绘制Drawable
Canvas 画布 = new Canvas(位图);
转换对象.setBounds(0, 0, 画布.getWidth(), 画布.getHeight());
转换对象.draw(画布);
// 返回绘制好的Bitmap对象
return 位图;
}

public int 密度() {return 0; }

public void 密度(int 欲设置密度) {}

public void 宽度(int 欲设置宽度) {}

public int 宽度() {return 0; }

public void 高度(int 欲设置高度) {}

public int 高度() {return 0; }

public void 配置(android.graphics.Bitmap.Config 位图配置) {}

public android.graphics.Bitmap.Config 配置() {return null; }

public void 重新配置(int 宽度, int 高度, android.graphics.Bitmap.Config 配置) {}

public void 回收() {}

public boolean 是否已被回收() {return false; }

public android.graphics.Bitmap 拷贝(android.graphics.Bitmap.Config 配置, boolean 是否可以改变) {return null; }

public boolean 压缩输出(android.graphics.Bitmap.CompressFormat 压缩格式, int 压缩质量, String 输出路径) {return false; }

public boolean 压缩输出_输出流(android.graphics.Bitmap.CompressFormat 压缩格式, int 压缩质量, 输出流 输出流) {return false; }

public boolean 取是否预乘位图() {return false; }

public void 预乘位图(boolean 是否预乘位图) {}

public int 取缩放高度(int 目标位图密度) {return 0; }

public int 取缩放宽度(int 目标位图密度) {return 0; }
//返回位图像素中行之间的字节数
public int 取行字节() {return 0; }
//返回可用于存储此位图像素的最小字节数
public int 取字节() {return 0; }
//返回用于存储此位图像素的分配内存的大小
public int 取分配字节数() {return 0; }

public boolean 取是否存在透明值() {return false; }

public void 透明值(boolean 存在透明值) {}

public boolean 取是否细化纹理() {return false; }

public void 细化纹理(boolean 是否细化纹理) {}

public void 擦除颜色(int 颜色) {}

public void 擦除颜色2(Long 颜色) {}
//返回指定位置的Color
public int 取像素(int 横坐标, int 纵坐标) {return 0; }

public android.graphics.Bitmap 取透明通道() {return null; }

public boolean 等同(android.graphics.Bitmap 位图对象) {return false; }

public static android.graphics.Bitmap 放大图片(android.graphics.Bitmap 图片, int 倍率) {
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
android.graphics.Bitmap 新图片 = 位图对象.创建位图2(新高度,新宽度,图片.配置);
新图片.setPixels(新像素数据组, 0, 新宽度, 0, 0, 新宽度, 新高度);
return 新图片;
}

}
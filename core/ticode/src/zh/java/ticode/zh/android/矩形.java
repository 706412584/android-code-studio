package ticode.zh.android;

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

import ticode.zh.base.长整数类;
import ticode.zh.jvm.文件输入流;
import ticode.zh.jvm.文件输出流;
import ticode.zh.jvm.输入流;
import ticode.zh.jvm.输出流;

public class 矩形 extends android.graphics.Rect {

public void 赋值_op(int 左边, int 上边, int 右边, int 下边) {
return new android.graphics.Rect(左边,上边,右边,下边);
}

public int 上() {
return this.top;
}

public void 上(int 位置) {
this.top=位置;
}

public int 下() {
return this.bottom;
}

public void 下(int 位置) {
this.bottom=位置;
}

public int 左() {
return this.left;
}

public void 左(int 位置) {
this.left=位置;
}

public int 右() {
return this.right;
}

public void 右(int 位置) {
this.right=位置;
}

public boolean 是否为空() {
return this.isEmpty();
}

public void 清空() {
this.setEmpty();
}

public int 宽度() {
return this.width();
}

public int 高度() {
return this.height();
}

public int 中心横坐标() {
return this.centerX();
}

public int 中心纵坐标() {
return this.centerY();
}

public float 精确中心横坐标() {
return this.exactCenterX();
}

public float 精确中心纵坐标() {
return this.exactCenterY();
}

public void 设置(int 左边, int 上边, int 右边, int 下边) {
this.set(左边,上边,右边,下边);
}

public void 拷贝矩形(矩形 被拷贝对象) {
this.set(被拷贝对象);
}

public void 位置移动(int 横向距离, int 纵向距离) {
this.offset(横向距离,纵向距离);
}

public void 位置偏移(int 左边, int 上边) {
this.offsetTo(左边,上边);
}

public void 尺寸大小(int 横向尺寸, int 纵向尺寸) {
this.inset(横向尺寸,纵向尺寸);
}

public void 尺寸修改(int 左边, int 上边) {
this.inset(左边,上边);
}

public boolean 是否包含坐标(int 横向坐标, int 纵向坐标) {
return this.contains(横向坐标,纵向坐标);
}

public boolean 是否包含矩形(矩形 被判断对象) {
return this.contains(被判断对象);
}

public boolean 是否包含矩形区域(int 左边, int 上边, int 右边, int 下边) {
return this.contains(左边,上边,右边,下边);
}

public boolean 矩形相交(矩形 被判断对象) {
return this.intersect(被判断对象);
}

public boolean 多个矩形相交(矩形 被判断对象1, 矩形 被判断对象2) {
return this.setIntersect(被判断对象1,被判断对象2);
}

public boolean 矩形区域相交(int 左边, int 上边, int 右边, int 下边) {
return this.intersect(左边,上边,右边,下边);
}

public boolean 矩形是否相交(矩形 被判断对象) {
return this.intersect(被判断对象);
}

public boolean 矩形区域是否相交(int 左边, int 上边, int 右边, int 下边) {
return this.intersects(左边,上边,右边,下边);
}

public void 合并矩形坐标(int 横向坐标, int 纵向坐标) {
this.union(横向坐标,纵向坐标);
}

public void 合并矩形(矩形 被合并对象) {
this.union(被合并对象);
}

public void 合并矩形区域(int 左边, int 上边, int 右边, int 下边) {
this.union(左边,上边,右边,下边);
}

public void 规范化() {
this.sort();
}

}
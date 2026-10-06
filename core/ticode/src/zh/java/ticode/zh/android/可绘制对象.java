package ticode.zh.android;

import android.graphics.drawable.Drawable;
import android.graphics.*;
import android.graphics.drawable.*;
import android.graphics.drawable.shapes.*;
import android.content.res.*;

import ticode.zh.jvm.文件输入流;

public abstract class 可绘制对象 extends android.graphics.drawable.Drawable {

public 可绘制对象 赋值_op(String 文件路径) {
return (可绘制对象)可绘制对象.createFromPath(文件路径);
}

public static 可绘制对象 从路径创建(String 文件路径) {
return (可绘制对象)Drawable.createFromPath(文件路径);
}

public static 可绘制对象 从文件流创建(文件输入流 文件输入流, String 源名称) {
return (可绘制对象)Drawable.createFromStream(文件输入流,源名称);
}

public void 绘制区域(int 左边, int 上边, int 右边, int 下边) {
this.setBounds(左边,上边,右边,下边);
}

public void 绘制区域2(android.graphics.Rect 绘制区域) {
this.setBounds(绘制区域);
}

public void 从矩形拷贝区域(android.graphics.Rect 被绘制区域) {
this.copyBounds(被绘制区域);
}


public android.graphics.Rect 拷贝绘制区域到矩形() {
return (android.graphics.Rect)this.copyBounds();
}

public android.graphics.Rect 取绘制区域() {
return (android.graphics.Rect)this.getBounds();
}

public android.graphics.Rect 取抖动区域() {
return (android.graphics.Rect)this.getDirtyBounds();
}

public void 开启抖动(boolean 开启抖动算法) {
this.setDither(开启抖动算法);
}

public void 过滤位图(boolean 开启过滤) {
this.setFilterBitmap(开启过滤);
}

public boolean 是否过滤位图() {
return this.isFilterBitmap();
}

public void 色调(int 色调颜色) {
this.setTint(色调颜色);
}

public void 状态(int[] 状态集) {
this.setState(状态集);
}

public int[] 取状态() {
return this.getState();
}

public android.graphics.drawable.Drawable 取自身对象() {
// getCurrent 是框架原生实例，不是 可绘制对象 子类，强转必 ClassCastException。返回原生类型。
return this.getCurrent();
}

public android.graphics.Rect 取绘制间距(android.graphics.Rect 间距) {
this.getPadding(间距);return 间距;
}

public void 透明度(int 透明数值) {
this.setAlpha(透明数值);
}

public int 透明度() {
return this.getAlpha();
}

public void 等级(int 等级) {
this.setLevel(等级);
}

public int 等级() {
return this.getLevel();
}

public void 可见(boolean 是否可见, boolean 重新绘制) {
this.setVisible(是否可见,重新绘制);
}

public boolean 可见() {
return this.isVisible();
}

public void 自动镜像(boolean 是否自动镜像) {
this.setAutoMirrored(是否自动镜像);
}

public boolean 自动镜像() {
return this.isAutoMirrored();
}

public void 重新绘制() {
this.invalidateSelf();
}
}
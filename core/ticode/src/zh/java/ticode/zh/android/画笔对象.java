package ticode.zh.android;

import android.graphics.Paint;
import android.graphics.Rect;

public class 画笔对象 extends android.graphics.Paint {
// 具体化后必须显式调 super()：父类（Path/Paint/…）有 native 字段，
// 不调则实例未初始化，一用就 SIGSEGV。
public 画笔对象() { super(); }
public static final int 画笔类型_填充 = 1;
public static final int 画笔类型_描边 = 2;
public static final int 画笔类型_填充和描边 = 3;

public static 画笔对象 创建画笔() {
Paint paint = new Paint();
paint.setAntiAlias(true);
paint.setTextSize(45);
return new 画笔对象();
}

public float 文字高度() {
return this.descent() - this.ascent();
}

public void 抗锯齿(boolean 开启抗锯齿) {
this.setAntiAlias(开启抗锯齿);
}

public void 类型(int 类型) {
switch (类型) {
case 1:
this.setStyle(Paint.Style.FILL);
break;
case 2:
this.setStyle(Paint.Style.STROKE);
break;
case 3:
this.setStyle(Paint.Style.FILL_AND_STROKE);
break;
}
}

public int 颜色值() {
return this.getColor();
}

public void 颜色值(int 颜色值) {
this.setColor(颜色值);
}

public float 文字大小() {
return this.getTextSize();
}

public void 文字大小(float 文字大小) {
this.setTextSize(文字大小);
}

public float 宽度() {
return this.getStrokeWidth();
}

public void 宽度(float 宽度) {
this.setStrokeWidth(宽度);
}

public int 透明度() {
return this.getAlpha();
}

public void 透明度(int 透明度) {
this.setAlpha(透明度);
}

public void 字体(android.graphics.Typeface 字体) {
this.setTypeface(字体);
}

public float 测量文字宽度(String 文字) {
return this.measureText(文字);
}

public android.graphics.Rect 测量文字界限(String 文字) {
Rect bounds = new Rect();
this.getTextBounds(文字, 0, 文字.length(), bounds);
return (android.graphics.Rect)bounds;
}
}
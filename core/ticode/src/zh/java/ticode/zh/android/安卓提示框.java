package ticode.zh.android;

import android.media.*;
import android.os.*;
import java.util.*;

public abstract class 安卓提示框 extends android.widget.Toast {
public 安卓提示框() { super(null); }
public static final int 长时 = 1;
public static final int 短时 = 0;

public static 安卓提示框 新建提示框(android.content.Context 环境) {
return (安卓提示框)android.widget.Toast.makeText(环境,"",0);
}

public static void 弹出提示(android.content.Context 环境, String 内容, boolean 长时显示) {
android.widget.Toast.makeText(环境, 内容, 长时显示 ? 1 : 0).show();
}




public int 显示时长() {
return this.getDuration();
}




public void 显示时长(int 时长) {
this.setDuration(时长);
}




public 可视化组件 布局() {
// getTag() 可能为 null / 非 可视化组件；直接强转会崩。
Object tag = this.getView().getTag();
return tag instanceof 可视化组件 ? (可视化组件) tag : null;
}




public void 布局(可视化组件 组件) {
this.setView(组件.getView());
}




public void 内容(String 内容) {
this.setText(内容);
}




public int 横向边距() {
return (int) this.getHorizontalMargin();
}




public int 纵向边距() {
return (int) this.getVerticalMargin();
}




public int 横向偏移() {
return this.getXOffset();
}




public int 纵向偏移() {
return this.getYOffset();
}




public int 对齐方式() {
return this.getGravity();
}




public void 显示() {
this.show();
}




public void 取消() {
this.cancel();
}




public void 置边距(int 横向边距, int 纵向边距) {
this.setMargin(横向边距, 纵向边距);
}




public void 置对齐方式(int 对齐方式, int 横向偏移, int 纵向偏移) {
this.setGravity(对齐方式, 横向偏移, 纵向偏移);
}
}
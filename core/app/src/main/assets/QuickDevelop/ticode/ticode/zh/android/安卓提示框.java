package ticode.zh.android;

import android.media.*;
import android.os.*;
import java.util.*;

// 框架 Toast.makeText 返回的是**原生 Toast**，不是本壳子类 —— 原写法
// `return (安卓提示框) Toast.makeText(...)` 必 ClassCastException。
// 且本壳构造器只能 super(null)，实例方法（getDuration 等）跑在空 native 上也会崩。
// 故改为「持有原生 Toast + 全部委托」。
public class 安卓提示框 extends android.widget.Toast {
public static final int 长时 = 1;
public static final int 短时 = 0;

private final android.widget.Toast 内部对象;

private 安卓提示框(android.widget.Toast 原) {
super(null);
this.内部对象 = 原;
}

public 安卓提示框() {
super(null);
this.内部对象 = null;
}

public static 安卓提示框 新建提示框(android.content.Context 环境) {
return new 安卓提示框(android.widget.Toast.makeText(环境, "", 0));
}

public static void 弹出提示(android.content.Context 环境, String 内容, boolean 长时显示) {
android.widget.Toast.makeText(环境, 内容, 长时显示 ? 1 : 0).show();
}

public int 显示时长() {
return 内部对象 == null ? 0 : 内部对象.getDuration();
}

public void 显示时长(int 时长) {
if (内部对象 != null) 内部对象.setDuration(时长);
}

public 可视化组件 布局() {
if (内部对象 == null) return null;
// getTag() 可能为 null / 非 可视化组件；直接强转会崩。
Object tag = 内部对象.getView() == null ? null : 内部对象.getView().getTag();
return tag instanceof 可视化组件 ? (可视化组件) tag : null;
}

public void 布局(可视化组件 组件) {
if (内部对象 != null && 组件 != null) 内部对象.setView(组件.getView());
}

public void 内容(String 内容) {
if (内部对象 != null) 内部对象.setText(内容);
}

public int 横向边距() {
return 内部对象 == null ? 0 : (int) 内部对象.getHorizontalMargin();
}

public int 纵向边距() {
return 内部对象 == null ? 0 : (int) 内部对象.getVerticalMargin();
}

public int 横向偏移() {
return 内部对象 == null ? 0 : 内部对象.getXOffset();
}

public int 纵向偏移() {
return 内部对象 == null ? 0 : 内部对象.getYOffset();
}

public int 对齐方式() {
return 内部对象 == null ? 0 : 内部对象.getGravity();
}

public void 显示() {
if (内部对象 != null) 内部对象.show();
}

public void 取消() {
if (内部对象 != null) 内部对象.cancel();
}

public void 置边距(int 横向边距, int 纵向边距) {
if (内部对象 != null) 内部对象.setMargin(横向边距, 纵向边距);
}

public void 置对齐方式(int 对齐方式, int 横向偏移, int 纵向偏移) {
if (内部对象 != null) 内部对象.setGravity(对齐方式, 横向偏移, 纵向偏移);
}
}

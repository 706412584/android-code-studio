package 结绳.安卓;

import android.media.*;
import android.os.*;
import java.util.*;
import android.os.Vibrator;
import android.content.Context;

public class 提示框 extends 窗口组件 {
public static final int 长时 = 1;
public static final int 短时 = 0;

安卓提示框 提示框对象;

public 提示框(android.content.Context context) {
super(context);
提示框对象 = android.widget.Toast.makeText(context, "", 0);
}




public int 显示时长() {
return 提示框对象.显示时长;
}




public void 显示时长(int 时长) {
提示框对象.显示时长 = 时长;
}




public 可视化组件 布局() {
return 提示框对象.布局;
}




public void 布局(可视化组件 组件) {
提示框对象.布局 = 组件;
}




public void 内容(String 内容) {
提示框对象.内容 = 内容;
}




public int 横向边距() {
return 提示框对象.横向边距;
}




public int 纵向边距() {
return 提示框对象.纵向边距;
}




public int 横向偏移() {
return 提示框对象.横向偏移;
}




public int 纵向偏移() {
return 提示框对象.纵向偏移;
}




public int 对齐方式() {
return 提示框对象.对齐方式;
}




public void 显示() {
提示框对象.显示();
}




public void 取消() {
提示框对象.取消();
}




public void 置边距(int 横向边距, int 纵向边距) {
提示框对象.置边距(横向边距, 纵向边距);
}




public void 置对齐方式(int 对齐方式, int 横向偏移, int 纵向偏移) {
提示框对象.置对齐方式(对齐方式, 横向偏移, 纵向偏移);
}

public void 弹出提示(安卓环境 环境, String 内容, boolean 长时显示) {
android.widget.Toast.makeText(环境, 内容, 长时显示 ? 1 : 0).show();
}
}






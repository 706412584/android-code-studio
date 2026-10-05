package ticode.zh.android;

import android.content.*;
import android.app.*;
import android.view.*;
import android.graphics.drawable.*;
import android.widget.*;
import java.util.*;
import android.graphics.*;

public class 对话框 extends 窗口组件 {

private AlertDialog dialog;
private AlertDialog.Builder builder;

public 对话框(Context context) {
super(context);
builder = new AlertDialog.Builder(context);
}
private 组件容器 container;

//设置对话框的标题
public void 标题(String 标题) {
builder.setTitle(标题);
}

//设置对话框要显示的信息
public void 信息(String 信息) {
builder.setMessage(信息);
}

//设置对话框图标, 参数为图片资源
public void 图标(int 图标) {
builder.setIcon(图标);
}

//设置对话框按钮1的文本
public void 按钮1(String 按钮1文本) {
builder.setPositiveButton(按钮1文本, new DialogInterface.OnClickListener(){
@Override
public void onClick(DialogInterface p1, int p2) {
按钮1被单击();
}
});
}

//设置对话框按钮2的文本
public void 按钮2(String 按钮2文本) {
builder.setNegativeButton(按钮2文本, new DialogInterface.OnClickListener(){
@Override
public void onClick(DialogInterface p1, int p2) {
按钮2被单击();
}
});
}

//设置对话框按钮3的文本
public void 按钮3(String 按钮3文本) {
builder.setNeutralButton(按钮3文本, new DialogInterface.OnClickListener(){
@Override
public void onClick(DialogInterface p1, int p2) {
按钮3被单击();
}
});
}

//设置对话框加载组件
public void 自定义布局(组件容器 布局) {
this.container = 布局;
builder.setView(container.getLayout().getView());
布局.布局被加载();
}

//获取对话框的自定义组件容器
public 组件容器 自定义布局() {
return (container);
}

//设置对话框透明度
public void 透明度(double 透明度) {
if(dialog == null) {
dialog = builder.create();
}
Window window = dialog.getWindow();
WindowManager.LayoutParams p = window.getAttributes();
p.alpha = (float) 透明度;
window.setAttributes(p);
}

//设置对话框动画主题
public void 动画主题(int 主题) {
if(dialog == null) {
dialog = builder.create();
}
Window window = dialog.getWindow();
window.setWindowAnimations(主题);
}

//设置对话框垂直方向的边距
public void 垂直边距(int 垂直边距) {
if(dialog == null) {
dialog = builder.create();
}
Window window = dialog.getWindow();
WindowManager.LayoutParams p = window.getAttributes();
p.verticalMargin = 垂直边距;
window.setAttributes(p);
}

//设置对话框水平方向的边距
public void 水平边距(int 水平边距) {
if(dialog == null) {
dialog = builder.create();
}
Window window = dialog.getWindow();
WindowManager.LayoutParams p = window.getAttributes();
p.horizontalMargin = 水平边距;
window.setAttributes(p);
}

//设置对话框对齐方式
public void 对齐方式(int 对齐方式) {
if(dialog == null) {
dialog = builder.create();
}
Window window = dialog.getWindow();
window.setGravity(对齐方式);
}

//设置对话框高度
public void 高度(int 高度) {
if(dialog == null) {
dialog = builder.create();
}
Window window = dialog.getWindow();
WindowManager.LayoutParams p = window.getAttributes();
p.height = 高度;
window.setAttributes(p);
}

//设置对话框宽度
public void 宽度(int 宽度) {
if(dialog == null) {
dialog = builder.create();
}
Window window = dialog.getWindow();
WindowManager.LayoutParams p = window.getAttributes();
p.width = 宽度;
window.setAttributes(p);
}

//设置对话框是否可取消，若设置为假，则不能通过返回键取消对话框，只能通过代码取消对话框
public void 可取消(boolean 可取消) {
if(dialog == null) {
dialog = builder.create();
}
dialog.setCancelable(可取消);
}

//设置对话框背景颜色
public void 背景颜色(int 颜色) {
if(dialog == null) {
dialog = builder.create();
}
Window window = dialog.getWindow();
window.setBackgroundDrawable(new ColorDrawable(颜色));
}

//设置对话框背景图片
public void 背景图片(int 图片) {
if(dialog == null) {
dialog = builder.create();
}
Window window = dialog.getWindow();
window.setBackgroundDrawableResource(图片);
}

//判断对话框是否正在显示
public boolean 正在显示() {
return dialog.isShowing();
}

//设置对话框的列表项
public void 置列表项(String[] 列表项) {
builder.setItems(列表项, new DialogInterface.OnClickListener(){
@Override
public void onClick(DialogInterface p1, int p2) {
项目被单击(p2);
}
});
}

//设置对话框的单选列表项
public void 置单选列表项(String[] 列表项, int 默认选中索引) {
builder.setSingleChoiceItems(列表项, 默认选中索引, new DialogInterface.OnClickListener(){
@Override
public void onClick(DialogInterface p1, int p2) {
项目被单击(p2);
}
});
}

//设置对话框的多选列表项
public void 置多选列表项(String[] 列表项, boolean[] 选中项数组) {
builder.setMultiChoiceItems(列表项, 选中项数组, new DialogInterface.OnMultiChoiceClickListener(){
@Override
public void onClick(DialogInterface p1, int p2, boolean p3) {
项目被单击(p2);
}
});
}

//设置对话框边距
public void 置对话框边距(int 左边, int 顶边, int 右边, int 底边) {
if(dialog == null) {
dialog = builder.create();
}
Window window = dialog.getWindow();
window.getDecorView().setPadding(左边, 顶边, 右边, 底边);
}

//设置对话框布局
public void 置对话框布局(可视化组件 欲设置组件) {
if(dialog == null) {
dialog = builder.create();
}
dialog.setContentView(欲设置组件.getView());
}

//显示对话框
public void 显示() {
if(dialog == null) {
dialog = builder.create();
}
dialog.show();
}

//隐藏对话框
public void 隐藏() {
dialog.hide();
}

//关闭对话框
public void 关闭() {
dialog.dismiss();
}

//对话框按钮1被单击触发该事件
public void 按钮1被单击() { } // 事件

//对话框按钮2被单击触发该事件
public void 按钮2被单击() { } // 事件

//对话框按钮3被单击触发该事件
public void 按钮3被单击() { } // 事件

//当单选列表或多选列表被选中时触发该事件
public void 项目被单击(int 索引) { } // 事件
}
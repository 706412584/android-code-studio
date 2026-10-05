package 结绳.安卓;

import android.content.*;
import android.app.*;
import android.view.*;
import android.graphics.drawable.*;
import android.content.*;
import android.app.*;
import android.view.*;
import android.graphics.drawable.*;
import android.app.*;
import android.widget.*;
import android.graphics.drawable.*;
import android.content.*;
import java.util.*;
import android.app.*;
import android.widget.*;
import android.graphics.drawable.*;
import android.content.*;
import java.util.*;
import android.app.*;
import android.widget.*;
import android.graphics.drawable.*;
import android.content.*;
import java.util.*;
import android.widget.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

public class 弹窗 extends 窗口组件 {

private PopupWindow mPopupWindow;
private 组件容器 container;

public 弹窗(安卓环境 context) {
super(context);
mPopupWindow = new PopupWindow(context);
支持点击外部区域(true);
可获取焦点(true);
mPopupWindow.setOnDismissListener(new android.widget.PopupWindow.OnDismissListener() {
public void onDismiss() {
被关闭();
}
});
}

//设置弹窗加载的布局
public void 布局(组件容器 布局) {
this.container = 布局;
mPopupWindow.setContentView(布局.取根布局().getView());
布局.布局被加载();
}

//获取弹窗加载的组件容器
public 组件容器 布局() {
return container;
}

//设置弹窗对话风格
public void 动画资源(动画资源 动画) {
mPopupWindow.setAnimationStyle(动画);
}

//设置弹窗高度
public void 高度(int 高度) {
mPopupWindow.setHeight(高度);
}

//设置弹窗宽度
public void 宽度(int 宽度) {
mPopupWindow.setWidth(宽度);
}

//设置弹窗背景颜色
public void 背景颜色(int 背景颜色) {
mPopupWindow.setBackgroundDrawable(new ColorDrawable(背景颜色));
}

//设置弹窗背景图片，参数为图片资源
public void 背景图片(图片资源 图片) {
mPopupWindow.setBackgroundDrawable(context.getDrawable(图片));
}

//设置弹窗背景九宫格图片，参数为点九图资源
public void 点九图(图片资源 图片) {
Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), 图片);
NinePatchDrawable drawable = new NinePatchDrawable(bitmap, bitmap.getNinePatchChunk(), new Rect(), 图片 + "");
mPopupWindow.setBackgroundDrawable(drawable);
}




public void 可获取焦点(boolean 是否可获取焦点) {
mPopupWindow.setFocusable(是否可获取焦点);
}




public boolean 可获取焦点() {
return mPopupWindow.isFocusable();
}




public void 支持触摸(boolean 是否支持触摸) {
mPopupWindow.setTouchable(是否支持触摸);
}




public void 支持点击外部区域(boolean 是否支持) {
mPopupWindow.setOutsideTouchable(是否支持);
}

//将弹窗显示在寄托组件附近
public void 显示(可视化组件 寄托组件) {
mPopupWindow.showAsDropDown(寄托组件.getView());
}

//将弹窗显示在寄托组件附近, 并设置对齐方式和横纵坐标
public void 显示2(可视化组件 寄托组件, int 对齐方式, int X坐标, int Y坐标) {
mPopupWindow.showAtLocation(寄托组件.getView(), 对齐方式, X坐标, Y坐标);
}

//关闭弹窗
public void 关闭() {
mPopupWindow.dismiss();
}




public void 被关闭() { return null; } // 事件
}



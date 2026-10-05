package 结绳.安卓;

import android.view.ViewGroup;
import android.content.Context;
import android.widget.FrameLayout;
import android.graphics.*;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.view.*;
import android.view.ViewGroup;
import android.widget.AbsoluteLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.RelativeLayout.LayoutParams;
import android.widget.RelativeLayout;
import android.view.View;
import android.widget.GridLayout.LayoutParams;
import android.widget.GridLayout;
import android.view.View;
import rn_1.*;
import java.util.*;
import android.view.View;

public class 侧滑布局 extends 布局组件 {
public 侧滑布局(android.content.Context context) {
super(context);
}

public rn_1.DrawerLayout onCreateView(android.content.Context context) {
rn_1.DrawerLayout view = new rn_1.DrawerLayout(context);
view.setDrawerListener(new rn_1.DrawerLayout.SimpleDrawerListener() {
public void onDrawerOpened(android.view.View drawerView){
侧滑打开();
}
public void onDrawerClosed(android.view.View drawerView){
侧滑关闭();
}
});
return view;
}

public rn_1.DrawerLayout getView() {
return (rn_1.DrawerLayout) view;
}

//设置侧滑栏打开的阴影度
public void 侧滑阴影(int 阴影) {
getView().setDrawerElevation(阴影);
}

public void 左侧布局(组件容器 左侧布局) {
可视化组件 component = 左侧布局.getLayout();
添加组件(component);
左侧布局.布局被加载();
android.view.View view = component.getView();
rn_1.DrawerLayout.LayoutParams params = (rn_1.DrawerLayout.LayoutParams) view.getLayoutParams();
params.gravity = android.view.Gravity.LEFT;
view.setLayoutParams(params);
}

public void 右侧布局(组件容器 右侧布局) {
可视化组件 component = 右侧布局.getLayout();
添加组件(component);
右侧布局.布局被加载();
android.view.View view = component.getView();
rn_1.DrawerLayout.LayoutParams params = (rn_1.DrawerLayout.LayoutParams) view.getLayoutParams();
params.gravity = android.view.Gravity.RIGHT;
view.setLayoutParams(params);
}






public void 布局对齐方式(可视化组件 欲设置组件, int 对齐方式) {
android.view.View v = 欲设置组件.getView();
rn_1.DrawerLayout.LayoutParams params = (rn_1.DrawerLayout.LayoutParams) v.getLayoutParams();
params.gravity = 对齐方式;
v.setLayoutParams(params);
}




public boolean 是否已打开(int 方向) {
return getView().isDrawerOpen(方向);
}

//打开左侧方向的侧滑栏
public void 打开左侧滑栏() {
getView().openDrawer(对齐方式.靠左对齐);
}

//打开右侧方向的侧滑栏
public void 打开右侧滑栏() {
getView().openDrawer(对齐方式.靠右对齐);
}

//打开指定方向的侧滑栏
public void 打开侧滑栏(int 方向) {
getView().openDrawer(方向);
}

//关闭指定方向的侧滑栏
public void 关闭侧滑栏(int 方向) {
getView().closeDrawer(方向);
}

//关闭所有方向已打开的侧滑栏
public void 关闭所有侧滑栏() {
getView().closeDrawers();
}

//抽屉布局侧滑栏被关闭时触发该事件
public void 侧滑关闭() { return null; } // 事件

//抽屉布局侧滑栏打开时触发该事件
public void 侧滑打开() { return null; } // 事件
}





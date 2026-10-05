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

public class 自适应布局 extends 布局组件 {
public 自适应布局(android.content.Context context) {
super(context);
}

public AbsoluteLayout onCreateView(android.content.Context context) {
AbsoluteLayout view = new AbsoluteLayout(context);
return view;
}

public AbsoluteLayout getView() {
return (AbsoluteLayout) view;
}

public void 宽度比例(可视化组件 欲设置组件, double 比例) {
android.view.View v = 欲设置组件.getView();
ViewGroup.LayoutParams params = (ViewGroup.LayoutParams) v.getLayoutParams();
params.width = (int) (系统操作.取屏幕宽度(context) * 比例);
v.setLayoutParams(params);
}

public void 高度比例(可视化组件 欲设置组件, double 比例) {
android.view.View v = 欲设置组件.getView();
ViewGroup.LayoutParams params = (ViewGroup.LayoutParams) v.getLayoutParams();
params.height = (int) (系统操作.取屏幕高度_不含导航栏和状态栏(context) * 比例);
v.setLayoutParams(params);
}

public void 横坐标比例(可视化组件 欲设置组件, double 比例) {
android.view.View v = 欲设置组件.getView();
android.widget.AbsoluteLayout.LayoutParams params
= (android.widget.AbsoluteLayout.LayoutParams) v.getLayoutParams();
params.x = (int) (系统操作.取屏幕宽度(context) * 比例);
v.setLayoutParams(params);
}

public void 纵坐标比例(可视化组件 欲设置组件, double 比例) {
android.view.View v = 欲设置组件.getView();
android.widget.AbsoluteLayout.LayoutParams params
= (android.widget.AbsoluteLayout.LayoutParams) v.getLayoutParams();
params.y = (int) (系统操作.取屏幕高度_不含导航栏和状态栏(context) * 比例);
v.setLayoutParams(params);
}
}






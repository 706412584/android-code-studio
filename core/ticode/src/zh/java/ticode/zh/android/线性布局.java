package ticode.zh.android;

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

public class 线性布局 extends 可调整边距布局组件 {
public 线性布局(android.content.Context context) {
super(context);
}

@Override
public android.widget.LinearLayout onCreateView(android.content.Context context) {
android.widget.LinearLayout view = new android.widget.LinearLayout(context);
return view;
}

@Override
public android.widget.LinearLayout getView() {
return (android.widget.LinearLayout) view;
}




public void 对齐方式(int 方式) {
getView().setGravity(方式);
}




public void 纵向布局(boolean 是否纵向) {
getView().setOrientation(是否纵向 ? 1 : 0);
}






public void 权重(可视化组件 欲设置组件, double 值) {
android.view.View v = 欲设置组件.getView();
LayoutParams params = (LayoutParams) v.getLayoutParams();
params.weight = (float) 值;
v.setLayoutParams(params);
}






public void 布局对齐方式(可视化组件 欲设置组件, int 对齐方式) {
android.view.View v = 欲设置组件.getView();
LayoutParams params = (LayoutParams) v.getLayoutParams();
params.gravity = 对齐方式;
v.setLayoutParams(params);
}
}
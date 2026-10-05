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

public class 布局组件 extends 可视化组件 {
public 布局组件(android.content.Context context) {
super(context);
}

public abstract ViewGroup onCreateView(android.content.Context context);
public abstract ViewGroup getView();

public void addComponent(可视化组件 component) {
getView().addView(component.getView());
}

public void addComponent(可视化组件 component, int width, int height) {
getView().addView(component.getView(), width, height);
}

public void 添加组件(可视化组件 组件) {
addComponent(组件);
}

public 可视化组件 取子组件(int 索引) {
return (可视化组件)getView().getChildAt(索引).getTag();
}

public int 取子组件数量() {
return getView().getChildCount();
}

//查找子组件在当前布局中的索引
public int 查找子组件(可视化组件 子组件) {
return getView().indexOfChild(子组件.getView());
}

//移除布局中的子组件
public void 移除组件(可视化组件 欲移除组件) {
getView().removeView(欲移除组件.getView());
}

//移除布局中的组件
public void 移除组件2(int 索引) {
getView().removeViewAt(索引);
}

//移除布局中所有可视化组件
public void 移除所有组件() {
getView().removeAllViews();
}
}
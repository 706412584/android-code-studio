package ticode.android;

import android.view.ViewGroup;
import android.widget.AbsoluteLayout;
import android.graphics.*;
import android.view.*;
import java.util.*;

public class AdaptiveLayout extends LayoutComponent {
public AdaptiveLayout(android.content.Context context) {
super(context);
}

@Override
public AbsoluteLayout onCreateView(android.content.Context context) {
AbsoluteLayout view = new AbsoluteLayout(context);
return view;
}

@Override
public AbsoluteLayout getView() {
return (AbsoluteLayout) view;
}

public void 宽度比例(VisualComponent 欲设置组件, double 比例) {
android.view.View v = 欲设置组件.getView();
ViewGroup.LayoutParams params = (ViewGroup.LayoutParams) v.getLayoutParams();
params.width = (int) (SystemOps.取屏幕宽度(context) * 比例);
v.setLayoutParams(params);
}

public void 高度比例(VisualComponent 欲设置组件, double 比例) {
android.view.View v = 欲设置组件.getView();
ViewGroup.LayoutParams params = (ViewGroup.LayoutParams) v.getLayoutParams();
params.height = (int) (SystemOps.取屏幕高度_不含导航栏和状态栏(context) * 比例);
v.setLayoutParams(params);
}

public void 横坐标比例(VisualComponent 欲设置组件, double 比例) {
android.view.View v = 欲设置组件.getView();
android.widget.AbsoluteLayout.LayoutParams params
= (android.widget.AbsoluteLayout.LayoutParams) v.getLayoutParams();
params.x = (int) (SystemOps.取屏幕宽度(context) * 比例);
v.setLayoutParams(params);
}

public void 纵坐标比例(VisualComponent 欲设置组件, double 比例) {
android.view.View v = 欲设置组件.getView();
android.widget.AbsoluteLayout.LayoutParams params
= (android.widget.AbsoluteLayout.LayoutParams) v.getLayoutParams();
params.y = (int) (SystemOps.取屏幕高度_不含导航栏和状态栏(context) * 比例);
v.setLayoutParams(params);
}
}
package ticode.zh.android;

import android.view.ViewGroup;
import android.content.Context;
import android.widget.FrameLayout;
import android.graphics.*;
import android.view.*;
import android.widget.AbsoluteLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.RelativeLayout;
import android.view.View;
import android.widget.GridLayout;
import rn_1.*;
import java.util.*;

public class 可调整边距布局组件 extends 布局组件 {
public 可调整边距布局组件(android.content.Context context) {
super(context);
}




public void 外边距(可视化组件 欲设置组件, Object 边距) {
View view = 欲设置组件.getView();
ViewGroup.LayoutParams params = view.getLayoutParams();
if (params instanceof ViewGroup.MarginLayoutParams) {
ViewGroup.MarginLayoutParams marginParams = ((ViewGroup.MarginLayoutParams) params);
int margin = computeDimension(边距);
marginParams.setMargins(margin, margin, margin, margin);
view.setLayoutParams(marginParams);
}
}

public void 左外边距(可视化组件 欲设置组件, Object 左外边距) {
View view = 欲设置组件.getView();
ViewGroup.LayoutParams params = view.getLayoutParams();
if (params instanceof ViewGroup.MarginLayoutParams) {
ViewGroup.MarginLayoutParams marginParams = ((ViewGroup.MarginLayoutParams) params);
marginParams.leftMargin = computeDimension(左外边距);
view.setLayoutParams(marginParams);
}
}

public void 上外边距(可视化组件 欲设置组件, Object 上外边距) {
View view = 欲设置组件.getView();
ViewGroup.LayoutParams params = view.getLayoutParams();
if (params instanceof ViewGroup.MarginLayoutParams) {
ViewGroup.MarginLayoutParams marginParams = ((ViewGroup.MarginLayoutParams) params);
marginParams.topMargin = computeDimension(上外边距);
view.setLayoutParams(marginParams);
}
}

public void 右外边距(可视化组件 欲设置组件, Object 右外边距) {
View view = 欲设置组件.getView();
ViewGroup.LayoutParams params = view.getLayoutParams();
if (params instanceof ViewGroup.MarginLayoutParams) {
ViewGroup.MarginLayoutParams marginParams = ((ViewGroup.MarginLayoutParams) params);
marginParams.rightMargin = computeDimension(右外边距);
view.setLayoutParams(marginParams);
}
}

public void 下外边距(可视化组件 欲设置组件, Object 下外边距) {
View view = 欲设置组件.getView();
ViewGroup.LayoutParams params = view.getLayoutParams();
if (params instanceof ViewGroup.MarginLayoutParams) {
ViewGroup.MarginLayoutParams marginParams = ((ViewGroup.MarginLayoutParams) params);
marginParams.bottomMargin = computeDimension(下外边距);
view.setLayoutParams(marginParams);
}
}




public void 外边距DP(可视化组件 欲设置组件, int 边距) {
View view = 欲设置组件.getView();
ViewGroup.LayoutParams params = view.getLayoutParams();
if (params instanceof ViewGroup.MarginLayoutParams) {
ViewGroup.MarginLayoutParams marginParams = ((ViewGroup.MarginLayoutParams) params);
int dip = 像素操作.DP到PX(边距);
marginParams.setMargins(dip, dip, dip, dip);
view.setLayoutParams(marginParams);
}
}

public void 左外边距DP(可视化组件 欲设置组件, int 左外边距) {
View view = 欲设置组件.getView();
ViewGroup.LayoutParams params = view.getLayoutParams();
if (params instanceof ViewGroup.MarginLayoutParams) {
ViewGroup.MarginLayoutParams marginParams = ((ViewGroup.MarginLayoutParams) params);
marginParams.leftMargin = 像素操作.DP到PX(左外边距);
view.setLayoutParams(marginParams);
}
}

public void 上外边距DP(可视化组件 欲设置组件, int 上外边距) {
View view = 欲设置组件.getView();
ViewGroup.LayoutParams params = view.getLayoutParams();
if (params instanceof ViewGroup.MarginLayoutParams) {
ViewGroup.MarginLayoutParams marginParams = ((ViewGroup.MarginLayoutParams) params);
marginParams.topMargin = 像素操作.DP到PX(上外边距);
view.setLayoutParams(marginParams);
}
}

public void 右外边距DP(可视化组件 欲设置组件, int 右外边距) {
View view = 欲设置组件.getView();
ViewGroup.LayoutParams params = view.getLayoutParams();
if (params instanceof ViewGroup.MarginLayoutParams) {
ViewGroup.MarginLayoutParams marginParams = ((ViewGroup.MarginLayoutParams) params);
marginParams.rightMargin = 像素操作.DP到PX(右外边距);
view.setLayoutParams(marginParams);
}
}

public void 下外边距DP(可视化组件 欲设置组件, int 下外边距) {
View view = 欲设置组件.getView();
ViewGroup.LayoutParams params = view.getLayoutParams();
if (params instanceof ViewGroup.MarginLayoutParams) {
ViewGroup.MarginLayoutParams marginParams = ((ViewGroup.MarginLayoutParams) params);
marginParams.bottomMargin = 像素操作.DP到PX(下外边距);
view.setLayoutParams(marginParams);
}
}
}
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

public class 卡片布局 extends 布局组件 {
public 卡片布局(android.content.Context context) {
super(context);
}

public rn_1.CardView onCreateView(android.content.Context context) {
rn_1.CardView view = new rn_1.CardView(context);
return view;
}

public rn_1.CardView getView() {
return (rn_1.CardView) view;
}

//设置卡片圆角
public void 圆角(int 圆角度) {
getView().setRadius(圆角度);
}

//设置卡片阴影
public void 卡片阴影(int 阴影) {
getView().setCardElevation(阴影);
}

//设置卡片背景颜色
public void 卡片背景颜色(int 颜色) {
getView().setCardBackgroundColor(颜色);
}
}






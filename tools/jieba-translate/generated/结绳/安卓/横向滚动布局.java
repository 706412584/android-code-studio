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

public class 横向滚动布局 extends 布局组件 {
public 横向滚动布局(android.content.Context context) {
super(context);
}

public android.widget.HorizontalScrollView onCreateView(android.content.Context context) {
android.widget.HorizontalScrollView view = new android.widget.HorizontalScrollView(context);
return view;
}

public android.widget.HorizontalScrollView getView() {
return (android.widget.HorizontalScrollView) view;
}

//使横滚布局滚动到某一位置
public void 滚动至(int X坐标, int Y坐标) {
getView().scrollTo(X坐标, Y坐标);
}

//设置滚动布局是否显示滚动条
public void 显示滚动条(boolean 是否显示) {
getView().setHorizontalScrollBarEnabled(是否显示);
}


public void 完全显示(boolean 是否完全显示) {
getView().setFillViewport(是否完全显示);
}
}






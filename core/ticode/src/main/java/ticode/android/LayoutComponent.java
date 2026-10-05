package ticode.android;

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

public class LayoutComponent extends VisualComponent {
public LayoutComponent(android.content.Context context) {
super(context);
}

public abstract ViewGroup onCreateView(android.content.Context context);
public abstract ViewGroup getView();

public void addComponent(VisualComponent component) {
getView().addView(component.getView());
}

public void addComponent(VisualComponent component, int width, int height) {
getView().addView(component.getView(), width, height);
}

public void 添加组件(VisualComponent 组件) {
addComponent(组件);
}

public VisualComponent 取子组件(int 索引) {
return (可视化组件)getView().getChildAt(索引).getTag();
}

public int 取子组件数量() {
return getView().getChildCount();
}

//查找子组件在当前布局中的索引
public int 查找子组件(VisualComponent 子组件) {
return getView().indexOfChild(子组件.getView());
}

//移除布局中的子组件
public void 移除组件(VisualComponent 欲移除组件) {
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
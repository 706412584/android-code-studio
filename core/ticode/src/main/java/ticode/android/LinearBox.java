package ticode.android;

import android.widget.LinearLayout.LayoutParams;
import android.graphics.*;
import android.view.*;
import java.util.*;

public class LinearBox extends AdjustableMarginLayout {
public LinearBox(android.content.Context context) {
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






public void 权重(VisualComponent 欲设置组件, double 值) {
android.view.View v = 欲设置组件.getView();
LayoutParams params = (LayoutParams) v.getLayoutParams();
params.weight = (float) 值;
v.setLayoutParams(params);
}






public void 布局对齐方式(VisualComponent 欲设置组件, int 对齐方式) {
android.view.View v = 欲设置组件.getView();
LayoutParams params = (LayoutParams) v.getLayoutParams();
params.gravity = Gravity2;
v.setLayoutParams(params);
}
}
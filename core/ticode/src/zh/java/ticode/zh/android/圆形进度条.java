package ticode.zh.android;

import android.view.ViewGroup;

import ticode.zh.jvm.输入流;

public class 圆形进度条 extends 可视化组件 {
public 圆形进度条(android.content.Context context) {
super(context);
}

@Override
public rn_1.CircleBarView onCreateView(android.content.Context context) {
rn_1.CircleBarView view = new rn_1.CircleBarView(context);
return view;
}

@Override
public rn_1.CircleBarView getView() {
return (rn_1.CircleBarView) view;
}

public void 进度值(int 进度值) {
getView().setProgress(进度值);
}

public void 进度条直径(int 直径) {
getView().setViewSize(直径);
}

public void 进度条粗细(int 粗细) {
getView().setStrokeWidth(粗细);
}

public void 进度条颜色(int 颜色) {
getView().setColor(颜色);
}
}
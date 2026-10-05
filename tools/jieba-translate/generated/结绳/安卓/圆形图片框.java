package 结绳.安卓;

import android.view.ViewGroup;

public class 圆形图片框 extends 图片框 {
public 圆形图片框(android.content.Context context) {
super(context);
}

public rn_1.CircleImageView onCreateView(android.content.Context context) {
rn_1.CircleImageView view = new rn_1.CircleImageView(context);
return view;
}

public rn_1.CircleImageView getView() {
return (rn_1.CircleImageView) view;
}

//设置圆形图片框阴影
public void 圆角阴影(int 阴影度) {
getView().setElevation2(阴影度);
}

//设置圆形图片框边框宽度
public void 边框宽度(int 边框宽度) {
getView().setBorderWidth(边框宽度);
}

//获取圆形图片框边框宽度
public int 边框宽度() {
return getView().getBorderWidth();
}

//设置圆形图片框边框颜色
public void 边框颜色(int 边框颜色) {
getView().setBorderColor(边框颜色);
}

//设置圆形图片框边框颜色
public void 边框颜色2(String 边框颜色) {
getView().setBorderColor(android.graphics.Color.parseColor(边框颜色));
}

//获取圆形图片框边框颜色
public int 边框颜色() {
return getView().getBorderColor();
}
}






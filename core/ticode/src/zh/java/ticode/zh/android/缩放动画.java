package ticode.zh.android;


public class 缩放动画 extends android.view.animation.ScaleAnimation {
public void 赋值_op(double 起始横向缩放度, double 结束横向缩放度, double 起始纵向缩放度, double 结束纵向缩放度) {
return new 缩放动画(
(float) 起始横向缩放度,
(float) 结束横向缩放度,
(float) 起始纵向缩放度,
(float) 结束纵向缩放度
);
}

public static 缩放动画 新建(double 起始横向缩放度, double 结束横向缩放度, double 起始纵向缩放度, double 结束纵向缩放度) {
return new 缩放动画(
(float) 起始横向缩放度,
(float) 结束横向缩放度,
(float) 起始纵向缩放度,
(float) 结束纵向缩放度
);
}
}
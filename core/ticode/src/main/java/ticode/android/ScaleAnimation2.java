package ticode.android;


public class ScaleAnimation2 extends android.view.animation.ScaleAnimation {
public void 赋值_op(double 起始横向缩放度, double 结束横向缩放度, double 起始纵向缩放度, double 结束纵向缩放度) {
return new ScaleAnimation2(
(float) 起始横向缩放度,
(float) 结束横向缩放度,
(float) 起始纵向缩放度,
(float) 结束纵向缩放度
);
}

public static ScaleAnimation2 新建(double 起始横向缩放度, double 结束横向缩放度, double 起始纵向缩放度, double 结束纵向缩放度) {
return new ScaleAnimation2(
(float) 起始横向缩放度,
(float) 结束横向缩放度,
(float) 起始纵向缩放度,
(float) 结束纵向缩放度
);
}
}
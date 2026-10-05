package ticode.zh.android;


public class 透明动画 extends android.view.animation.AlphaAnimation {
public void 赋值_op(double 开始时透明度, double 结束时透明度) {
return new 透明动画((float) 开始时透明度, (float) 结束时透明度);
}

public static 透明动画 新建(double 开始时透明度, double 结束时透明度) {
return new 透明动画((float) 开始时透明度, (float) 结束时透明度);
}
}
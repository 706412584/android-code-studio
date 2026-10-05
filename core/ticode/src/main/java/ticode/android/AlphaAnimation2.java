package ticode.android;


public class AlphaAnimation2 extends android.view.animation.AlphaAnimation {
public void 赋值_op(double 开始时透明度, double 结束时透明度) {
return new AlphaAnimation2((float) 开始时透明度, (float) 结束时透明度);
}

public static AlphaAnimation2 新建(double 开始时透明度, double 结束时透明度) {
return new AlphaAnimation2((float) 开始时透明度, (float) 结束时透明度);
}
}
package ticode.zh.android;


public class 透明动画 extends android.view.animation.AlphaAnimation {
public android.view.animation.AlphaAnimation 赋值_op(double 开始时透明度, double 结束时透明度) {
return new android.view.animation.AlphaAnimation((float) 开始时透明度, (float) 结束时透明度);
}

public static android.view.animation.AlphaAnimation 新建(double 开始时透明度, double 结束时透明度) {
return new android.view.animation.AlphaAnimation((float) 开始时透明度, (float) 结束时透明度);
}
}
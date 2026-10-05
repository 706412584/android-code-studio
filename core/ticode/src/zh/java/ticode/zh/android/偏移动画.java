package ticode.zh.android;


public class 偏移动画 extends android.view.animation.TranslateAnimation {
public android.view.animation.TranslateAnimation 赋值_op(double 起始横向偏移, double 结束横向偏移, double 起始纵向偏移, double 结束纵向偏移) {
return new android.view.animation.TranslateAnimation(
(float) 起始横向偏移,
(float) 结束横向偏移,
(float) 起始纵向偏移,
(float) 结束纵向偏移
);
}

public static android.view.animation.TranslateAnimation 新建(double 起始横向偏移, double 结束横向偏移, double 起始纵向偏移, double 结束纵向偏移) {
return new android.view.animation.TranslateAnimation(
(float) 起始横向偏移,
(float) 结束横向偏移,
(float) 起始纵向偏移,
(float) 结束纵向偏移
);
}
}
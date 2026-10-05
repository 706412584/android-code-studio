package ticode.android;


public class TranslateAnimation2 extends android.view.animation.TranslateAnimation {
public void 赋值_op(double 起始横向偏移, double 结束横向偏移, double 起始纵向偏移, double 结束纵向偏移) {
return new TranslateAnimation2(
(float) 起始横向偏移,
(float) 结束横向偏移,
(float) 起始纵向偏移,
(float) 结束纵向偏移
);
}

public static TranslateAnimation2 新建(double 起始横向偏移, double 结束横向偏移, double 起始纵向偏移, double 结束纵向偏移) {
return new TranslateAnimation2(
(float) 起始横向偏移,
(float) 结束横向偏移,
(float) 起始纵向偏移,
(float) 结束纵向偏移
);
}
}
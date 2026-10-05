package ticode.zh.android;


public abstract class 样式_背景色 extends android.text.style.BackgroundColorSpan {

public 样式_背景色 赋值_op(int 颜色值) {
return (样式_背景色)new android.text.style.BackgroundColorSpan(颜色值);
}

public static 样式_背景色 取实例(int 颜色值) {
return (样式_背景色)new android.text.style.BackgroundColorSpan(颜色值);
}

}
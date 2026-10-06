package ticode.zh.android;


public class 样式_背景色 extends android.text.style.BackgroundColorSpan {

public 样式_背景色(int 颜色值) { super(颜色值); }

public static 样式_背景色 取实例(int 颜色值) {
return new 样式_背景色(颜色值);
}

}
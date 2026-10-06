package ticode.android;


public class StyleBackgroundColor extends android.text.style.BackgroundColorSpan {

public StyleBackgroundColor 赋值_op(int 颜色值) {
return new android.text.style.BackgroundColorSpan(颜色值);
}

public static StyleBackgroundColor 取实例(int 颜色值) {
return new android.text.style.BackgroundColorSpan(颜色值);
}

}
package ticode.android;


public class StyleForegroundColor extends android.text.style.ForegroundColorSpan {
//前景色可以简单理解为 文本的部分字体颜色 可以理解为文本高亮
public StyleForegroundColor 赋值_op(int 颜色值) {
return new android.text.style.ForegroundColorSpan(颜色值);
}

public static StyleForegroundColor 取实例(int 颜色值) {
return new android.text.style.ForegroundColorSpan(颜色值);
}

}
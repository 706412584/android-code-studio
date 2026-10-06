package ticode.zh.android;


public class 样式_前景色 extends android.text.style.ForegroundColorSpan {
//前景色可以简单理解为 文本的部分字体颜色 可以理解为文本高亮
public 样式_前景色(int 颜色值) { super(颜色值); }

public static 样式_前景色 取实例(int 颜色值) {
return new 样式_前景色(颜色值);
}

}
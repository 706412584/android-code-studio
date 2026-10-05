package ticode.zh.android;


public abstract class 样式_字体样式 extends android.text.style.StyleSpan {

public static final int 样式类型_默认 = 0;
public static final int 样式类型_粗体 = 1;
public static final int 样式类型_斜体 = 2;
public static final int 样式类型_粗斜体 = 3;

public 样式_字体样式 赋值_op(int 样式) {
return (样式_字体样式)new android.text.style.StyleSpan(样式);
}

public static 样式_字体样式 取实例(int 样式) {
return (样式_字体样式)new android.text.style.StyleSpan(样式);
}

}
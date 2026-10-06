package ticode.zh.android;


public class 样式_字体大小 extends android.text.style.AbsoluteSizeSpan {

public 样式_字体大小(int 字体大小) { super(字体大小); }

//字体大小单位sp
public static 样式_字体大小 取实例(android.content.Context 环境, int 字体大小) {
if (字体大小 <= 0) {
return new 样式_字体大小(字体大小);
}
float scale = 环境.getResources().getDisplayMetrics().scaledDensity;
int size = (int) (字体大小 * scale + 0.5f);
return new 样式_字体大小(size);
}

}
package ticode.zh.android;

import android.text.SpannableStringBuilder;

import ticode.zh.base.字符串;
import ticode.zh.base.文本;

public class 样式_前景色 extends android.text.style.ForegroundColorSpan {
//前景色可以简单理解为 文本的部分字体颜色 可以理解为文本高亮
public void 赋值_op(int 颜色值) {
return new android.text.style.ForegroundColorSpan(颜色值);
}

public static 样式_前景色 取实例(int 颜色值) {
return new android.text.style.ForegroundColorSpan(颜色值);
}

}
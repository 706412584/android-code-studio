package ticode.zh.android;

import android.text.SpannableStringBuilder;
import android.text.SpannableString;

import ticode.zh.base.字符串;
import ticode.zh.base.文本;

public class 样式_背景色 extends android.text.style.BackgroundColorSpan {

public void 赋值_op(int 颜色值) {
return new android.text.style.BackgroundColorSpan(颜色值);
}

public static 样式_背景色 取实例(int 颜色值) {
return new android.text.style.BackgroundColorSpan(颜色值);
}

}
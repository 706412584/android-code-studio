package ticode.android;

import android.text.SpannableStringBuilder;
import android.text.SpannableString;

public class StyleFontSize extends android.text.style.AbsoluteSizeSpan {

public void 赋值_op(int 字体大小) {
return new android.text.style.AbsoluteSizeSpan(字体大小);
}

//字体大小单位sp
public static StyleFontSize 取实例(AndroidEnv 环境, int 字体大小) {
if (字体大小 <= 0) {
return 字体大小;
}
float scale = 环境.getResources().getDisplayMetrics().scaledDensity;
int size = (int) (字体大小 * scale + 0.5f);
return new android.text.style.AbsoluteSizeSpan(size);
}

}
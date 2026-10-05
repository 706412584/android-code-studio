package ticode.android;

import android.text.SpannableStringBuilder;
import android.text.SpannableString;

import ticode.base.JString;

public class StyleFontStyle extends android.text.style.StyleSpan {

public static final int 样式类型_默认 = 0;
public static final int 样式类型_粗体 = 1;
public static final int 样式类型_斜体 = 2;
public static final int 样式类型_粗斜体 = 3;

public void 赋值_op(int 样式) {
return new android.text.style.StyleSpan(样式);
}

public static StyleFontStyle 取实例(int 样式) {
return new android.text.style.StyleSpan(样式);
}

}
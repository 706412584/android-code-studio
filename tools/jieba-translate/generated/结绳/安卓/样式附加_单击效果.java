package 结绳.安卓;

import android.text.SpannableStringBuilder;
import android.text.SpannableString;

public class 样式附加_单击效果 {

private boolean underline;
public void onClick(android.view.View view) {
被单击();
}

public void updateDrawState(android.text.TextPaint paint) {
paint.setUnderlineText(underline); // 设置下划线
}

public boolean 显示下划线() {
return this.underline;
}

public void 显示下划线(boolean 显示) {
this.underline = 显示;
}

public void 被单击() { return null; } // 事件

public void 设置单击效果(文本框 文本框) {
文本框.getView().setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
}

}
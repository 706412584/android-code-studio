package ticode.android;


public class StyleClickEffect extends android.text.style.ClickableSpan {

private boolean underline;
@Override
public void onClick(android.view.View view) {
被单击();
}

@Override
public void updateDrawState(android.text.TextPaint paint) {
paint.setUnderlineText(underline); // 设置下划线
}

public boolean 显示下划线() {
return this.underline;
}

public void 显示下划线(boolean 显示) {
this.underline = 显示;
}

public void 被单击() { } // 事件

public void 设置单击效果(TextBox2 文本框) {
TextBox2.getView().setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
}

}
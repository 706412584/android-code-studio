package ticode.zh.android;


public class 可扩展文本 extends android.text.SpannableString {

public static final int 包括开始和结束 = 1;
//表示标记的范围从start到end-1，包括start，但不包括end.
public static final int 包括开始 = 2;
//表示标记的范围从start+1到end，不包括start，但包括end。
public static final int 包括结束 = 4;
//表示标记的范围从start+1到end-1，不包括start和end.
public static final int 不包括开始和结束 = 8;

public void 设置扩展(Object 样式, int 开始位置, int 结束位置, int 扩展类型) {
this.setSpan(样式, 开始位置, 结束位置, 扩展类型);
}

public 可扩展文本(CharSequence 内容) { super(内容); }

public void 设置到文本框(文本框 文本框组件) {
文本框组件.getView().setText(this);
}

}
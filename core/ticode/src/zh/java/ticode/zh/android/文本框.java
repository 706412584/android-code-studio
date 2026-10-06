package ticode.zh.android;

import android.graphics.Typeface;
import android.text.Html;
import android.text.TextUtils;
import android.text.util.Linkify;
import android.text.method.LinkMovementMethod;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import android.content.*;
import android.content.res.*;
import android.view.*;
import android.widget.*;
import android.text.*;
import android.text.style.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.webkit.*;

import ticode.zh.jvm.正则匹配器;
import ticode.zh.jvm.正则表达式;

import static ticode.zh.android.数组操作.取数组长度;

public class 文本框 extends 可视化组件 {
private android.text.TextWatcher watcher;
//已解析的字体不会被回收，所以直接复用，而不是重新解析(重复占用内存)
private static java.util.HashMap<String, Typeface> fonts = new java.util.HashMap<>();

public 文本框(android.content.Context context) {
super(context);
}

@Override
public android.widget.TextView onCreateView(android.content.Context context) {
android.widget.TextView view = new android.widget.TextView(context);
return view;
}

@Override
public android.widget.TextView getView() {
return (android.widget.TextView) view;
}




public void 对齐方式(int 方式) {
getView().setGravity(方式);
}




public void 文本资源(int 资源) {
getView().setText(资源);
}




public void 内容(String 值) {
getView().setText(值);
}




public String 内容() {
return getView().getText().toString();
}

//设置长按文本框选中文本属性
public void 长按选中(boolean 值) {
getView().setTextIsSelectable(值);
}

//设置文本框内容粗体显示
public void 粗体(boolean 是否粗体) {
Typeface typeface = getView().getTypeface();
if (是否粗体) {
if (typeface != null && typeface.isItalic()) {
getView().setTypeface(Typeface.create(typeface,Typeface.BOLD_ITALIC));
} else {
getView().setTypeface(Typeface.create(typeface,Typeface.BOLD));
}
} else {
if (typeface != null && typeface.isBold() && typeface.isItalic()) {
getView().setTypeface(Typeface.create(typeface,Typeface.ITALIC));
} else {
getView().setTypeface(Typeface.create(typeface,Typeface.NORMAL));
}
}
}

//设置文本框内容斜体显示
public void 斜体(boolean 是否斜体) {
Typeface typeface = getView().getTypeface();
if (是否斜体) {
if (typeface != null && typeface.isBold()) {
getView().setTypeface(Typeface.create(typeface, Typeface.BOLD_ITALIC));
} else {
getView().setTypeface(Typeface.create(typeface, Typeface.ITALIC));
}
} else {
if (typeface != null && typeface.isBold() && typeface.isItalic()) {
getView().setTypeface(Typeface.create(typeface, Typeface.BOLD));
} else {
getView().setTypeface(Typeface.create(typeface, Typeface.NORMAL));
}
}
}

//设置文本框字体大小
public void 字体大小(Object 字体大小) {
if (字体大小 instanceof Number) {
int size = ((Number) 字体大小).intValue();
getView().setTextSize(size);
} else if (字体大小 instanceof String) {
String text = ((String) 字体大小).trim().toLowerCase();
if (text.matches("\\d+\\.?\\d*")) { //支持整数以及小数
getView().setTextSize(Float.parseFloat(text));
} else if(text.matches("(\\d+\\.?\\d*)px")) {
getView().setTextSize(0, Float.parseFloat(text.substring(0, text.length() - 2)));
} else if(text.matches("(\\d+\\.?\\d*)dp")) {
getView().setTextSize(1, Float.parseFloat(text.substring(0, text.length() - 2)));
} else if(text.matches("(\\d+\\.?\\d*)sp")) {
getView().setTextSize(2, Float.parseFloat(text.substring(0, text.length() - 2)));
}
}
//下面这个代码会导致字体大小变得更大
//code getView().setTextSize(computeDimension(#字体大小));
}

//获取字体大小 PX
public int 字体大小() {
return (int)getView().getTextSize();
}

//设置文本框字体大小 PX
public void 字体大小PX(int 字体大小) {
getView().setTextSize(0,字体大小);
}

//设置文本框字体大小 DP
public void 字体大小DP(int 字体大小) {
getView().setTextSize(1,字体大小);
}

//设置文本框字体颜色
public void 字体颜色(int 字体颜色) {
getView().setTextColor(字体颜色);
}

//获取文本框字体颜色
public int 字体颜色() {
return getView().getTextColors().getDefaultColor();
}

//设置文本框显示行数
public void 显示行数(int 行数) {
getView().setLines(行数);
}

//设置文本框最大显示行数
public void 最大显示行数(int 行数) {
getView().setMaxLines(行数);
}

//设置文本框最小显示行数
public void 最小显示行数(int 行数) {
getView().setMinLines(行数);
}

//设置行间距
public void 行距(int 行距) {
getView().setLineSpacing(0f, 行距);
}

//设置组件的字体，字体路径可以为附加资源中字体文件名称，也可以是sdcard路径
public void 字体(String 字体路径) {
if(fonts.containsKey(字体路径)) {
getView().setTypeface(fonts.get(字体路径));
} else {
Typeface tf;
if (字体路径.charAt(0) == '/') {
tf = Typeface.createFromFile(字体路径);
} else {
tf = Typeface.createFromAsset(context.getAssets(), 字体路径);
}
getView().setTypeface(tf);
fonts.put(字体路径, tf);
}
}

public static final int 超链接_全部 = 0;
public static final int 超链接_网址 = 1;
public static final int 超链接_邮箱 = 2;
public static final int 超链接_电话号码 = 3;
public static final int 超链接_地址 = 4;




public void 超链接识别模式(int 模式) {
switch (模式) {
case 0:
getView().setAutoLinkMask(Linkify.ALL);
break;
case 1:
getView().setAutoLinkMask(Linkify.WEB_URLS);
break;
case 2:
getView().setAutoLinkMask(Linkify.EMAIL_ADDRESSES);
break;
case 3:
getView().setAutoLinkMask(Linkify.PHONE_NUMBERS);
break;
case 4:
getView().setAutoLinkMask(Linkify.MAP_ADDRESSES);
break;
}
getView().setMovementMethod(LinkMovementMethod.getInstance());
}

//设置内容阴影放射效果
public void 阴影效果(int 模糊半径, int x偏移, int y偏移, int 颜色) {
getView().setShadowLayer(模糊半径,x偏移,y偏移,颜色);
}

//设置文本框是否开启跑马灯效果
public void 跑马灯效果(boolean 是否开启跑马灯效果) {
android.widget.TextView textView = getView();
if (是否开启跑马灯效果) {
textView.setSingleLine();
textView.setMarqueeRepeatLimit(-1);
textView.setFocusable(true);
textView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
textView.setFocusableInTouchMode(true);
textView.setHorizontallyScrolling(true);
textView.requestFocus();
} else {
textView.setHorizontallyScrolling(false);
}
}

//设置是否单行显示
public void 单行显示(boolean 是否单行显示) {
getView().setSingleLine(是否单行显示);
}







public void 省略显示(int 显示方式) {
if (显示方式 == 0) {
getView().setEllipsize(TextUtils.TruncateAt.START);
} else if (显示方式 == 1) {
getView().setEllipsize(TextUtils.TruncateAt.MIDDLE);
} else if (显示方式 == 2) {
getView().setEllipsize(TextUtils.TruncateAt.END);
}
}

public void 支持内容改变监听(boolean 是否支持) {
if (是否支持) {
if (watcher == null) {
watcher = new android.text.TextWatcher() {
@Override
public void beforeTextChanged(CharSequence s, int start, int count, int after) {
}
@Override
public void onTextChanged(CharSequence s, int start, int before, int count) {
}
@Override
public void afterTextChanged(android.text.Editable s) {
内容被改变();
}
};
getView().addTextChangedListener(watcher);
}
} else if (watcher != null) {
getView().removeTextChangedListener(watcher);
}
}

//高亮文本框中的特定字词
public void 高亮(String[] 欲高亮文本数组, int 高亮颜色) {
SpannableString ss = new SpannableString(getView().getText());
for (int i = 0;i < 欲高亮文本数组.length;i++) {
// 使用 \Q 和 \E 包裹关键词，让表达式直接转换成普通文本
Pattern p = Pattern.compile("\\Q" + 欲高亮文本数组[i] + "\\E");
Matcher m = p.matcher(ss);
while (m.find()) {
int start = m.start();
int end = m.end();
ss.setSpan(new ForegroundColorSpan(高亮颜色), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
}
}
getView().setText(ss);
}









public void 高亮2(String[] 欲高亮文本数组, Object 高亮样式, int 渲染类型) {
String 文本框内容 = this.内容();
可扩展文本 欲高亮内容 = new 可扩展文本(文本框内容) {};
for (int i = 0; i < (欲高亮文本数组).length; i++) {
java.util.regex.Pattern 表达式 = 正则表达式.编译("\\Q" + 欲高亮文本数组[i] + "\\E");
java.util.regex.Matcher 匹配器 = 表达式.matcher(文本框内容);
while (匹配器.find()) {
欲高亮内容.设置扩展(高亮样式,匹配器.start(),匹配器.end(),渲染类型);
}
}
getView().setText(欲高亮内容);
}




public void 加载Html(String html代码) {
getView().setText(Html.fromHtml(html代码));
}




public void 内容被改变() { } // 事件
}
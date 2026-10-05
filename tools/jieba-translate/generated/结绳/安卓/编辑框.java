package 结绳.安卓;

import android.content.Context;
import android.content.*;
import android.content.res.*;
import android.view.*;
import android.widget.*;
import android.graphics.Typeface;
import android.text.Html;
import android.text.TextUtils;
import android.widget.CompoundButton;
import android.widget.RadioButton;
import android.widget.CompoundButton;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.CompoundButton;
import android.widget.ImageView.ScaleType;
import android.graphics.*;
import android.widget.ProgressBar;
import android.graphics.drawable.*;
import android.graphics.*;
import android.widget.ProgressBar;
import android.graphics.drawable.*;
import android.graphics.*;
import android.widget.SeekBar;
import android.graphics.drawable.*;
import android.widget.RatingBar;
import android.widget.VideoView;
import android.media.MediaPlayer;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.view.View;
import android.view.ViewGroup;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.app.Activity;
import android.app.DownloadManager;
import android.widget.FrameLayout;
import java.io.File;
import android.graphics.Bitmap;
import android.annotation.TargetApi;
import android.widget.ProgressBar;
import android.webkit.*;

public class 编辑框 extends 文本框 {
public 编辑框(android.content.Context context) {
super(context);
}

public android.widget.EditText onCreateView(android.content.Context context) {
android.widget.EditText view = new android.widget.EditText(context);
return view;
}

public android.widget.EditText getView() {
return (android.widget.EditText) view;
}

public static final int 输入方式_普通输入 = 1;
public static final int 输入方式_数字 = 2;
public static final int 输入方式_电话号码 = 3;
public static final int 输入方式_时间日期 = 4;
public static final int 输入方式_密码 = 0x81;

//设置编辑框提示文本
public void 提示文本(String 提示文本) {
getView().setHint(提示文本);
}

//获取编辑框提示文本
public String 提示文本() {
return getView().getHint().toString();
}

//设置编辑框提示文本颜色，0xaarrggbb格式
public void 提示文本颜色(int 提示文本颜色) {
getView().setHintTextColor(提示文本颜色);
}

//获取编辑框提示文本颜色
public int 提示文本颜色() {
return getView().getHintTextColors().getDefaultColor();
}

//设置编辑框输入方式
public void 输入方式(int 输入方式) {
getView().setInputType(输入方式);
}

//获取编辑框输入方式
public int 输入方式() {
return getView().getInputType();
}

//设置光标位置
public void 光标位置(int 光标位置) {
getView().setSelection(光标位置);
}

//获取光标位置
public int 光标位置() {
return getView().getSelectionStart();
}

//设置编辑框状态是否为密码输入
public void 密码输入(boolean 是否密码输入) {
if (是否密码输入 == true) {
输入方式(0x81);
} else {
输入方式(android.text.InputType.TYPE_TEXT_VARIATION_NORMAL);
}
}

//设置编辑框是否为只能单行输入
public void 单行输入(boolean 是否单行输入) {
getView().setSingleLine(是否单行输入);
}

//设置编辑框是否显示光标
public void 显示光标(boolean 是否显示光标) {
getView().setCursorVisible(是否显示光标);
}

//全选编辑框的内容
public void 全选() {
getView().selectAll();
}

//删除指定位置文本
public void 删除文本(int 开始位置, int 结束位置) {
getView().getText().delete(开始位置, 结束位置);
}

//选中指定位置文本
public void 选中文本(int 开始位置, int 结束位置) {
getView().setSelection(开始位置, 结束位置);
}

//向编辑框指定位置插入文本
public void 插入文本(int 欲插入位置, String 欲插入文本) {
getView().getText().insert(欲插入位置, 欲插入文本);
}

//向编辑框中追加文本
public void 追加文本(String 内容) {
getView().getText().append(内容);
}

//显示输入法
public void 显示输入法() {
((android.view.inputmethod.InputMethodManager)context.getSystemService("input_method")).showSoftInput(getView(), 0);
}

//隐藏输入法
public void 隐藏输入法() {
((android.view.inputmethod.InputMethodManager)context.getSystemService("input_method")).hideSoftInputFromWindow(getView().getApplicationWindowToken(), 0);
}
}

//按钮组件

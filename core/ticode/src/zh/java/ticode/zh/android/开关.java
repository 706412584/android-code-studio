package ticode.zh.android;

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

import ticode.zh.base.异常;
import ticode.zh.base.文本;
import ticode.zh.jvm.正则匹配器;
import ticode.zh.jvm.正则表达式;

public class 开关 extends 复合按钮 {
public 开关(android.content.Context context) {
super(context);
}

@Override
public Switch onCreateView(android.content.Context context) {
Switch view = new Switch(context);
return view;
}

@Override
public Switch getView() {
return (Switch) view;
}

//设置开关打开时的文本
public void 文本_打开(String 文本) {
getView().setTextOn(文本);
}

//获取开关打开时的文本
public String 文本_打开() {
return getView().getTextOn().toString();
}

//设置开关关闭时的文本
public void 文本_关闭(String 文本) {
getView().setTextOff(文本);
}

//获取开关关闭时的文本
public String 文本_关闭() {
return getView().getTextOff().toString();
}
}
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

public class 进度条 extends android.widget.ImageView.ScaleType {
public 进度条(android.content.Context context) {
super(context);
}

@Override
public ProgressBar onCreateView(android.content.Context context) {
ProgressBar view = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
return view;
}

@Override
public ProgressBar getView() {
return (ProgressBar) view;
}

//设置进度条进度
public void 进度(int 进度值) {
getView().setProgress(进度值);
}

//获取进度条进度
public int 进度() {
return getView().getProgress();
}

//设置进度条的最大进度
public void 最大进度(int 最大进度值) {
getView().setMax(最大进度值);
}

//获取进度条的最大进度
public int 最大进度() {
return getView().getMax();
}

//设置进度条的缓冲进度，常用于缓冲音视频时设置缓冲进度
public void 缓冲进度(int 缓冲进度) {
getView().setSecondaryProgress(缓冲进度);
}

//获取进度条缓冲进度
public int 缓冲进度() {
return getView().getSecondaryProgress();
}

//设置进度条进度是否为模糊进度，如设置为真，则不再显示进度，而是一种无限刷新加载的状态
public void 模糊进度(boolean 是否不明确进度) {
getView().setIndeterminate(是否不明确进度);
}

//获取进度条是否为模糊进度状态
public boolean 模糊进度() {
return getView().isIndeterminate();
}
}
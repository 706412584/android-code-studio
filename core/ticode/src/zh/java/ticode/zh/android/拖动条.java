package ticode.zh.android;

import android.content.Context;
import android.content.*;
import android.content.res.*;
import android.view.*;
import android.widget.*;
import android.util.TypedValue;
import android.animation.Animator;
import android.view.animation.Animation;
import android.graphics.Typeface;
import android.text.Html;
import android.text.TextUtils;
import android.text.util.Linkify;
import android.text.method.LinkMovementMethod;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import android.text.*;
import android.text.style.*;
import android.widget.CompoundButton;
import android.widget.RadioButton;
import android.widget.CheckBox;
import android.widget.Switch;
import android.widget.ImageView.ScaleType;
import android.graphics.*;
import android.widget.ProgressBar;
import android.graphics.drawable.*;
import android.widget.SeekBar;
import android.widget.RatingBar;
import android.widget.VideoView;
import android.media.MediaPlayer;
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
import android.webkit.*;

import ticode.zh.base.异常;
import ticode.zh.base.文本;
import ticode.zh.jvm.正则匹配器;
import ticode.zh.jvm.正则表达式;

public class 拖动条 extends 进度条 {
public 拖动条(android.content.Context context) {
super(context);
getView().setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
@Override
public void onProgressChanged(SeekBar p1, int p2, boolean p3) {
进度被改变(p2, p3);
}
@Override
public void onStartTrackingTouch(SeekBar p1) {
开始拖动();
}
@Override
public void onStopTrackingTouch(SeekBar p1) {
结束拖动();
}
});
}

@Override
public SeekBar onCreateView(android.content.Context context) {
SeekBar view = new SeekBar(context);
return view;
}

@Override
public SeekBar getView() {
return (SeekBar) view;
}

public void 进度被改变(int 当前进度, boolean 是否人为改变) { } // 事件

public void 开始拖动() { } // 事件

public void 结束拖动() { } // 事件
}
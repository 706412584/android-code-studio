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

public class 窗口组件 extends 可标记类 {
protected Context context;

public 窗口组件(Context context) {
this(context, true);
}

public 窗口组件(Context context, boolean dispatchEvent) {
this.context = context;
if (dispatchEvent) {
onInit();
创建完毕();
}
}

protected void onInit() {
}

public void 创建完毕() {
}

public 安卓环境 取安卓环境() {
return context;
}

public 安卓窗口 取安卓窗口() {
return (安卓窗口)context;
}
}
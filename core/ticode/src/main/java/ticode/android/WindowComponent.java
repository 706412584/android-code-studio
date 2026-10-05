package ticode.android;

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

import ticode.jvm.JMatcher;
import ticode.jvm.JRegex;

public class WindowComponent extends Markable {
protected Context context;

public WindowComponent(Context context) {
this(context, true);
}

public WindowComponent(Context context, boolean dispatchEvent) {
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

public AndroidEnv 取安卓环境() {
return context;
}

public AndroidActivity 取安卓窗口() {
return (AndroidActivity)context;
}
}
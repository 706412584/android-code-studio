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

public class 进度圈 extends 可视化组件 {
public 进度圈(android.content.Context context) {
super(context);
}

public ProgressBar onCreateView(android.content.Context context) {
ProgressBar view = new ProgressBar(context);
return view;
}

public ProgressBar getView() {
return (ProgressBar) view;
}

//设置进度圈颜色，颜色是十六进制整数型颜色，0xaarrggbb格式
public void 颜色(int 颜色) {
getView().getIndeterminateDrawable().setColorFilter(new PorterDuffColorFilter(颜色,PorterDuff.Mode.SRC_ATOP));
}

//获取进度圈着色之后的颜色，返回整数型颜色
public int 颜色() {
return ((ColorDrawable)getView().getIndeterminateDrawable().getCurrent()).getColor();
}
}






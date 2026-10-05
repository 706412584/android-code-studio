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

public class 图像缩放类型 {
public static final android.widget.ImageView.ScaleType 矩阵;
public static final android.widget.ImageView.ScaleType 完全拉伸;
public static final android.widget.ImageView.ScaleType 左上;
public static final android.widget.ImageView.ScaleType 自适应居中;
public static final android.widget.ImageView.ScaleType 右下;
public static final android.widget.ImageView.ScaleType 居中;
public static final android.widget.ImageView.ScaleType 裁切居中;
public static final android.widget.ImageView.ScaleType 内置居中;

static {
矩阵=ScaleType.MATRIX;
完全拉伸=ScaleType.FIT_XY;
左上=ScaleType.FIT_START;
自适应居中=ScaleType.FIT_CENTER;
右下=ScaleType.FIT_END;
居中=ScaleType.CENTER;
裁切居中=ScaleType.CENTER_CROP;
内置居中=ScaleType.CENTER_INSIDE;
}

}
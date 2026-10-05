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

import ticode.jvm.JMatcher;
import ticode.jvm.JRegex;

public class ImageScaleType extends android.widget.ImageView.ScaleType {
public static final ImageScaleType 矩阵;
public static final ImageScaleType 完全拉伸;
public static final ImageScaleType 左上;
public static final ImageScaleType 自适应居中;
public static final ImageScaleType 右下;
public static final ImageScaleType 居中;
public static final ImageScaleType 裁切居中;
public static final ImageScaleType 内置居中;

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
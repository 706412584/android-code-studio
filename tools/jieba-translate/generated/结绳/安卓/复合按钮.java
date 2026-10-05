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

public class 复合按钮 extends 按钮 {
public 复合按钮(android.content.Context context) {
super(context);
getView().setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){
public void onCheckedChanged(CompoundButton btn, boolean checked) {
选中状态改变(checked);
}
});
}

public abstract CompoundButton onCreateView(android.content.Context context);

public abstract CompoundButton getView();

//设置复合类按钮的选中状态
public void 选中(boolean 是否选中) {
getView().setChecked(是否选中);
}

//获取复合类按钮的选中状态
public boolean 选中() {
return getView().isChecked();
}

//切换复合类按钮的选中状态
public void 切换状态() {
getView().toggle();
}




public void 选中状态改变(boolean 选中) { return null; } // 事件
}

//单选框组件

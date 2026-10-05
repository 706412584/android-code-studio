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

public class 评分条 extends 可视化组件 {
public 评分条(android.content.Context context) {
super(context);
getView().setOnRatingBarChangeListener(new RatingBar.OnRatingBarChangeListener(){
public void onRatingChanged(RatingBar p1, float p2, boolean p3) {
评分被改变(p2, p3);
}
});
}

public RatingBar onCreateView(android.content.Context context) {
RatingBar view = new RatingBar(context);
return view;
}

public RatingBar getView() {
return (RatingBar) view;
}

//设置评分条的星星数量
public void 总评分(int 数量) {
getView().setNumStars(数量);
}

//获取评分条的星星数量
public int 总评分() {
return getView().getNumStars();
}

//设置评分条的评分
public void 评分(double 评分) {
getView().setRating((float) 评分);
}

//获取评分条的评分
public double 评分() {
return getView().getRating();
}

//设置评分条的最小评分单位
public void 最小评分单位(double 评分) {
getView().setStepSize((float) 评分);
}

//获取评分条的最小评分单位
public double 最小评分单位() {
return getView().getStepSize();
}

//评分被改变时触发该事件
public void 评分被改变(double 评分, boolean 是否人为改变) { return null; } // 事件
}





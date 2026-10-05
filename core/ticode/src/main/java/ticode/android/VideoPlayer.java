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

public class VideoPlayer extends VisualComponent {
private boolean autoPlay;

public VideoPlayer(AndroidEnv context) {
super(context);
getView().setOnPreparedListener(new MediaPlayer.OnPreparedListener(){
@Override
public void onPrepared(MediaPlayer p1) {
if (autoPlay) {
getView().start();
}
视频加载完成();
}
});
getView().setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
@Override
public void onCompletion(MediaPlayer mp) {
视频播放完成();
}
});
getView().setOnErrorListener(new MediaPlayer.OnErrorListener() {
@Override
public boolean onError(MediaPlayer mp, int what, int extra) {
视频播放错误();
return true;
}
});
}

@Override
public VideoView onCreateView(android.content.Context context) {
VideoView view = new VideoView(context);
return view;
}

@Override
public VideoView getView() {
return (VideoView) view;
}

//设置播放视频的路径，可以为本地路径，也可以为网络路径,第二个参数为是否自动播放，表示视频加载完成后是否自动播放
public void 置播放路径(String 路径, boolean 是否自动播放) {
if (路径.startsWith("http")) {
getView().setVideoURI(android.net.Uri.parse(路径));
} else if (路径.startsWith("/")) {
getView().setVideoPath(路径);
}
autoPlay = 是否自动播放;
}

//开始播放视频
public void 开始播放() {
getView().start();
}

//暂停播放视频
public void 暂停播放() {
getView().pause();
}

//停止播放视频
public void 停止播放() {
getView().stopPlayback();
}

//获取视频总时长
public int 取视频总时长() {
return getView().getDuration();
}

//获取当前播放位置
public int 取当前播放位置() {
return getView().getCurrentPosition();
}

//快进至指定位置
public void 快进至(int 位置) {
getView().seekTo(位置);
}

//重新播放
public void 重新播放() {
getView().resume();
}

//判断视频播放器是否在播放视频
public boolean 是否在播放() {
return getView().isPlaying();
}

//视频加载完成时触发该事件
public void 视频加载完成() { } // 事件

//视频播放完成时触发该事件
public void 视频播放完成() { } // 事件

//视频播放错误时触发该事件
public void 视频播放错误() { } // 事件
}
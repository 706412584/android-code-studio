package 结绳.安卓;

import android.media.*;
import android.os.*;
import java.util.*;
import android.os.Vibrator;
import android.content.Context;

public class 音乐播放器 {

private MediaPlayer player;
private Timer mTimer;
private TimerTask mTimerTask;
private Handler handleProgress;
private boolean autoPlay = true;

public 音乐播放器() {
this.player = new MediaPlayer();
this.mTimer = new Timer();
player.setAudioStreamType(AudioManager.STREAM_MUSIC);
player.setOnBufferingUpdateListener(new MediaPlayer.OnBufferingUpdateListener(){
public void onBufferingUpdate(MediaPlayer p1, int p2){
音乐正在缓冲(p2);
}
});
player.setOnPreparedListener(new MediaPlayer.OnPreparedListener(){
public void onPrepared(MediaPlayer p1){
if (autoPlay) {
if(p1.isPlaying()){
p1.stop();
p1.start();
}else{
p1.start();
}
}
音乐缓冲完毕();
}
});
player.setOnCompletionListener(new MediaPlayer.OnCompletionListener(){
public void onCompletion(MediaPlayer p1) {
音乐播放完毕();
}
});
mTimerTask = new TimerTask() {
public void run(){
if (player == null)
return;
if (player.isPlaying()){
handleProgress.sendEmptyMessage(0);
}
}
};
mTimer.schedule(mTimerTask, 0,1000);
handleProgress = new Handler() {
public void handleMessage(Message msg){
音乐正在播放();
};
};
}

//设置音乐播放器是否循环播放
public void 循环播放(boolean 是否循环播放) {
player.setLooping(是否循环播放);
}

//获取音乐播放器是否循环播放
public boolean 循环播放() {
return player.isLooping();
}

//设置播放音乐的路径，可以为本地路径，也可以为网络路径,第二个参数为是否自动播放，表示视频加载完成后是否自动播放
public void 置播放路径(String 路径, boolean 是否自动播放) {
this.autoPlay = 是否自动播放;
player.reset();
try {
player.setDataSource(路径);
player.prepareAsync();
} catch (Exception e) {
e.printStackTrace();
}
}

//开始播放音乐
public void 开始播放() {
player.start();
}

//暂停播放音乐
public void 暂停播放() {
player.pause();
}

//停止播放音乐
public void 停止播放() {
player.stop();
}

//获取音乐总时长
public int 取音乐总时长() {
return player.getDuration();
}

//获取当前播放位置
public int 取当前播放位置() {
return player.getCurrentPosition();
}

//快进至指定位置
public void 快进至(int 位置) {
player.seekTo(位置);
}

//设置播放音乐的音量，分别设置左声道音量和右声道音量
public void 置音量(double 左声道音量, double 右声道音量) {
player.setVolume((float) 左声道音量, (float) 右声道音量);
}

//重置音乐播放器
public void 重置() {
player.reset();
}

//释放资源
public void 释放资源() {
player.release();
}

//判断音乐播放器是否在播放音乐
public boolean 是否在播放() {
return player.isPlaying();
}

//音乐正在缓冲时触发该事件，返回缓冲进度
public void 音乐正在缓冲(int 进度) { return null; } // 事件

//音乐缓冲完成时触发该事件
public void 音乐缓冲完毕() { return null; } // 事件

//音乐正在播放时触发该事件
public void 音乐正在播放() { return null; } // 事件

//音乐播放完毕时触发该事件
public void 音乐播放完毕() { return null; } // 事件
}


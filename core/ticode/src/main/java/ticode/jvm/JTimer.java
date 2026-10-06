package ticode.jvm;

import java.util.Timer;
import java.util.TimerTask;
import android.os.Handler;
import android.os.Message;
import android.os.Looper;

public class JTimer {
private Timer timer = new Timer();
private final Object lock = new Object(); //添加线程锁

private Handler handler = new Handler(Looper.getMainLooper()){
@Override
public void handleMessage(Message msg) {
super.handleMessage(msg);
定时事件();
}
};






public void 开始定时(long 定时周期, long 延迟时间) {
synchronized (lock) {
if (timer == null) {
return;
}
timer.schedule(new TimerTask(){
@Override
public void run() {
handler.sendEmptyMessage(0);
}
}, 延迟时间, 定时周期);
}
}

//关闭定时器，关闭以后无法再使用定时器，需重新创建定时器
public void 关闭() {
synchronized (lock) {
if (timer != null) {
timer.cancel();
timer = null;
}
handler.removeMessages(0);
}
}

public void 定时事件() { } // 事件
}
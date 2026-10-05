package ticode.android;

import android.os.Message;

public class Clock2 extends Thread {
private boolean enabled;
private int period;
private android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

@Override
public void run() {
if (enabled) {
周期事件();
handler.postDelayed(this, period);
}
}

//设置时钟周期
public void 时钟周期(int 周期) {
this.period = 周期;
if (period > 0) {
enabled = true;
} else {
enabled = false;
}
if (enabled) {
handler.removeCallbacks(this);
handler.postDelayed(this, period);
}
}

//获取时钟周期
public int 时钟周期() {
return period;
}

//设置时钟是否可用
public void 可用(boolean 是否可用) {
enabled = 是否可用;
if (enabled) {
handler.removeCallbacks(this);
handler.postDelayed(this, period);
}
}

//获取时钟是否可用
public boolean 可用() {
return enabled;
}

public void 周期事件() { } // 事件
}
package ticode.zh.android;


public class 组件动画 extends android.view.animation.Animation {
public void 重置() {
this.reset();
}

public void 取消() {
this.cancel();
}

public void 开始播放() {
this.start();
}

public void 立即播放() {
this.startNow();
}

public void 重复次数(int 次数) {
this.setRepeatCount(次数);
}

public int 重复次数() {
return this.getRepeatCount();
}

public void 保持最终状态(boolean 是否保持) {
this.setFillAfter(是否保持);
}

public boolean 已开始() {
return this.hasStarted();
}

public boolean 已播放完毕() {
return this.hasEnded();
}

//获取动画的播放时长
public long 播放时间() {
return this.getDuration();
}

//设置动画播放时长
public void 播放时间(long 时长) {
this.setDuration(时长);
}

//获取动画的播放时长
public long 延迟时间() {
return this.getStartTime();
}

//设置动画播放时长
public void 延时时间(long 时长) {
this.setStartTime(时长);
}
}
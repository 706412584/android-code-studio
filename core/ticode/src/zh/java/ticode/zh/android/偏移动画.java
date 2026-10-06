package ticode.zh.android;


public class 偏移动画 extends android.view.animation.TranslateAnimation {
public 偏移动画(double 起始横向偏移, double 结束横向偏移, double 起始纵向偏移, double 结束纵向偏移) { super((float) 起始横向偏移,
(float) 结束横向偏移,
(float) 起始纵向偏移,
(float) 结束纵向偏移); }

public static android.view.animation.TranslateAnimation 新建(double 起始横向偏移, double 结束横向偏移, double 起始纵向偏移, double 结束纵向偏移) {
return new android.view.animation.TranslateAnimation(
(float) 起始横向偏移,
(float) 结束横向偏移,
(float) 起始纵向偏移,
(float) 结束纵向偏移
);
}
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
public void 保持最终状态(boolean 是否保持) {
this.setFillAfter(是否保持);
}
public boolean 已开始() {
return this.hasStarted();
}
public boolean 已播放完毕() {
return this.hasEnded();
}
public long 播放时间() {
return this.getDuration();
}
public long 延迟时间() {
return this.getStartTime();
}
public void 延时时间(long 时长) {
this.setStartTime(时长);
}
}
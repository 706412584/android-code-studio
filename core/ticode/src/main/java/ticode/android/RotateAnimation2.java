package ticode.android;


public class RotateAnimation2 extends android.view.animation.RotateAnimation {
public RotateAnimation2(double 开始时角度, double 结束时角度) { 赋值_op(开始时角度, 结束时角度); }
public RotateAnimation2 赋值_op(double 开始时角度, double 结束时角度) {
return new RotateAnimation2((float) 开始时角度, (float) 结束时角度);
}

public static RotateAnimation2 新建(double 开始时角度, double 结束时角度) {
return new RotateAnimation2((float) 开始时角度, (float) 结束时角度);
}

public static RotateAnimation2 新建2(double 开始时角度, double 结束时角度, double 中心点横坐标, double 中心点纵坐标) {
return new RotateAnimation2((float) 开始时角度, (float) 结束时角度, (float) 中心点横坐标, (float) 中心点纵坐标);
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
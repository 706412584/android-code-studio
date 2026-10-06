package ticode.zh.android;


public class 组件动画集合 extends android.view.animation.AnimationSet {
public 组件动画集合() { super(true); }

public static android.view.animation.AnimationSet 新建集合() {
return new android.view.animation.AnimationSet(true);
}

//添加一个动画到动画集合中
public void 添加动画(组件动画 欲添加动画) {
this.addAnimation(欲添加动画);
}

public 组件动画[] 取所有动画() {
return (组件动画[])this.getAnimations().toArray(new 组件动画[0]);
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
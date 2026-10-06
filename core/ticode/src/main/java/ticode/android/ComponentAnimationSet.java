package ticode.android;


public class ComponentAnimationSet extends android.view.animation.AnimationSet {

public static ComponentAnimationSet 新建集合() {
return new ComponentAnimationSet(true);
}

//添加一个动画到动画集合中
public void 添加动画(ComponentAnimation 欲添加动画) {
this.addAnimation(欲添加动画);
}

public ComponentAnimation[] 取所有动画() {
return this.getAnimations().toArray(new ComponentAnimation[0]);
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
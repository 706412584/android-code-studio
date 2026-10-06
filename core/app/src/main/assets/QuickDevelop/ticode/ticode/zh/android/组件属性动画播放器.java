package ticode.zh.android;


public class 组件属性动画播放器 {
private android.view.ViewPropertyAnimator 内部对象;

public 组件属性动画播放器(android.view.ViewPropertyAnimator 动画对象) {
this.内部对象 = 动画对象;
}

public android.view.ViewPropertyAnimator 取内部对象() {
return this.内部对象;
}

//获取动画的播放时长
public long 播放时间() {
return this.内部对象.getDuration();
}

//设置动画播放时长
public void 播放时间(long 时长) {
this.内部对象.setDuration(时长);
}

//获取动画开始播放的延迟时间
public long 延迟时间() {
return this.内部对象.getStartDelay();
}

//设置动画开始播放的延迟时间
public void 延迟时间(long 时长) {
this.内部对象.setStartDelay(时长);
}

public void 横坐标(double 横坐标) {
this.内部对象.x((float) 横坐标);
}

public void 横坐标平移(double 横坐标) {
this.内部对象.xBy((float) 横坐标);
}

public void 纵坐标(double 纵坐标) {
this.内部对象.y((float) 纵坐标);
}

public void 纵坐标平移(double 纵坐标) {
this.内部对象.yBy((float) 纵坐标);
}

public void 竖坐标(double 竖坐标) {
this.内部对象.z((float) 竖坐标);
}

public void 竖坐标平移(double 竖坐标) {
this.内部对象.zBy((float) 竖坐标);
}

public void 旋转角(double 旋转角) {
this.内部对象.rotation((float) 旋转角);
}

public void 增加旋转角(double 旋转角) {
this.内部对象.rotationBy((float) 旋转角);
}

public void X轴旋转角(double 旋转角) {
this.内部对象.rotationX((float) 旋转角);
}

public void 增加X轴旋转角(double 旋转角) {
this.内部对象.rotationXBy((float) 旋转角);
}

public void Y轴旋转角(double 旋转角) {
this.内部对象.rotationY((float) 旋转角);
}

public void 增加Y轴旋转角(double 旋转角) {
this.内部对象.rotationYBy((float) 旋转角);
}

public void 横向偏移(double 偏移量) {
this.内部对象.translationX((float) 偏移量);
}

public void 增加横向偏移(double 偏移量) {
this.内部对象.translationXBy((float) 偏移量);
}

public void 纵向偏移(double 偏移量) {
this.内部对象.translationY((float) 偏移量);
}

public void 增加纵向偏移(double 偏移量) {
this.内部对象.translationYBy((float) 偏移量);
}

public void 竖向偏移(double 偏移量) {
this.内部对象.translationZ((float) 偏移量);
}

public void 增加竖向偏移(double 偏移量) {
this.内部对象.translationZBy((float) 偏移量);
}

public void 横向缩放度(double 缩放度) {
this.内部对象.scaleX((float) 缩放度);
}

public void 增加横向缩放度(double 缩放度) {
this.内部对象.scaleXBy((float) 缩放度);
}

public void 纵向缩放度(double 缩放度) {
this.内部对象.scaleY((float) 缩放度);
}

public void 增加纵向缩放度(double 缩放度) {
this.内部对象.scaleYBy((float) 缩放度);
}

public void 透明度(double 透明度) {
this.内部对象.alpha((float) 透明度);
}

public void 增加透明度(double 透明度) {
this.内部对象.alphaBy((float) 透明度);
}

//开始播放动画
public void 开始播放() {
this.内部对象.start();
}

//取消播放动画
public void 取消播放() {
this.内部对象.start();
}
}

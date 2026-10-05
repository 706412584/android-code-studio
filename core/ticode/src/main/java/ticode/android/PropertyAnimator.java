package ticode.android;


public class PropertyAnimator extends android.view.ViewPropertyAnimator {
//获取动画的播放时长
public long 播放时间() {
return this.getDuration();
}

//设置动画播放时长
public void 播放时间(long 时长) {
this.setDuration(时长);
}

//获取动画开始播放的延迟时间
public long 延迟时间() {
return this.getStartDelay();
}

//设置动画开始播放的延迟时间
public void 延迟时间(long 时长) {
this.setStartDelay(时长);
}

public void 横坐标(double 横坐标) {
this.x((float) 横坐标);
}

public void 横坐标平移(double 横坐标) {
this.xBy((float) 横坐标);
}

public void 纵坐标(double 纵坐标) {
this.y((float) 纵坐标);
}

public void 纵坐标平移(double 纵坐标) {
this.yBy((float) 纵坐标);
}

public void 竖坐标(double 竖坐标) {
this.z((float) 竖坐标);
}

public void 竖坐标平移(double 竖坐标) {
this.zBy((float) 竖坐标);
}

public void 旋转角(double 旋转角) {
this.rotation((float) 旋转角);
}

public void 增加旋转角(double 旋转角) {
this.rotationBy((float) 旋转角);
}

public void X轴旋转角(double 旋转角) {
this.rotationX((float) 旋转角);
}

public void 增加X轴旋转角(double 旋转角) {
this.rotationXBy((float) 旋转角);
}

public void Y轴旋转角(double 旋转角) {
this.rotationY((float) 旋转角);
}

public void 增加Y轴旋转角(double 旋转角) {
this.rotationYBy((float) 旋转角);
}

public void 横向偏移(double 偏移量) {
this.translationX((float) 偏移量);
}

public void 增加横向偏移(double 偏移量) {
this.translationXBy((float) 偏移量);
}

public void 纵向偏移(double 偏移量) {
this.translationY((float) 偏移量);
}

public void 增加纵向偏移(double 偏移量) {
this.translationYBy((float) 偏移量);
}

public void 竖向偏移(double 偏移量) {
this.translationZ((float) 偏移量);
}

public void 增加竖向偏移(double 偏移量) {
this.translationZBy((float) 偏移量);
}

public void 横向缩放度(double 缩放度) {
this.scaleX((float) 缩放度);
}

public void 增加横向缩放度(double 缩放度) {
this.scaleXBy((float) 缩放度);
}

public void 纵向缩放度(double 缩放度) {
this.scaleY((float) 缩放度);
}

public void 增加纵向缩放度(double 缩放度) {
this.scaleYBy((float) 缩放度);
}

public void 透明度(double 透明度) {
this.alpha((float) 透明度);
}

public void 增加透明度(double 透明度) {
this.alphaBy((float) 透明度);
}

//开始播放动画
public void 开始播放() {
this.start();
}

//取消播放动画
public void 取消播放() {
this.start();
}
}
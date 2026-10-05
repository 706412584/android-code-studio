package ticode.zh.android;


public class 旋转动画 extends android.view.animation.RotateAnimation {
public void 赋值_op(double 开始时角度, double 结束时角度) {
return new 旋转动画((float) 开始时角度, (float) 结束时角度);
}

public static 旋转动画 新建(double 开始时角度, double 结束时角度) {
return new 旋转动画((float) 开始时角度, (float) 结束时角度);
}

public static 旋转动画 新建2(double 开始时角度, double 结束时角度, double 中心点横坐标, double 中心点纵坐标) {
return new 旋转动画((float) 开始时角度, (float) 结束时角度, (float) 中心点横坐标, (float) 中心点纵坐标);
}
}
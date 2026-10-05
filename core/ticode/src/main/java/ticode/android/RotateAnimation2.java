package ticode.android;


public class RotateAnimation2 extends android.view.animation.RotateAnimation {
public void 赋值_op(double 开始时角度, double 结束时角度) {
return new RotateAnimation2((float) 开始时角度, (float) 结束时角度);
}

public static RotateAnimation2 新建(double 开始时角度, double 结束时角度) {
return new RotateAnimation2((float) 开始时角度, (float) 结束时角度);
}

public static RotateAnimation2 新建2(double 开始时角度, double 结束时角度, double 中心点横坐标, double 中心点纵坐标) {
return new RotateAnimation2((float) 开始时角度, (float) 结束时角度, (float) 中心点横坐标, (float) 中心点纵坐标);
}
}
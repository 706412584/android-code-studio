package ticode.android;


public class TouchEvent2 extends android.view.MotionEvent {



public int 动作() {
return this.getAction();
}




public int 当前动作() {
return this.getActionMasked();
}




public int 触摸点数量() {
return this.getPointerCount();
}




public double 原始横坐标() {
return this.getRawX();
}




public double 原始纵坐标() {
return this.getRawY();
}





public double 取横坐标(int 索引) {
return this.getX(索引);
}





public double 取纵坐标(int 索引) {
return this.getY(索引);
}
public int 设备ID() {
return this.getDeviceId();
}
public long 时间() {
return this.getEventTime();
}
}
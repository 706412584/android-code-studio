package ticode.android;

import android.content.Context;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.OnScaleGestureListener;

public class TouchEvent2 extends InputEvent2 {



public TouchAction 动作() {
return this.getAction();
}




public TouchAction 当前动作() {
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
}
package ticode.zh.android;

import android.content.Context;
import android.view.ScaleGestureDetector;

public class 拖放事件 extends android.view.DragEvent {



public 拖放动作 动作() {
return this.getAction();
}




public double 横坐标() {
return this.getX();
}




public double 纵坐标() {
return this.getY();
}
}
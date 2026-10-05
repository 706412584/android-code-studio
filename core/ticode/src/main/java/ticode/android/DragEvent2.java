package ticode.android;

import android.content.Context;
import android.view.ScaleGestureDetector;

public class DragEvent2 extends android.view.DragEvent {



public DragAction 动作() {
return this.getAction();
}




public double 横坐标() {
return this.getX();
}




public double 纵坐标() {
return this.getY();
}
}
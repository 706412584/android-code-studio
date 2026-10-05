package 结绳.安卓;

import android.content.Context;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.OnScaleGestureListener;

public class 按键事件 extends 输入事件 {



public 按键动作 动作() {
return this.getAction();
}




public int 按键代码() {
return this.getKeyCode();
}
}

//通过传递触摸事件,进行识别处理缩放手势

package ticode.android;

import android.content.Context;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.OnScaleGestureListener;

public class KeyEvent2 extends android.view.KeyEvent {



public KeyAction 动作() {
return this.getAction();
}




public int 按键代码() {
return this.getKeyCode();
}
}
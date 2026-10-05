package ticode.zh.android;

import android.content.Context;
import android.view.ScaleGestureDetector;

public class 按键事件 extends android.view.KeyEvent {



public 按键动作 动作() {
return this.getAction();
}




public int 按键代码() {
return this.getKeyCode();
}
}
package ticode.android;


public class KeyEvent2 extends android.view.KeyEvent {



public int 动作() {
return this.getAction();
}




public int 按键代码() {
return this.getKeyCode();
}
public int 设备ID() {
return this.getDeviceId();
}
public long 时间() {
return this.getEventTime();
}
}
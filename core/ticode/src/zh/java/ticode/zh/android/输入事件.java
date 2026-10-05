package ticode.zh.android;


public abstract class 输入事件 extends android.view.InputEvent {



public int 设备ID() {
return this.getDeviceId();
}




public long 时间() {
return this.getEventTime();
}
}
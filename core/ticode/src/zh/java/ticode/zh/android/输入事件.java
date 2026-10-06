package ticode.zh.android;


public class 输入事件 {
private android.view.InputEvent 内部对象;

public 输入事件(android.view.InputEvent 事件对象) {
this.内部对象 = 事件对象;
}

public android.view.InputEvent 取内部对象() {
return this.内部对象;
}

public int 设备ID() {
return this.内部对象.getDeviceId();
}

public long 时间() {
return this.内部对象.getEventTime();
}
}

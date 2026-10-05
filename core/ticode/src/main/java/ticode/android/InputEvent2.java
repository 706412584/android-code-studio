package ticode.android;

import android.content.Context;
import android.view.ScaleGestureDetector;

public class InputEvent2 extends android.view.InputEvent {



public int 设备ID() {
return this.getDeviceId();
}




public long 时间() {
return this.getEventTime();
}
}
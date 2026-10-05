package ticode.android;

import android.hardware.*;
import android.view.WindowManager;
import java.util.*;
import android.location.*;
import android.os.*;
import android.content.*;

public class LightSensor extends WindowComponent {

private SensorManager sensors;
@Override
public void onSensorChanged(SensorEvent event) {
if (event.sensor.getType() == 5) {
光线改变(event.values[0]);
}
}

@Override
public void onAccuracyChanged(Sensor sensor, int accuracy) {
}

public LightSensor(AndroidActivity activity) {
super(activity);
sensors = (SensorManager) activity.getSystemService("sensor");
sensors.registerListener(this, sensors.getDefaultSensor(5), 1);
}

//判断方向传感器是否有效
public boolean 有效() {
List<Sensor> sensorList = this.sensors.getSensorList(5);
return sensorList != null && !sensorList.isEmpty();
}

//当手机周围光线发生变化时触发此事件，返回光线强度，单位为勒克斯
public void 光线改变(double 光线强度) { } // 事件

}
package 结绳.安卓;

import android.hardware.*;
import android.view.WindowManager;
import android.hardware.*;
import android.view.WindowManager;
import java.util.*;
import android.hardware.*;
import android.view.WindowManager;
import java.util.*;
import android.hardware.*;
import android.view.WindowManager;
import java.util.*;
import android.hardware.*;
import android.location.*;
import android.os.*;
import android.content.*;
import android.view.WindowManager;
import java.util.*;

public class 方向传感器 extends 窗口组件 {
public void onSensorChanged(SensorEvent event) {
if (event.sensor.getType() == 3 && this.enabled) {
this.yaw = event.values[0];
this.pitch = event.values[1];
this.roll = event.values[2];
方向改变(this.yaw, this.pitch, this.roll);
}
}

public void onAccuracyChanged(Sensor sensor, int accuracy) {
}
private boolean enabled;
private float pitch;
private float roll;
private SensorManager sensors;
private float yaw;
private 安卓窗口 activity;
public 方向传感器(安卓窗口 activity) {
super(activity);
this.activity = activity;
sensors = ((SensorManager) activity.getSystemService("sensor"));
sensors.registerListener(this, this.sensors.getDefaultSensor(3), 1);
}

//判断手机方向传感器是否有效
public boolean 有效() {
java.util.List<Sensor> sensorList = this.sensors.getSensorList(3);
return sensorList != null && !sensorList.isEmpty();
}

//获取方向传感器是否可用
public boolean 可用() {
return this.enabled;
}

//设置方向传感器是否可用
public void 可用(boolean 是否可用) {
this.enabled = 是否可用;
}

//获取方向传感器的俯仰角
public double 俯仰角() {
return pitch;
}

//获取方向传感器的横滚角
public double 横滚角() {
return roll;
}

//获取方向传感器的偏航角
public double 偏航角() {
return yaw;
}

//获取倾斜角
public double 倾斜角() {
return Math.toDegrees(Math.atan2(pitch,roll));
}

//获取屏幕旋转的角度，只能返回0,90,180,270
public int 取屏幕旋转角度() {
switch (((WindowManager) activity.getSystemService("window")).getDefaultDisplay().getRotation()) {
case 0:
return 0;
case 1:
return 90;
case 2:
return 180;
case 3:
return 270;
default:
return 0;
}
}

//方向改变时触发该事件
public void 方向改变(double 偏航角, double 俯仰角, double 横滚角) { return null; } // 事件

}






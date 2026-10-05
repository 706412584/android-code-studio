package ticode.android;

import android.hardware.*;
import android.view.WindowManager;
import java.util.*;
import android.location.*;
import android.os.*;
import android.content.*;

public class AccelerometerSensor extends WindowComponent {
private static final int SENSOR_CACHE_SIZE = 10;
private static final double SHAKE_THRESHOLD = 8.0d;
private final Queue<Float> X_CACHE = new LinkedList<>();
private final Queue<Float> Y_CACHE = new LinkedList<>();
private final Queue<Float> Z_CACHE = new LinkedList<>();
private boolean enabled;
private final Sensor sensor;
private float xAccel;
private float yAccel;
private float zAccel;

private void addToSensorCache(Queue<Float> cache, float value) {
if (cache.size() >= 10) {
cache.remove();
}
cache.add(Float.valueOf(value));
}

private boolean isShaking(Queue<Float> cache, float currentValue) {
float average = 0.0f;
for (Float floatValue : cache) {
average += floatValue.floatValue();
}
return ((double) Math.abs((average / ((float) cache.size())) - currentValue)) > 8.0d;
}

@Override
public void onSensorChanged(SensorEvent event) {
if (event.sensor.getType() == 1 && this.enabled) {
this.xAccel = event.values[0];
this.yAccel = event.values[1];
this.zAccel = event.values[2];
addToSensorCache(this.X_CACHE, xAccel);
addToSensorCache(this.Y_CACHE, yAccel);
addToSensorCache(this.Z_CACHE, zAccel);
if (isShaking(this.X_CACHE, xAccel) || isShaking(this.Y_CACHE, yAccel) || isShaking(this.Z_CACHE, zAccel)) {
摇晃手机();
}
加速度改变(xAccel, yAccel, zAccel);
}
}

@Override
public void onAccuracyChanged(Sensor s, int accuracy) {
}

public AccelerometerSensor(AndroidActivity activity) {
super(activity);
SensorManager sensors = (SensorManager) activity.getSystemService("sensor");
this.sensor = sensors.getDefaultSensor(1);
if (this.sensor != null) {
sensors.registerListener(this, this.sensor, 1);
}
}

//判断加速度传感器是否有效
public boolean 有效() {
return this.sensor != null;
}

//判断加速度传感器是否可用
public boolean 可用() {
return this.enabled;
}

//设置加速度传感器是否可用
public void 可用(boolean 是否可用) {
this.enabled = 是否可用;
}

//获取X方向的加速度
public double X加速度() {
return xAccel;
}

//获取Y方向的加速度
public double Y加速度() {
return yAccel;
}

//获取Z方向的加速度
public double Z加速度() {
return zAccel;
}

//加速度改变时触发该事件，并返回三个方向的加速度
public void 加速度改变(double X加速度, double Y加速度, double Z加速度) { } // 事件

//用户摇晃手机时触发该事件
public void 摇晃手机() { } // 事件

}
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

public class 距离传感器 extends 窗口组件 {

private 安卓环境 mContext;
private float lastValue;
private int mRate = 3;
private Sensor mSensor;
private SensorEventListener mSensorListener;
private SensorManager mSensorManager;
private int min = 1;
private float value = -999.0f;
private boolean enable;

public 距离传感器(安卓环境 context) {
super(context);
this.mContext = context;
mSensorManager = ((SensorManager) mContext.getSystemService("sensor"));
mSensor = mSensorManager.getDefaultSensor(8);
if (mSensor != null) {
enable = true;
} else {
enable = false;
}
mSensorListener = new SensorEventListener() {
public void onAccuracyChanged(Sensor sensor, int accuracy) {
}

public void onSensorChanged(SensorEvent event) {
if (event.sensor.getType() == 8) {
value = event.values[0];
if (value== 0 || value < min) {
物体靠近();
}
if (value == 1 || value > min) {
物体离开();
}
if (lastValue != value) {
lastValue = value;
距离改变(value);
}
}
}
};
if (mSensor != null) {
mSensorManager.registerListener(this.mSensorListener, this.mSensor, this.mRate);
}
}

//判断距离传感器是否可用
public boolean 可用() {
return this.enable;
}

//设置距离传感器是否可用
public void 可用(boolean 是否可用) {
if (this.mSensor != null) {
if (this.enable && !是否可用) {
this.mSensorManager.unregisterListener(this.mSensorListener);
this.enable = false;
}
if (!this.enable && 是否可用) {
this.mSensorManager.registerListener(this.mSensorListener, this.mSensor, this.mRate);
this.enable = true;
}
}
}

//获取物体靠近的距离
public double 距离() {
return this.lastValue;
}

//获取物体靠近的最小距离
public int 最小距离() {
return this.min;
}

//设置物体靠近的最小距离
public void 最小距离(int 最小距离) {
this.min = 最小距离;
}

//获取距离传感器的检测间隔时间
public int 检测间隔() {
return this.mRate;
}

//设置距离传感器的检测间隔时间
public void 检测间隔(int 间隔时间) {
this.mSensorManager.unregisterListener(this.mSensorListener);
this.mRate = 间隔时间;
this.mSensorManager.registerListener(this.mSensorListener, this.mSensor, this.mRate);
}

//当物体与手机的距离发生改变时触发此事件，返回两者的距离。
public void 距离改变(double 距离) { return null; } // 事件

//放物体靠近手机时触发该事件
public void 物体靠近() { return null; } // 事件

//当物体离开手机时触发该事件
public void 物体离开() { return null; } // 事件
}





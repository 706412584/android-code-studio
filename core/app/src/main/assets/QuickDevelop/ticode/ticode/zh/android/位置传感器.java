package ticode.zh.android;

import android.hardware.*;
import java.util.*;
import android.location.*;
import android.os.*;
import android.content.*;

public class 位置传感器 extends 窗口组件 {

private Criteria criteria;
private Location location;
private LocationManager locationManager;
private Context mContext;
private String provider;
private final LocationListener locationListener = new LocationListener() {
@Override
public void onLocationChanged(Location location) {
if (location != null) {
位置改变(location.getLatitude(), location.getLongitude(), location.getAltitude(), (double) location.getSpeed(), (double) location.getBearing());
}
}

@Override
public void onProviderDisabled(String provider) {
设备关闭();
}

@Override
public void onProviderEnabled(String provider) {
设备开启();
}

@Override
public void onStatusChanged(String provider, int status, Bundle extras) {
switch (status) {
case 0:
状态改变(2);
break;
case 1:
状态改变(3);
break;
case 2:
状态改变(1);
break;
}
}
};

public 位置传感器(android.content.Context context) {
super(context);
this.mContext = context;
locationManager = ((LocationManager) mContext.getSystemService("location"));
}

//判断GPS硬件是否有效
public boolean 有效() {
if (this.locationManager == null) {
return false;
}
List<String> providers = locationManager.getAllProviders();
if (providers != null) {
return providers.contains("gps");
}
return false;
}

//判断GPS是否已开启
public boolean 是否已开启() {
return locationManager.isProviderEnabled("gps");
}

//获取当前纬度
public double 纬度() {
if (this.location != null) {
return this.location.getLatitude();
}
return 0.0d;
}

//获取当前经度
public double 经度() {
if (this.location != null) {
return this.location.getLongitude();
}
return 0.0d;
}

//获取当前海拔，单位为m
public double 海拔() {
if (this.location != null) {
return this.location.getAltitude();
}
return 0.0d;
}

//获取当前速度，单位为km/h
public double 速度() {
if (this.location != null) {
return (double) this.location.getSpeed();
}
return 0.0d;
}

//获取当前方向角，为当前方向与正北方向的顺时针夹角度数
public double 方向() {
if (this.location != null) {
return (double) this.location.getBearing();
}
return 0.0d;
}

//获取当前定位精度，单位为m
public double 精度() {
if (this.location == null || !this.location.hasAccuracy()) {
return 0.0d;
}
return (double) this.location.getAccuracy();
}

//获取当前卫星时间
public String 时间() {
if (this.location != null) {
return new java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss").format(new Date(this.location.getTime()));
}
return "";
}

//打开GPS设置界面，让用户选择是否开启GPS
public void 打开设置界面() {
Intent intent = new Intent();
intent.setAction("android.settings.LOCATION_SOURCE_SETTINGS");
intent.setFlags(268435456);
try {
mContext.startActivity(intent);
} catch (ActivityNotFoundException e) {
e.printStackTrace();
intent.setAction("android.settings.SETTINGS");
try {
mContext.startActivity(intent);
} catch (Exception e2) {
e2.printStackTrace();
}
}
}

//开始定位并监测当前位置，当获取到位置或者位置发生改变时触发位置改变事件
public void 开始监测() {
if (this.locationManager.isProviderEnabled("gps")) {
if (this.criteria == null) {
this.criteria = new Criteria();
this.criteria.setAccuracy(1);
this.criteria.setAltitudeRequired(true);
this.criteria.setBearingRequired(true);
this.criteria.setCostAllowed(true);
this.criteria.setPowerRequirement(1);
}
this.provider = this.locationManager.getBestProvider(this.criteria, true);
this.locationManager.requestLocationUpdates("gps", 1000, 0.0f, this.locationListener);
this.location = this.locationManager.getLastKnownLocation("gps");
}
}

//停止定位和监测
public void 停止监测() {
this.locationManager.removeUpdates(this.locationListener);
}






public void 位置改变(double 纬度, double 经度, double 海拔, double 速度, double 方向) { } // 事件





public void 状态改变(int 状态) { } // 事件

//GPS开启时触发该事件
public void 设备开启() { } // 事件

//GPS关闭时触发该事件
public void 设备关闭() { } // 事件

}
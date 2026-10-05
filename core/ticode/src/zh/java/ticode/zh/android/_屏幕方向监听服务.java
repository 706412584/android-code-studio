package ticode.zh.android;

import android.content.*;
import android.app.Activity;
import android.view.*;
import android.widget.*;
import java.util.*;
import android.util.*;
import android.graphics.*;

import ticode.zh.base.对象;
import ticode.zh.jvm.集合;

public class _屏幕方向监听服务 extends 服务 {

public static void 启动服务(安卓环境 环境) {
startService(环境, _屏幕方向监听服务.class);
}
public static void 停止服务(安卓环境 环境) {
stopService(环境, _屏幕方向监听服务.class);
}

public static boolean start = true;
public static void startService(安卓环境 c, Class<?> cla){c.startService(new 启动信息(c, cla));}
public static void stopService(安卓环境 c, Class<?> cla){c.stopService(new 启动信息(c, cla));}
public android.content.BroadcastReceiver br = new android.content.BroadcastReceiver() {
public void onReceive(安卓环境 context, 启动信息 intent) {
if (intent.getAction().equals(启动信息.ACTION_CONFIGURATION_CHANGED)) {
int w = getResources().getDisplayMetrics().widthPixels;
int h = getResources().getDisplayMetrics().heightPixels;
for (悬浮窗 fw : 悬浮窗.FMap.values()) {
if(fw.isAutoScreenOrientation()) fw.screenOrientationChange(w, h);
}
}
}
};
public void onCreate() {
if(start){
registerReceiver(br, new android.content.IntentFilter(启动信息.ACTION_CONFIGURATION_CHANGED));
start = false;
}
ShowNotification();
}
public void onDestroy() {
unregisterReceiver(br);
stopForeground(true);
start = true;
}
public void ShowNotification() {
android.app.Notification.Builder n = new android.app.Notification.Builder(this)
.setContentTitle("悬浮窗_屏幕方向监听")
.setContentText("用于监听屏幕方向以适应横竖屏");
if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O){
((android.app.NotificationManager)getSystemService(NOTIFICATION_SERVICE))
.createNotificationChannel(new android.app.NotificationChannel("FWV","悬浮窗_屏幕方向监听服务", android.app.NotificationManager.IMPORTANCE_DEFAULT));
n.setChannelId("FWV");
}
startForeground(9527,n.build());
}

}
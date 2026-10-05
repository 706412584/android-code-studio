package ticode.android;

import android.content.*;
import android.app.Activity;
import android.view.*;
import android.widget.*;
import java.util.*;
import android.util.*;
import android.graphics.*;

import ticode.jvm.JCollection;

public class ScreenOrientationService extends Service2 {

public static void 启动服务(AndroidEnv 环境) {
startService(环境, ScreenOrientationService.class);
}
public static void 停止服务(AndroidEnv 环境) {
stopService(环境, ScreenOrientationService.class);
}

public static boolean start = true;
public static void startService(AndroidEnv c, Class<?> cla){c.startService(new Intent2(c, cla));}
public static void stopService(AndroidEnv c, Class<?> cla){c.stopService(new Intent2(c, cla));}
public android.content.BroadcastReceiver br = new android.content.BroadcastReceiver() {
public void onReceive(AndroidEnv context, Intent2 intent) {
if (intent.getAction().equals(Intent2.ACTION_CONFIGURATION_CHANGED)) {
int w = getResources().getDisplayMetrics().widthPixels;
int h = getResources().getDisplayMetrics().heightPixels;
for (FloatingWindow fw : FloatingWindow.FMap.values()) {
if(fw.isAutoScreenOrientation()) fw.screenOrientationChange(w, h);
}
}
}
};
public void onCreate() {
if(start){
registerReceiver(br, new android.content.IntentFilter(Intent2.ACTION_CONFIGURATION_CHANGED));
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
package ticode.zh.android;

import android.app.Application;
import java.lang.reflect.Method;
import android.view.*;
import android.app.*;
import android.content.pm.*;
import android.os.*;
import android.content.*;
import android.graphics.drawable.*;

public class 安卓应用 extends 应用 {
private static Application application;

@Override protected void attachBaseContext(android.content.Context base) {
super.attachBaseContext(base);
onPreInit();
即将创建();
}

@Override public void onCreate() {
super.onCreate();
onInit();
创建完毕();
}

public void onPreInit() {
}

public void onInit() {
}

public static 应用 取安卓应用() {
if (application == null) {
try {
Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
Method currentApplicationMethod = activityThreadClass.getDeclaredMethod("currentApplication");
currentApplicationMethod.setAccessible(true);
application = (Application) currentApplicationMethod.invoke(null);
} catch (Exception e) {
}
}
return application;
}

public void 即将创建() {
}

public void 创建完毕() {
}
}
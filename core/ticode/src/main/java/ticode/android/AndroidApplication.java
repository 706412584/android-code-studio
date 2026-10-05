package ticode.android;

import android.app.Application;
import java.lang.reflect.Method;
import android.content.Context;
import android.net.Uri;
import android.widget.Toast;
import android.provider.MediaStore;
import android.database.Cursor;
import android.provider.DocumentsContract;
import android.os.Environment;
import android.content.ContentUris;
import android.view.*;
import android.app.*;
import android.content.Intent;
import android.os.Build;
import android.content.pm.*;
import android.provider.Settings;
import android.os.StrictMode;
import android.os.*;
import android.content.*;
import android.view.KeyEvent;
import android.content.res.Configuration;
import android.os.IBinder;
import android.content.ComponentName;
import android.graphics.drawable.*;

import ticode.jvm.JFile;
import ticode.jvm.JavaClass;

public class AndroidApplication extends Application2 {
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

public static Application2 取安卓应用() {
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
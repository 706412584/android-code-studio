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
import android.os.*;
import android.content.*;
import android.view.KeyEvent;
import android.content.res.Configuration;
import android.os.IBinder;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.*;
import android.graphics.drawable.*;

import ticode.jvm.JFile;
import ticode.jvm.JavaClass;

public class Service2 extends AndroidService {

android.os.IBinder 中间件;

@Override
public void onCreate() {
super.onCreate();
创建完毕();
}

@Override
public int onStartCommand(Intent intent, int flags, int startId) {
int state = super.onStartCommand(intent, flags, startId);
被启动(intent);
return state;
}

@Override
public android.os.IBinder onBind(Intent intent) {
被绑定(intent);
return 取通信中间件();
}

@Override
public void onDestroy() {
被销毁();
super.onDestroy();
}


public void 置通信中间件(Handler2 处理器) {
中间件 = Messenger2.新建对象(处理器).取通信中间件();
}


public android.os.IBinder 取通信中间件() {
return 中间件;
}

public void 创建完毕() {
}

public void 被启动(Intent2 数据) {
}

public void 被绑定(Intent2 数据) {
}

public void 被销毁() {
}
}
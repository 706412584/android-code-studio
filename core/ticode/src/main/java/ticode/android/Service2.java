package ticode.android;

import android.content.Intent;
import android.view.*;
import android.app.*;
import android.content.pm.*;
import android.os.*;
import android.content.*;
import android.graphics.drawable.*;

public class Service2 extends AndroidService {

public static android.os.IBinder 中间件;

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
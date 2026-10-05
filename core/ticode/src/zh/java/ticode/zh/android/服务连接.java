package ticode.zh.android;

import android.os.IBinder;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.view.*;
import android.app.*;
import android.content.pm.*;
import android.os.*;
import android.content.*;
import android.graphics.drawable.*;

public class 服务连接 implements ServiceConnection {
@Override
public void onServiceConnected(ComponentName name, IBinder service) {
服务已连接(name,service);
}

@Override
public void onServiceDisconnected(ComponentName name) {
服务已断开连接(name);
}

public void 服务已连接(android.content.ComponentName 名称, android.os.IBinder 中间件) {
}

public void 服务已断开连接(android.content.ComponentName 名称) {
}

}
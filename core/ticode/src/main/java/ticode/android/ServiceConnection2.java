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
import android.content.ServiceConnection;
import android.graphics.drawable.*;

import ticode.jvm.JFile;
import ticode.jvm.JavaClass;

public class ServiceConnection2 {
@Override
public void onServiceConnected(ComponentName name, IBinder service) {
服务已连接(name,service);
}

@Override
public void onServiceDisconnected(ComponentName name) {
服务已断开连接(name);
}

public void 服务已连接(ComponentName2 名称, android.os.IBinder 中间件) {
}

public void 服务已断开连接(ComponentName2 名称) {
}

}
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

public class BroadcastReceiver3 extends BroadcastReceiver2 {
@Override
public void onReceive(Context context, Intent intent) {
接收到广播(context, intent);
}

public void 接收到广播(AndroidEnv 环境, Intent2 数据) {
}
}
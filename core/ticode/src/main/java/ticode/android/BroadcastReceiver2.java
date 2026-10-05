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
import android.content.Intent;
import android.os.IBinder;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.*;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.drawable.*;

import ticode.jvm.JFile;
import ticode.jvm.JavaClass;

public class BroadcastReceiver2 extends android.content.BroadcastReceiver {
public int 结果码() {
return this.getResultCode();
}

public void 结果码(int 结果码) {
this.setResultCode(结果码);
}

public String 结果内容() {
return this.getResultData();
}

public void 结果内容(String 内容) {
this.setResultData(内容);
}
}
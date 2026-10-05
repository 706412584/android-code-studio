package ticode.zh.android;

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

import ticode.zh.jvm.Java类;
import ticode.zh.jvm.文件;

public class 内容数据包 extends android.content.ContentValues {

public void 置入(String 键名, Object 值) {
if (值 instanceof Integer) {
this.put(键名, (Integer)值);
} else if (值 instanceof Float) {
this.put(键名, (Float)值);
} else if (值 instanceof Short) {
this.put(键名, (Short)值);
} else if (值 instanceof Double) {
this.put(键名, (Double)值);
} else if (值 instanceof Long) {
this.put(键名, (Long)值);
} else if (值 instanceof byte[]) {
this.put(键名, (byte[])值);
} else if (值 instanceof Byte) {
this.put(键名, (Byte)值);
} else if (值 instanceof Boolean) {
this.put(键名, (Boolean)值);
} else if (值 instanceof String) {
this.put(键名, (String)值);
}
}

public Object 取值(String 键名) {
return this.get(键名);
}

public int 取整数(String 键名) {
return this.getAsInteger(键名);
}

public float 取数值(String 键名) {
return this.getAsFloat(键名);
}

public double 取小数(String 键名) {
return this.getAsDouble(键名);
}

public long 取长整数(String 键名) {
return this.getAsLong(键名);
}

public byte[] 取字节集(String 键名) {
return this.getAsByteArray(键名);
}

public byte 取字节(String 键名) {
return this.getAsByte(键名);
}

public boolean 取逻辑型(String 键名) {
return this.getAsBoolean(键名);
}

public String 取文本(String 键名) {
return this.getAsString(键名);
}

}
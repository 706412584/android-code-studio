package ticode.zh.android;

import android.media.*;
import android.os.*;
import java.util.*;
import android.os.Vibrator;
import android.content.Context;

public class 震动操作 extends android.widget.Toast {
Vibrator vibrator;
public 震动操作(Context context) {
super(context);
}

public void 开始震动(long 振动时间) {
vibrator = (Vibrator)取安卓环境().getSystemService(取安卓环境().VIBRATOR_SERVICE);
vibrator.vibrate(振动时间);
}

public static void 震动(安卓环境 安卓环境, long 振动时间) {
Vibrator vibrator = (Vibrator)安卓环境.getSystemService(安卓环境.VIBRATOR_SERVICE);
vibrator.vibrate(振动时间);
}

public void 关闭震动() {
vibrator.cancel();
}
}
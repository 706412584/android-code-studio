package ticode.android;

import android.media.*;
import android.os.*;
import java.util.*;
import android.os.Vibrator;
import android.content.Context;

public class VibratorOps extends WindowComponent {
Vibrator vibrator;
public VibratorOps(Context context) {
super(context);
}

public void 开始震动(long 振动时间) {
vibrator = (Vibrator)取安卓环境().getSystemService(取安卓环境().VIBRATOR_SERVICE);
vibrator.vibrate(振动时间);
}

public static void 震动(AndroidEnv 安卓环境, long 振动时间) {
Vibrator vibrator = (Vibrator)AndroidEnv.getSystemService(AndroidEnv.VIBRATOR_SERVICE);
vibrator.vibrate(振动时间);
}

public void 关闭震动() {
vibrator.cancel();
}
}
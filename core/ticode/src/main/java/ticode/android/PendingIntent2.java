package ticode.android;

import android.content.pm.*;

public class PendingIntent2 extends android.app.PendingIntent {

public static final int 取消当前标志 = 0x10000000;;
public static final int 更新当前标志 = 0x8000000;;

public static PendingIntent2 取安卓窗口(AndroidEnv 环境, int 请求码, Intent2 信息, int 标志) {
return PendingIntent2.getActivity(环境,请求码,信息,标志);
}

public static PendingIntent2 取前台服务(AndroidEnv 环境, int 请求码, Intent2 信息, int 标志) {
return PendingIntent2.getForegroundService(环境,请求码,信息,标志);
}

public static PendingIntent2 取服务(AndroidEnv 环境, int 请求码, Intent2 信息, int 标志) {
return PendingIntent2.getService(环境,请求码,信息,标志);
}

public static PendingIntent2 取广播(AndroidEnv 环境, int 请求码, Intent2 信息, int 标志) {
return PendingIntent2.getBroadcast(环境,请求码,信息,标志);
}

}
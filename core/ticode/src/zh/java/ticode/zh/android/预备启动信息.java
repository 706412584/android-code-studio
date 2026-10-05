package ticode.zh.android;

import android.os.Environment;

import ticode.zh.jvm.Java类;
import ticode.zh.jvm.文件;

public class 预备启动信息 {

public static final int 取消当前标志 = 0x10000000;;
public static final int 更新当前标志 = 0x8000000;;

public static android.app.PendingIntent 取安卓窗口(安卓环境 环境, int 请求码, 启动信息 信息, int 标志) {
return android.app.PendingIntent.getActivity(环境,请求码,信息,标志);
}

public static android.app.PendingIntent 取前台服务(安卓环境 环境, int 请求码, 启动信息 信息, int 标志) {
return android.app.PendingIntent.getForegroundService(环境,请求码,信息,标志);
}

public static android.app.PendingIntent 取服务(安卓环境 环境, int 请求码, 启动信息 信息, int 标志) {
return android.app.PendingIntent.getService(环境,请求码,信息,标志);
}

public static android.app.PendingIntent 取广播(安卓环境 环境, int 请求码, 启动信息 信息, int 标志) {
return android.app.PendingIntent.getBroadcast(环境,请求码,信息,标志);
}

}
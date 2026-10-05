package ticode.zh.android;

import android.content.pm.*;

public class 预备启动信息 {

public static final int 取消当前标志 = 0x10000000;;
public static final int 更新当前标志 = 0x8000000;;

public static android.app.PendingIntent 取安卓窗口(android.content.Context 环境, int 请求码, android.content.Intent 信息, int 标志) {
return 预备启动信息.getActivity(环境,请求码,信息,标志);
}

public static android.app.PendingIntent 取前台服务(android.content.Context 环境, int 请求码, android.content.Intent 信息, int 标志) {
return 预备启动信息.getForegroundService(环境,请求码,信息,标志);
}

public static android.app.PendingIntent 取服务(android.content.Context 环境, int 请求码, android.content.Intent 信息, int 标志) {
return 预备启动信息.getService(环境,请求码,信息,标志);
}

public static android.app.PendingIntent 取广播(android.content.Context 环境, int 请求码, android.content.Intent 信息, int 标志) {
return 预备启动信息.getBroadcast(环境,请求码,信息,标志);
}

}
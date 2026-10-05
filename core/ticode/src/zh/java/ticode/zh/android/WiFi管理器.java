package ticode.zh.android;

import android.net.wifi.WifiManager;

public class WiFi管理器 extends 窗口组件 {

private WifiManager glq;
private android.content.Context context;
public WiFi管理器(android.content.Context context) {
super(context);
this.glq = (WifiManager)context.getSystemService(android.content.Context.WIFI_SERVICE);
this.context=context;
}

public WiFi扫描结果[] 取WiFi扫描结果() {
return glq.getScanResults().toArray(new WiFi扫描结果[0]);
}

public WiFi信息 取当前连接信息() {
return glq.getConnectionInfo();
}

public WiFi管理器 重新获取系统服务() {
this.glq = (WifiManager)context.getSystemService(android.content.Context.WIFI_SERVICE);
return (this);
}
}
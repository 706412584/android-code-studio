package ticode.android;

import java.util.ArrayList;
import android.net.wifi.WifiManager;

public class WifiManager2 extends WindowComponent {

private WifiManager glq;
private android.content.Context context;
public WifiManager2(android.content.Context context) {
super(context);
this.glq = (WifiManager)context.getSystemService(android.content.Context.WIFI_SERVICE);
this.context=context;
}

public WifiScanResult[] 取WiFi扫描结果() {
return glq.getScanResults().toArray(new WifiScanResult[0]);
}

public android.net.wifi.WifiInfo 取当前连接信息() {
return glq.getConnectionInfo();
}

public WifiManager2 重新获取系统服务() {
this.glq = (WifiManager)context.getSystemService(android.content.Context.WIFI_SERVICE);
return (this);
}
}
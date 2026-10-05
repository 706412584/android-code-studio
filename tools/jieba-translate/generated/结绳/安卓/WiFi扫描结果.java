package 结绳.安卓;

import java.util.ArrayList;
import android.net.wifi.WifiManager;

public class WiFi扫描结果 {
public static final int 带宽160MHZ = 3;
public static final int 带宽20MHZ = 0;
public static final int 带宽40MHZ = 1;
public static final int 带宽80MHZ = 2;



//勿用会崩溃
public int 取WiFi标准() {
return this.getWifiStandard();
}

public String SSID() {
return this.SSID;
}

public String BSSID() {
return this.BSSID;
}

public long 时间戳() {
return this.timestamp;
}

public String 能力() {
return this.capabilities;
}

public int 中心频射0() {
return this.centerFreq0;
}

public int 中心频射1() {
return this.centerFreq1;
}

public int 通道宽度() {
return this.channelWidth;
}

public int 频率() {
return this.frequency;
}

public int 信号强度() {
return this.level;
}





}

















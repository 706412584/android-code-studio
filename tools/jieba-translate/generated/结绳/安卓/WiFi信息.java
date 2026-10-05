package 结绳.安卓;

import java.util.ArrayList;
import android.net.wifi.WifiManager;

public class WiFi信息 {
public String SSID() {
return this.getSSID();
}

public String BSSID() {
return this.getBSSID();
}

public int 接收信号强度() {
return this.getRssi();
}

//勿用会崩溃
public int WiFi标准() {
return this.getWifiStandard();
}

public int 连接速率() {
return this.getLinkSpeed();
}

public int 发送速率() {
return this.getTxLinkSpeedMbps();
}
//勿用会崩溃
public int 最大发速率() {
return this.getMaxSupportedTxLinkSpeedMbps();
}

public int 接收速率() {
return this.getRxLinkSpeedMbps();
}
//勿用会崩溃
public int 最大接收速率() {
return this.getMaxSupportedRxLinkSpeedMbps();
}

public int 频率() {
return this.getFrequency();
}

public String Mac地址() {
return this.getMacAddress();
}

public String 完全限定域名() {
return this.getPasspointFqdn();
}

public String 程序友好名称() {
return this.getPasspointProviderFriendlyName();
}

public int 网络标识() {
return this.getNetworkId();
}





}


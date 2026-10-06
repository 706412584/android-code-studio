package ticode.zh.android;


public class WiFi信息 {
private android.net.wifi.WifiInfo 内部对象;

public WiFi信息(android.net.wifi.WifiInfo 信息对象) {
this.内部对象 = 信息对象;
}

public android.net.wifi.WifiInfo 取内部对象() {
return this.内部对象;
}

public String SSID() {
return this.内部对象.getSSID();
}

public String BSSID() {
return this.内部对象.getBSSID();
}

public int 接收信号强度() {
return this.内部对象.getRssi();
}

//勿用会崩溃
public int WiFi标准() {
return this.内部对象.getWifiStandard();
}

public int 连接速率() {
return this.内部对象.getLinkSpeed();
}

public int 发送速率() {
return this.内部对象.getTxLinkSpeedMbps();
}
//勿用会崩溃
public int 最大发速率() {
return this.内部对象.getMaxSupportedTxLinkSpeedMbps();
}

public int 接收速率() {
return this.内部对象.getRxLinkSpeedMbps();
}
//勿用会崩溃
public int 最大接收速率() {
return this.内部对象.getMaxSupportedRxLinkSpeedMbps();
}

public int 频率() {
return this.内部对象.getFrequency();
}

public String Mac地址() {
return this.内部对象.getMacAddress();
}

public String 完全限定域名() {
return this.内部对象.getPasspointFqdn();
}

public String 程序友好名称() {
return this.内部对象.getPasspointProviderFriendlyName();
}

public int 网络标识() {
return this.内部对象.getNetworkId();
}

}

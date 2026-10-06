package ticode.zh.android;

import java.util.concurrent.ExecutorService;
import java.net.InetSocketAddress;
import android.os.Looper;
import android.os.Handler;
import java.io.*;
import java.net.*;
import java.util.*;
import java.math.*;
import java.security.*;
import java.security.cert.*;
import javax.net.ssl.*;
import java.util.concurrent.*;
import android.os.*;
import java.nio.*;
import java.nio.channels.*;

import static ticode.zh.android.流程处理.提交到新线程运行;
import static ticode.zh.android.流程处理.等待新线程执行完毕;
import static ticode.zh.android.流程处理.结束提交到新线程;

public class 局域网工具 {
public static boolean 是否连通;
public static boolean 是否开放;

public static boolean 是否开启网络代理() {
String proxyHost = System.getProperty("http.proxyHost");
String proxyPort = System.getProperty("http.proxyPort");
if (proxyHost != null || proxyPort != null) {
return true;
}
try {
for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
if (network.getName().equals("tun0")) {
return true;
}
}
} catch (Exception e) {
}
return false;
}




public static String 获取本机IP地址() {
try {
for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
if (network.isLoopback()) continue;
for (InetAddress address : Collections.list(network.getInetAddresses())) {
if (address instanceof Inet4Address) return address.getHostAddress();
}
}
} catch (Exception e) {
}
return "127.0.0.1";
}




public static boolean IP地址是否连通(String IP地址, int 超时) {
提交到新线程运行();
是否连通 = IP地址是否连通_同步(IP地址, 超时);
结束提交到新线程();
等待新线程执行完毕();
return 是否连通;
}




public static boolean IP地址是否连通_同步(String IP地址, int 超时) {
try {
return InetAddress.getByName(IP地址).isReachable(超时);
} catch (Exception e) {
}
return false;
}




public static boolean 端口是否开放(int 端口, String IP地址, int 超时) {
提交到新线程运行();
是否开放 = 端口是否开放_同步(端口, IP地址, 超时);
结束提交到新线程();
等待新线程执行完毕();
return 是否开放;
}




public static boolean 端口是否开放_同步(int 端口, String IP地址, int 超时) {
Socket socket = new Socket();
try {
socket.connect(new InetSocketAddress(IP地址, 端口), 超时);
return true;
} catch (Exception e) {
}
try {
socket.close();
} catch (Exception e) {
}
return false;
}




public void 扫描所有连通IP地址(int 超时) {
Handler handler = new Handler(Looper.getMainLooper());
ExecutorService threadPool = Executors.newCachedThreadPool();
try {
for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
if (network.isLoopback()) continue;
for (InetAddress address : Collections.list(network.getInetAddresses())) {
if (address instanceof Inet4Address) {
byte[] ip = address.getAddress();
for (int i = 0; i <= 255; i++) {
ip[3] = (byte)i;
InetAddress ipAddress = InetAddress.getByAddress(ip);
threadPool.execute(new Runnable() {
@Override
public void run() {
try {
if (ipAddress.isReachable(超时)) {
handler.post(new Runnable() {
@Override
public void run() {
扫描到连通IP地址(ipAddress.getHostAddress());
}
});
}
} catch (Exception e) {
}
}
});
}
}
}
}
} catch (Exception e) {
}
threadPool.shutdown();
}




public void 扫描所有开放端口(String IP地址, int 超时, int 线程数) {
Handler handler = new Handler(Looper.getMainLooper());
ExecutorService threadPool = Executors.newFixedThreadPool(线程数);
for (int i = 1; i <= 65535; i++) {
int port = i;
threadPool.execute(new Runnable() {
@Override
public void run() {
Socket socket = new Socket();
try {
socket.connect(new InetSocketAddress(IP地址, port), 超时);
handler.post(new Runnable() {
@Override
public void run() {
扫描到开放端口(port);
}
});
} catch (Exception e) {
}
try {
socket.close();
} catch (Exception e) {
}
}
});
}
threadPool.shutdown();
}

public void 扫描到连通IP地址(String IP地址) { } // 事件

public void 扫描到开放端口(int 端口) { } // 事件
}
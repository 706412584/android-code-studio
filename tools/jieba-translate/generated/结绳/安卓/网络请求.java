package 结绳.安卓;

import java.io.*;
import java.net.*;
import java.util.*;
import java.math.*;
import java.security.*;
import java.security.cert.*;
import javax.net.ssl.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.HttpURLConnection;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.concurrent.ExecutorService;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import java.net.HttpURLConnection;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import android.os.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import android.os.Looper;
import android.os.Handler;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import android.os.*;

public class 网络请求 {
public static ExecutorService cachedThreadPool;

static {
try {
HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
public boolean verify(String hostname, SSLSession session) {
return true;
}
});
SSLContext context = SSLContext.getInstance("TLS");
context.init(null, new X509TrustManager[] { new X509TrustManager() {
public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
}

public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
}

public X509Certificate[] getAcceptedIssuers() {
return new X509Certificate[0];
}
} }, new SecureRandom());
HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
} catch (Exception e) {
}
}

int 全局网络请求超时 = 6000;
boolean 全局网络请求GZIP压缩 = false;
java.util.Map<String,String> 全局网络请求头;
Object 全局POST提交数据 = null;

public int 取网络请求超时() {
return 全局网络请求超时;
}

public void 置网络请求超时(int 超时) {
全局网络请求超时 = 超时;
}

public boolean 取网络请求GZIP压缩() {
return 全局网络请求GZIP压缩;
}

public void 置网络请求GZIP压缩(boolean GZIP压缩) {
全局网络请求GZIP压缩 = GZIP压缩;
}

public void 添加网络请求头(String 名称, String 值) {
全局网络请求头.添加项目(名称, 值);
}

public void 移除网络请求头(String 名称) {
全局网络请求头.删除项目(名称);
}

public void 清除网络请求头() {
全局网络请求头.清空();
}

public 网络请求结果 GET同步请求(String 网址, String Cookie, String 编码) {
网络请求结果 结果;
Runnable runnable = new Runnable() {
@Override
public void run() {
try {
byte[] bytes = httpRequest(结果, 网址, Cookie, 编码, "GET");
结果.text = new String(bytes, 编码);
结果.bytes = bytes;
} catch (Exception e) {
结果.resCode = -1;
结果.text = e.toString();
}
}
};
if (是否处于主线程()) {
提交到新线程运行();
runnable.run();
结束提交到新线程();
等待新线程执行完毕();
} else {
runnable.run();
}
return 结果;
}

public void GET异步请求(String 网址, String Cookie, String 编码) {
String httpGetUrl = 网址;
String httpGetCookie = Cookie;
String httpGetCharset = 编码;
String httpRequestMethod = "GET";
网络请求结果 httpGetResult = new 网络请求结果();
Runnable httpGetCallback = new Runnable() {
@Override
public void run() {
}

public void POST提交数据(Object 提交数据) {
全局POST提交数据 = 提交数据;
}

public 网络请求结果 POST同步请求(String 网址, String Cookie, String 编码) {
网络请求结果 结果;
Runnable runnable = new Runnable() {
@Override
public void run() {
try {
byte[] bytes = httpRequest(结果, 网址, Cookie, 编码, "POST");
结果.text = new String(bytes, 编码);
结果.bytes = bytes;
} catch (Exception e) {
结果.resCode = -1;
结果.text = e.toString();
}
}
};
if (是否处于主线程()) {
提交到新线程运行();
runnable.run();
结束提交到新线程();
等待新线程执行完毕();
} else {
runnable.run();
}
return 结果;
}

public void POST异步请求(String 网址, String Cookie, String 编码) {
String httpGetUrl = 网址;
String httpGetCookie = Cookie;
String httpGetCharset = 编码;
String httpRequestMethod = "POST";
网络请求结果 httpGetResult = new 网络请求结果();
Runnable httpGetCallback = new Runnable() {
@Override
public void run() {
}

public 网络请求结果 取网络请求结果() {
return httpGetResult;
}

public void 结束网络请求() {
}
};
if (网络请求.cachedThreadPool == null) {
网络请求.cachedThreadPool = java.util.concurrent.Executors.newCachedThreadPool();
}
网络请求.cachedThreadPool.execute(new Runnable() {
@Override
public void run() {
try {
httpGetResult.bytes = 网络请求.httpRequest(httpGetResult, httpGetUrl, httpGetCookie, httpGetCharset, httpRequestMethod);
httpGetResult.text = new String(httpGetResult.bytes, httpGetCharset);
} catch (Exception e) {
httpGetResult.resCode = -1;
httpGetResult.text = e.toString();
}
if (Thread.currentThread() == android.os.Looper.getMainLooper().getThread()) {
httpGetCallback.run();
} else {
new android.os.Handler(android.os.Looper.getMainLooper()).post(httpGetCallback);
}
}
});
}

public static byte[] httpRequest(网络请求结果 result, String url, String cookie, String charset, String method) throws IOException {
if (!url.startsWith("http://") && !url.startsWith("https://")) {
url = "http://" + url;
}
HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
result.con = con;
con.setConnectTimeout(全局网络请求超时);
con.setReadTimeout(全局网络请求超时);
con.setFollowRedirects(true);
con.setRequestMethod(method);
con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
con.setRequestProperty("Accept-Charset", charset);
if (全局网络请求GZIP压缩) {
con.setRequestProperty("Accept-Encoding", "gzip");
}
if (cookie != null && !cookie.isEmpty()) {
con.setRequestProperty("Cookie", cookie);
}
if (全局网络请求头 != null && !全局网络请求头.isEmpty()) {
for (Map.Entry<String, String> entry : 全局网络请求头.entrySet()) {
con.setRequestProperty(entry.getKey(), entry.getValue());
}
}
Object reqData = 全局POST提交数据;
if (method.equals("POST") && reqData != null) {
byte[] data = null;
if (reqData instanceof String) {
data = ((String) reqData).getBytes(charset);
} else if (reqData instanceof Byte) {
data = new byte[]{((byte) reqData)};
} else if (reqData instanceof byte[]) {
data = ((byte[]) reqData);
}
if (reqData != null) {
con.setRequestProperty("Content-length", data.length + "");
OutputStream os = con.getOutputStream();
os.write(data);
os.flush();
}
}
con.connect();
int resCode = con.getResponseCode();
result.resCode = resCode;
//3xx状态码重定向
if (resCode >= 300 && resCode < 400) {
String location = con.getHeaderField("Location");
con.disconnect();
if (location != null && !location.isEmpty()) {
return httpRequest(result, location, cookie, charset, method);
}
}
InputStream is = con.getInputStream();
if (全局网络请求GZIP压缩) {
is = new GZIPInputStream(is);
}
byte[] bytes = null;
try {
bytes = readStream(is);
} catch (IOException e) {
} finally {
if (is != null) {
try {
is.close();
} catch (Exception e) {
}
}
}
return bytes;
}

public static byte[] readStream(InputStream is) throws IOException {
byte[] buf = new byte[1024];
int len;
ByteArrayOutputStream baos = new ByteArrayOutputStream();
while ((len = is.read(buf)) != -1) {
baos.write(buf, 0, len);
}
baos.close();
is.close();
return baos.toByteArray();
}
}


public class 网络请求结果 {
public int resCode;
public String text;
public byte[] bytes;
public HttpURLConnection con;

public String toString() {
return text;
}

public byte[] toByteArray() {
return bytes;
}

public int 取状态码() {
try {
return con.getResponseCode();
} catch (Exception e) {
return -1;
}
}

public boolean 是否请求成功() {
return resCode >= 200 && resCode < 300;
}

public String 取响应头(String 键名) {
return con.getHeaderField(键名);
}

public String 取Cookie() {
return con.getHeaderField("Set-Cookie");
}

public String 到文本() {
return text;
}

public byte[] 到字节集() {
return bytes;
}

public JSON对象 到JSON对象() {
JSON对象 json = 到文本();
return json;
}

public JSON数组 到JSON数组() {
JSON数组 json = 到文本();
return json;
}
}


public class 局域网工具 {
boolean 是否连通;
boolean 是否开放;

public boolean 是否开启网络代理() {
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




public String 获取本机IP地址() {
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




public boolean IP地址是否连通(String IP地址, int 超时) {
提交到新线程运行();
是否连通 = IP地址是否连通_同步(IP地址, 超时);
结束提交到新线程();
等待新线程执行完毕();
return 是否连通;
}




public boolean IP地址是否连通_同步(String IP地址, int 超时) {
try {
return InetAddress.getByName(IP地址).isReachable(超时);
} catch (Exception e) {
}
return false;
}




public boolean 端口是否开放(int 端口, String IP地址, int 超时) {
提交到新线程运行();
是否开放 = 端口是否开放_同步(端口, IP地址, 超时);
结束提交到新线程();
等待新线程执行完毕();
return 是否开放;
}




public boolean 端口是否开放_同步(int 端口, String IP地址, int 超时) {
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

public void 扫描到连通IP地址(String IP地址) { return null; } // 事件

public void 扫描到开放端口(int 端口) { return null; } // 事件
}


public class 数据报 {
private DatagramChannel channel;
private boolean isClose = true;

public int 监听端口() {
if (channel != null) {
try {
return ((InetSocketAddress) channel.getLocalAddress()).getPort();
} catch (Exception e) {
return -1;
}
}
return -1;
}

public boolean 是否打开() {
return !isClose;
}

public 逻辑型类 是否关闭() {
return isClose;
}

public void 开始监听(int 端口) {
开始监听_内部(端口);
}

public void 开始监听_内部(int 端口, int 缓冲区大小) {
Handler handler = new Handler(Looper.getMainLooper());
try {
channel = DatagramChannel.open();
channel.bind(new InetSocketAddress(端口));
isClose = false;
handler.post(new Runnable() {
@Override
public void run() {
监听成功();
}
});
try {
ByteBuffer buffer = ByteBuffer.allocate(缓冲区大小);
while (true) {
InetSocketAddress address = (InetSocketAddress) channel.receive(buffer);
String ip = address.getHostString();
int port = address.getPort();
buffer.flip();
String text = new String(buffer.array(), 0, buffer.limit());
byte[] bytes = new byte[buffer.limit()];
buffer.get(bytes);
handler.post(new Runnable() {
@Override
public void run() {
收到文本(ip, port, text);
收到字节集(ip, port, bytes);
}
});
buffer.clear();
}
} catch (Exception e) {
handler.post(new Runnable() {
@Override
public void run() {
监听关闭();
}
});
}
} catch (Exception e) {
handler.post(new Runnable() {
@Override
public void run() {
发生异常(e);
}
});
}
isClose = true;
}

public void 发送文本(String 地址, int 端口, String 内容) {
发送文本_同步(地址, 端口, 内容);
}

public void 发送字节集(String 地址, int 端口, byte[] 字节集) {
发送字节集_同步(地址, 端口, 字节集);
}

public void 发送文本_同步(String 地址, int 端口, String 内容) {
发送字节集_同步(地址, 端口, 内容.到字节集());
}

public void 发送字节集_同步(String 地址, int 端口, byte[] 字节集) {
if (channel != null && !isClose) {
try {
channel.send(ByteBuffer.wrap(字节集), new InetSocketAddress(地址, 端口));
} catch (Exception e) {
}
}
}

public void 关闭监听() {
if (channel != null && !isClose) {
try {
channel.close();
} catch (Exception e) {
}
}
}

public void 监听成功() { return null; } // 事件

public void 收到文本(String 地址, int 端口, String 内容) { return null; } // 事件

public void 收到字节集(String 地址, int 端口, byte[] 字节集) { return null; } // 事件

public void 监听关闭() { return null; } // 事件

public void 发生异常(异常 异常原因) { return null; } // 事件
}


public class 套接字 {
private boolean isClose;
private SocketChannel client;
private Handler handler = new Handler(Looper.getMainLooper());

public int 监听端口() {
if (client == null) return -1;
try {
return ((InetSocketAddress)client.getLocalAddress()).getPort();
} catch (Exception e) {
return -1;
}
}

public void 连接(String 地址, int 端口) {
连接_内部(地址, 端口);
}

public void 连接_内部(String 地址, int 端口) {
isClose = true;
Selector selector = null;
try {
client = SocketChannel.open(new InetSocketAddress(地址, 端口));
selector = Selector.open();
client.configureBlocking(false).register(selector, SelectionKey.OP_READ);
ByteBuffer buffer = ByteBuffer.allocate(1024);
ByteArrayOutputStream bytesBuffer = new ByteArrayOutputStream();
handler.post(new Runnable() {
@Override
public void run() {
连接成功();
}
});
isClose = false;
while (!isClose) {
if (selector.selectNow() == 0) continue;
Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
while (keys.hasNext()) {
SocketChannel client = (SocketChannel)keys.next().channel();
while (true) {
int length = client.read(buffer);
if (length == -1) {
isClose = true;
break;
} else if (length == 0) {
bytesBuffer.flush();
String text = bytesBuffer.toString();
byte[] bytes = bytesBuffer.toByteArray();
handler.post(new Runnable() {
@Override
public void run() {
收到数据(text, bytes);
}
});
bytesBuffer.reset();
break;
} else {
bytesBuffer.write(buffer.array(), 0, length);
buffer.clear();
}
}
keys.remove();
}
}
} catch (Exception e) {
handler.post(new Runnable() {
@Override
public void run() {
连接异常(e);
}
});
} finally {
if (client != null) {
try {
client.close();
selector.close();
} catch (Exception e) {
}
}
handler.post(new Runnable() {
@Override
public void run() {
连接关闭();
}
});
}
}

public void 关闭连接() {
isClose = true;
}

public void 发送文本(String 文本) {
发送文本_同步(文本);
}

public void 发送字节集(byte[] 字节集) {
发送字节集_同步(字节集);
}

public boolean 发送文本_同步(String 文本) {
return 发送字节集_同步(文本.到字节集());
}

public boolean 发送字节集_同步(byte[] 字节集) {
if (client == null) return false;
try {
return client.write(ByteBuffer.wrap(字节集)) == 字节集.length;
} catch (Exception e) {
return false;
}
}

public void 连接成功() { return null; } // 事件

public void 连接异常(异常 异常) { return null; } // 事件

public void 收到数据(String 文本, byte[] 字节集) { return null; } // 事件

public void 连接关闭() { return null; } // 事件
}


public class 套接字服务端 {
private boolean isClose;
private int increment;
private ServerSocketChannel server;
private Map<SocketChannel, Integer> ids;
private Map<Integer, SocketChannel> clients;
private Handler handler = new Handler(Looper.getMainLooper());

public int 监听端口() {
if (server == null) return -1;
try {
return ((InetSocketAddress)server.getLocalAddress()).getPort();
} catch (Exception e) {
return -1;
}
}

public int 总连接数() {
return clients==null?-1:clients.size();
}

public void 监听(int 端口) {
监听_内部(端口);
}

public void 监听_内部(int 端口) {
isClose = true;
Selector selector = null;
try {
server = ServerSocketChannel.open();
server.bind(new InetSocketAddress(端口));
selector = Selector.open();
server.configureBlocking(false).register(selector, SelectionKey.OP_ACCEPT);
increment = 0;
if (ids == null) {
ids = new ConcurrentHashMap<>();
clients = new ConcurrentHashMap<>();
}
ByteBuffer buffer = ByteBuffer.allocate(1024);
ByteArrayOutputStream bytesBuffer = new ByteArrayOutputStream();
isClose = false;
handler.post(new Runnable() {
@Override
public void run() {
监听成功();
}
});
while (!isClose) {
if (selector.selectNow() == 0) continue;
Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
while (keys.hasNext()) {
SelectionKey key = keys.next();
if (key.isAcceptable()) {
SocketChannel client = server.accept();
client.configureBlocking(false).register(selector, SelectionKey.OP_READ);
int id = increment;
clients.put(id, client);
ids.put(client, id);
increment++;
handler.post(new Runnable() {
@Override
public void run() {
有新连接(id);
}
});
} else if (key.isReadable()) {
SocketChannel client = (SocketChannel)key.channel();
if (ids.containsKey(client)) {
int id = ids.get(client);
try {
while (true) {
int length = client.read(buffer);
if (length == -1) {
关闭连接(id);
break;
} else if (length == 0) {
bytesBuffer.flush();
String text = bytesBuffer.toString();
byte[] bytes = bytesBuffer.toByteArray();
handler.post(new Runnable() {
@Override
public void run() {
收到数据(id, text, bytes);
}
});
bytesBuffer.reset();
break;
} else {
bytesBuffer.write(buffer.array(), 0, length);
buffer.clear();
}
}
} catch (Exception e) {
关闭连接(id);
}
}
}
keys.remove();
}
}
} catch (Exception e) {
handler.post(new Runnable() {
@Override
public void run() {
监听异常(e);
}
});
} finally {
try {
server.close();
if (selector != null) {
selector.close();
ids.clear();
clients.clear();
}
} catch (Exception e) {
}
handler.post(new Runnable() {
@Override
public void run() {
监听关闭();
}
});
}
}

public void 关闭监听() {
isClose = true;
}

public int[] 取所有连接() {
return (int[])(clients==null||clients.isEmpty()?new int[0]:clients.keySet().toArray());
}

public String 取客户端地址(int 标识) {
if (clients == null || clients.isEmpty() || !clients.containsKey(标识)) return "";
try {
return ((InetSocketAddress)clients.get(标识).getRemoteAddress()).getHostString();
} catch (Exception e) {
return "";
}
}

public int 取客户端端口(int 标识) {
if (clients == null || clients.isEmpty() || !clients.containsKey(标识)) return -1;
try {
return ((InetSocketAddress)clients.get(标识).getRemoteAddress()).getPort();
} catch (Exception e) {
return -1;
}
}

public void 发送文本(int 标识, String 文本) {
发送文本_同步(标识, 文本);
}

public void 发送字节集(int 标识, byte[] 字节集) {
发送字节集_同步(标识, 字节集);
}

public boolean 发送文本_同步(int 标识, String 文本) {
return 发送字节集_同步(标识, 文本.到字节集());
}

public boolean 发送字节集_同步(int 标识, byte[] 字节集) {
if (clients == null || clients.isEmpty() || !clients.containsKey(标识) || 字节集.length == 0) return false;
try {
return clients.get(标识).write(ByteBuffer.wrap(字节集)) == 字节集.length;
} catch (Exception e) {
return false;
}
}

public void 广播文本(String 文本) {
广播文本_同步(文本);
}

public void 广播字节集(byte[] 字节集) {
广播字节集_同步(字节集);
}

public boolean 广播文本_同步(String 文本) {
return 广播字节集_同步(文本.到字节集());
}

public boolean 广播字节集_同步(byte[] 字节集) {
if (clients == null || clients.isEmpty() || 字节集.length == 0) return false;
try {
boolean isSuccessful = true;
for (SocketChannel client:clients.values()) {
if (client.write(ByteBuffer.wrap(字节集)) != 字节集.length) isSuccessful = false;
}
return isSuccessful;
} catch (Exception e) {
return false;
}
}

public boolean 关闭连接(int 标识) {
if (clients == null || clients.isEmpty() || !clients.containsKey(标识)) return false;
SocketChannel client = clients.get(标识);
try {
client.close();
return true;
} catch (Exception e) {
return false;
} finally {
ids.remove(client);
clients.remove(标识);
handler.post(new Runnable() {
@Override
public void run() {
连接断开(标识);
}
});
}
}

public boolean 关闭所有连接() {
if (clients == null || clients.isEmpty()) return false;
try {
boolean isSuccessful = true;
for (int id:clients.keySet()) {
if (!关闭连接(id)) isSuccessful = false;
}
return isSuccessful;
} catch (Exception e) {
return false;
}
}

public void 监听成功() { return null; } // 事件

public void 监听异常(异常 异常) { return null; } // 事件

public void 有新连接(int 标识) { return null; } // 事件

public void 收到数据(int 标识, String 文本, byte[] 字节集) { return null; } // 事件

public void 连接断开(int 标识) { return null; } // 事件

public void 监听关闭() { return null; } // 事件
}


public class 安卓资源标识符 {
public 安卓资源标识符 赋值_op(String Uri编码内容) {
return 解析(Uri编码内容);
}

public 安卓资源标识符 解析(String Uri编码内容) {
return android.net.Uri.parse(Uri编码内容);
}

public 安卓资源标识符 从文件创建(文件 文件对象) {
return android.net.Uri.fromFile(文件对象);
}

public String 协议名称() {
return this.getScheme();
}

public String 协议内容() {
return this.getSchemeSpecificPart();
}


public String 主机名() {
return this.getAuthority();
}


public String 主机地址() {
return this.getHost();
}


public int 主机端口() {
return this.getPort();
}


public String 用户信息() {
return this.getUserInfo();
}

public String 编码用户信息() {
return this.getEncodedUserInfo();
}

public String 资源路径() {
return this.getPath();
}

public String 片段内容() {
return this.getFragment();
}

public String 查询参数() {
return this.getQuery();
}

public boolean 是否为绝对Uri() {
return this.isAbsolute();
}

public boolean 是否为不透明Uri() {
return this.isOpaque();
}

public boolean 是否为相对Uri() {
return this.isRelative();
}

public boolean 是否为绝对分层Uri() {
return this.isHierarchical();
}

public String 取查询参数值(String 参数名称) {
return this.getQueryParameter(参数名称);
}

public String[] 取所有参数名() {
return (String[])this.getQueryParameterNames().toArray();
}

public java.util.List<String> 取路径片段() {
return (java.util.ArrayList)this.getPathSegments();
}

public String 获取最后路径片段() {
return this.getLastPathSegment();
}

public 安卓资源标识符 规范化方案() {
return this.normalizeScheme();
}

public String 文本到Uri编码(String 编码内容) {
return android.net.Uri.encode(编码内容);
}

public String 文本到Uri解码(String 编码内容) {
return android.net.Uri.decode(编码内容);
}

public int 比较(安卓资源标识符 比较对象) {
return this.compareTo(比较对象);
}

public String 到文本() {
return this.toString();
}


}
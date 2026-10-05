package ticode.android;

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

import ticode.base.BooleanBox;
import ticode.base.JException;
import ticode.base.TextBox;
import ticode.jvm.JFile;
import ticode.jvm.UUID;

public class ServerSocket2 {
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
return 发送字节集_同步(标识, TextBox.到字节集());
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
return 广播字节集_同步(TextBox.到字节集());
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

public void 监听成功() { } // 事件

public void 监听异常(JException 异常) { } // 事件

public void 有新连接(int 标识) { } // 事件

public void 收到数据(int 标识, String 文本, byte[] 字节集) { } // 事件

public void 连接断开(int 标识) { } // 事件

public void 监听关闭() { } // 事件
}
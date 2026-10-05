package ticode.zh.android;

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
import java.util.concurrent.*;
import android.os.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import android.os.Looper;
import android.os.Handler;
import java.nio.*;
import java.nio.channels.*;

import ticode.zh.base.异常;
import ticode.zh.base.文本;
import ticode.zh.base.逻辑型类;
import ticode.zh.jvm.UUID;
import ticode.zh.jvm.文件;

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

public void 连接成功() { } // 事件

public void 连接异常(异常 异常) { } // 事件

public void 收到数据(String 文本, byte[] 字节集) { } // 事件

public void 连接关闭() { } // 事件
}
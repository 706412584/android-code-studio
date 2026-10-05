package ticode.zh.android;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
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

import ticode.zh.base.异常;
import ticode.zh.base.逻辑型类;

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

public Boolean 是否关闭() {
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

public void 监听成功() { } // 事件

public void 收到文本(String 地址, int 端口, String 内容) { } // 事件

public void 收到字节集(String 地址, int 端口, byte[] 字节集) { } // 事件

public void 监听关闭() { } // 事件

public void 发生异常(异常 异常原因) { } // 事件
}
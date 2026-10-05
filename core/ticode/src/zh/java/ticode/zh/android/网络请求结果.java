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

import ticode.zh.base.异常;
import ticode.zh.base.文本;
import ticode.zh.base.逻辑型类;
import ticode.zh.jvm.UUID;
import ticode.zh.jvm.文件;

public class 网络请求结果 {
public int resCode;
public String text;
public byte[] bytes;
public HttpURLConnection con;

@Override
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
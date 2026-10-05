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
import java.util.concurrent.*;
import android.os.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import android.os.Looper;
import android.os.Handler;
import java.nio.*;
import java.nio.channels.*;

import ticode.base.BooleanBox;
import ticode.base.JException;
import ticode.base.TextBox;
import ticode.jvm.JFile;
import ticode.jvm.UUID;

public class HttpResponse {
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

public JsonObject 到JSON对象() {
JsonObject json = 到文本();
return json;
}

public JsonArray 到JSON数组() {
JsonArray json = 到文本();
return json;
}
}
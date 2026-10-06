package ticode.zh.android;

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
import static ticode.zh.android.流程处理.是否处于主线程;
import static ticode.zh.android.流程处理.等待新线程执行完毕;
import static ticode.zh.android.流程处理.结束提交到新线程;

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

public static int 全局网络请求超时 = 6000;
public static boolean 全局网络请求GZIP压缩 = false;
public static java.util.Map<String,String> 全局网络请求头;
public static 网络请求结果 httpGetResult;
public static Object 全局POST提交数据 = null;

public static int 取网络请求超时() {
return 全局网络请求超时;
}

public static void 置网络请求超时(int 超时) {
全局网络请求超时 = 超时;
}

public static boolean 取网络请求GZIP压缩() {
return 全局网络请求GZIP压缩;
}

public static void 置网络请求GZIP压缩(boolean GZIP压缩) {
全局网络请求GZIP压缩 = GZIP压缩;
}

public static void 添加网络请求头(String 名称, String 值) {
全局网络请求头.put(名称, 值);
}

public static void 移除网络请求头(String 名称) {
全局网络请求头.remove(名称);
}

public static void 清除网络请求头() {
全局网络请求头.clear();
}

public static 网络请求结果 GET同步请求(String 网址, String Cookie, String 编码) {
网络请求结果 结果 = new 网络请求结果();
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

public static void GET异步请求(String 网址, String Cookie, String 编码) { }

public static void POST提交数据(Object 提交数据) {
全局POST提交数据 = 提交数据;
}

public static 网络请求结果 POST同步请求(String 网址, String Cookie, String 编码) {
网络请求结果 结果 = new 网络请求结果();
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

public static void POST异步请求(String 网址, String Cookie, String 编码) { }

public static 网络请求结果 取网络请求结果() {
return httpGetResult;
}

public static void 结束网络请求() { }

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
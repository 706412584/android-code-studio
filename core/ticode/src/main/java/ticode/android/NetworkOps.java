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

public class NetworkOps {
java.util.Map<String,String> 请求头;
boolean 是否支持重定向;




public void 添加请求头(String 名称, String 值) {
请求头[名称] = 值;
}




public void 移除请求头(String 名称) {
请求头.删除项目(名称);
}




public void 清除请求头() {
请求头.清空();
}




public void 支持重定向(boolean 是否支持) {
this.是否支持重定向 = 是否支持;
}








public void 取网页源码(String 网址, String cookie, int 超时, String 编码) {
Object 结果 = 取网页源码_同步_内部(网址, cookie, 超时, 编码);
if (结果 == null) {
取网页源码失败();
} else {
byte[] content = (byte[]) 结果[0];
String cookie = (String) 结果[1];
取网页源码结束(TextBox.从字节集创建(content, 编码), content, cookie);
}
}








public String 取网页源码_同步(String 网址, String cookie, int 超时, String 编码) {
Object 结果 = 取网页源码_字节集_同步(网址, cookie, 超时, 编码);
if (结果 == null) {
return ("");
} else {
return TextBox.从字节集创建(结果, 编码);
}
}








public byte[] 取网页源码_字节集_同步(String 网址, String cookie, int 超时, String 编码) {
Object[] results = 取网页源码_同步_内部(网址, cookie, 超时, 编码);
if (results == null) {
return new byte[0];
}
return (byte[]) results[0];
}

public Object[] 取网页源码_同步_内部(String 网址, String cookie, int 超时, String 编码) {
return 发送请求_内部(网址, "GET",null,null,cookie, 超时, 编码);
}









public void 发送数据(String 网址, Object 欲发送数据, String cookie, int 超时, String 编码) {
Object 结果 = 发送数据_同步_内部(网址, 欲发送数据, cookie, 超时, 编码);
if (结果 == null) {
发送数据失败();
} else {
byte[] content = (byte[]) 结果[0];
String cookie = (String) 结果[1];
发送数据结束(TextBox.从字节集创建(content, 编码), content, cookie);
}
}









public String 发送数据_同步(String 网址, Object 欲发送数据, String cookie, int 超时, String 编码) {
Object 结果 = 发送数据_字节集_同步(网址, 欲发送数据, cookie, 超时, 编码);
if (结果 == null) {
return ("");
} else {
return TextBox.从字节集创建(结果, 编码);
}
}









public byte[] 发送数据_字节集_同步(String 网址, Object 欲发送数据, String cookie, int 超时, String 编码) {
Object[] results = 发送数据_同步_内部(网址, 欲发送数据, cookie, 超时, 编码);
if (results == null) {
return new byte[0];
}
return (byte[]) results[0];
}

public Object[] 发送数据_同步_内部(String 网址, Object 欲发送数据, String cookie, int 超时, String 编码) {
return 发送请求_内部(网址, "POST",null, 欲发送数据, cookie, 超时, 编码);
}










public void 下载(String 网址, String 保存路径, String cookie, int 超时, String 编码) {
Object 结果 = 发送请求_内部(网址, "GET", 保存路径,null, cookie, 超时, 编码);
if (结果 == null) {
下载失败();
} else {
String 结果cookie = ((String)结果[0]);
下载结束(结果cookie);
}
}










public boolean 下载_同步(String 网址, String 保存路径, String cookie, int 超时, String 编码) {
Object 结果 = 发送请求_内部(网址, "GET", 保存路径,null, cookie, 超时, 编码);
if (结果 == null) {
return (false);
}
return (true);
}











public void 上传(String 网址, String 文件路径, String 键名, String cookie, int 超时, String 编码) {
Object 结果 = 上传_内部(网址, 文件路径, 键名, null, cookie, 超时, 编码);
if (结果 == null) {
上传失败();
} else {
byte[] content = (byte[]) 结果[0];
String cookie = (String) 结果[1];
上传结束(TextBox.从字节集创建(content, 编码), content, cookie);
}
}










public String 上传_同步(String 网址, String 文件路径, String 键名, String cookie, int 超时, String 编码) {
Object[] results = 上传_内部(网址, 文件路径, 键名, null, cookie, 超时, 编码);
if (results == null) {
return "";
}
byte[] bytes = (byte[]) results[0];
try {
return new String(bytes, 编码);
} catch (Exception e) {
return new String(bytes);
}
}












public void 上传2(String 网址, String 文件路径, String 键名, String 参数, String cookie, int 超时, String 编码) {
Object 结果 = 上传_内部(网址, 文件路径, 键名,参数, cookie, 超时, 编码);
if (结果 == null) {
上传失败();
} else {
byte[] content = (byte[]) 结果[0];
String cookie = (String) 结果[1];
上传结束(TextBox.从字节集创建(content, 编码), content, cookie);
}
}












public String 上传_同步2(String 网址, String 文件路径, String 键名, String 参数, String cookie, int 超时, String 编码) {
Object[] results = 上传_内部(网址, 文件路径, 键名,参数, cookie, 超时, 编码);
if (results == null) {
return "";
}
byte[] bytes = (byte[]) results[0];
try {
return new String(bytes, 编码);
} catch (Exception e) {
return new String(bytes);
}
}

public Object[] 发送请求_内部(String 网址, String 请求类型, String 下行路径, Object 欲发送数据, String cookie, int 超时, String 编码) {
try {
if (!网址.startsWith("http://") && !网址.startsWith("https://")) {
网址 = "http://" + 网址;
}
URL url = new URL(网址);
HttpURLConnection conn;
//https设置ssl
if (网址.startsWith("https://")) {
conn = (HttpsURLConnection) url.openConnection();
setSsl();
} else {
conn = (HttpURLConnection) url.openConnection();
}
conn.setConnectTimeout(超时);
conn.setReadTimeout(超时);
conn.setFollowRedirects(true);
conn.setDoInput(true);
conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
//设置编码
conn.setRequestProperty("Accept-Charset", 编码);
//设置cookie
if (cookie != null) {
conn.setRequestProperty("Cookie", cookie);
}
//设置请求类型(GET/POST/DELETE/PUT)
conn.setRequestMethod(请求类型);
//设置请求头
if (请求头 != null) {
Set<Map.Entry<String, String>> entries = 请求头.entrySet();
for (Map.Entry<String, String> entry : entries) {
conn.setRequestProperty(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
}
}
//POST发送的数据
byte[] data = null;
if (欲发送数据 != null) {
data = formatData(欲发送数据, 编码);
if(data != null) {
conn.setDoOutput(true);
conn.setRequestProperty("Content-length", "" + data.length);
OutputStream os = conn.getOutputStream();
os.write(data);
}
}
conn.connect();
//如果下行路径不为空且请求类型为GET，则是下载
if ("GET".equals(请求类型) && 下行路径 != null) {
long length = conn.getContentLengthLong();
File f = new File(下行路径);
if (!f.getParentFile().exists()) {
f.getParentFile().mkdirs();
}
FileOutputStream os = new FileOutputStream(f);
InputStream is = conn.getInputStream();
copyFile(is, os, length);
Map<String, List<String>> hs = conn.getHeaderFields();
List<String> cs = hs.get("Set-Cookie");
StringBuffer cok = new StringBuffer();
if (cs != null) {
for (String s : cs) {
cok.append(s + ";");
}
}
String returnCookie = cok.toString();
return new Object[] { returnCookie };
}
int responseCode = conn.getResponseCode();
//判断重定向
if (是否支持重定向 && (responseCode == HttpURLConnection.HTTP_MOVED_TEMP
|| responseCode == HttpURLConnection.HTTP_MOVED_PERM
|| responseCode == HttpURLConnection.HTTP_SEE_OTHER)) {
String newUrl = conn.getHeaderField("Location");
conn.disconnect();
return 发送请求_内部(newUrl, 请求类型, 下行路径, 欲发送数据, cookie, 超时, 编码);
}
//获取返回结果
if (responseCode >= 200 && responseCode < 400) {
Map<String, List<String>> hs = conn.getHeaderFields();
List<String> cs = hs.get("Set-Cookie");
StringBuffer cok = new StringBuffer();
if (cs != null) {
for (String s : cs) {
cok.append(s + ";");
}
}
ByteArrayOutputStream boas = new ByteArrayOutputStream();
byte[] tmp = new byte[1024];
int len;
InputStream is = conn.getInputStream();
while ((len = is.read(tmp)) != -1) {
boas.write(tmp, 0, len);
}
byte[] result = boas.toByteArray();
boas.close();
is.close();
String cookie = cok.toString();
return new Object[]{result, cookie};
}
} catch (Exception e) {
e.printStackTrace();
}
return null;
}

public Object[] 上传_内部(String 网址, String 文件路径, String 键名, String 参数表文本, String cookie, int 超时, String 编码) {
String BOUNDARY = UUID.randomUUID().toString(); //边界标识 随机生成;
String PREFIX = "--", LINE_END = "\r\n";
String CONTENT_TYPE = "multipart/form-data"; //内容类型;
try {
URL url = new URL(网址);
HttpURLConnection conn;
//https设置ssl
if (网址.startsWith("https://")) {
conn = (HttpsURLConnection) url.openConnection();
setSsl();
} else {
conn = (HttpURLConnection) url.openConnection();
}
conn.setConnectTimeout(超时);
conn.setFollowRedirects(true);
conn.setDoInput(true); //允许输入流;
conn.setDoOutput(true); //允许输出流;
conn.setUseCaches(false); //不允许使用缓存;
conn.setRequestMethod("POST"); //请求方式;
//设置编码
conn.setRequestProperty("Accept-Charset", 编码);
//设置cookie
if (cookie != null) {
conn.setRequestProperty("Cookie", cookie);
}
conn.setRequestProperty("connection", "keep-alive");
conn.setRequestProperty("Content-Type", CONTENT_TYPE + ";boundary=" + BOUNDARY);
//设置请求头
if (请求头 != null) {
Set<Map.Entry<String, String>> entries = 请求头.entrySet();
for (Map.Entry<String, String> entry : entries) {
conn.setRequestProperty(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
}
}

OutputStream outputSteam = conn.getOutputStream();
DataOutputStream dos = new DataOutputStream(outputSteam);

// 解析并添加表单参数
if (参数表文本 != null && !参数表文本.isEmpty()) {
String[] params = 参数表文本.split("\n");
for (String param : params) {
String[] keyValue = param.split("=", 2);
if (keyValue.length == 2) {
String key = keyValue[0].trim();
String value = keyValue[1].trim();

StringBuffer paramSb = new StringBuffer();
paramSb.append(PREFIX).append(BOUNDARY).append(LINE_END);
paramSb.append("Content-Disposition: form-data; name=\"" + key + "\"");
paramSb.append(LINE_END).append(LINE_END);
paramSb.append(value).append(LINE_END);
dos.write(paramSb.toString().getBytes(编码));
}
}
}

// 添加文件参数
StringBuffer fileSb = new StringBuffer();
fileSb.append(PREFIX).append(BOUNDARY).append(LINE_END);
fileSb.append("Content-Disposition: form-data; name=\"" + 键名 + "\"; filename=\"" + FileOps.取文件名(文件路径) + "\"");
fileSb.append(LINE_END);
fileSb.append("Content-Type: application/octet-stream; charset=" + 编码).append(LINE_END);
fileSb.append(LINE_END);
dos.write(fileSb.toString().getBytes(编码));

InputStream is = new FileInputStream(文件路径);
byte[] bytes = new byte[1024];
int len;
long max = new File(文件路径).length();
long progress = 0;
while ((len = is.read(bytes)) != -1) {
dos.write(bytes, 0, len);
progress += len;
double d = (new BigDecimal(progress / (double) max).setScale(2,
BigDecimal.ROUND_HALF_UP)).doubleValue();
double d1 = d * 100;
正在上传(d1);
}
is.close();

// 写入结束标记
byte[] end_data = (LINE_END + PREFIX + BOUNDARY + PREFIX + LINE_END).getBytes(编码);
dos.write(end_data);
dos.flush();

int res = conn.getResponseCode();
if (res >= 200 && res < 400) {
Map<String, List<String>> hs = conn.getHeaderFields();
List<String> cs = hs.get("Set-Cookie");
StringBuffer cok = new StringBuffer();
if (cs != null) {
for (String s : cs) {
cok.append(s).append(";");
}
}
ByteArrayOutputStream bos = new ByteArrayOutputStream();
InputStream resultStream = conn.getInputStream();
len = -1;
byte[] buffer = new byte[1024 * 8];
while ((len = resultStream.read(buffer)) != -1) {
bos.write(buffer, 0, len);
}
resultStream.close();
bos.flush();
bos.close();
byte[] result = bos.toByteArray();
return new Object[]{result, cok.toString()};
}
} catch (Exception e) {
e.printStackTrace();
}
return null;
}





public void 取网页源码结束(String 结果, byte[] 内容, String cookie) { } // 事件





public void 发送数据结束(String 结果, byte[] 内容, String cookie) { } // 事件




public void 正在下载(double 进度) { } // 事件





public void 下载结束(String cookie) { } // 事件




public void 正在上传(double 进度) { } // 事件





public void 上传结束(String 结果, byte[] 内容, String cookie) { } // 事件




public void 取网页源码失败() { } // 事件




public void 发送数据失败() { } // 事件




public void 下载失败() { } // 事件




public void 上传失败() { } // 事件

private boolean copyFile(InputStream in, OutputStream out, long length) {
try {
int readLength = 0;
int byteread = 0;
byte[] buffer = new byte[1024 * 1024];
while ((byteread = in.read(buffer)) != -1) {
readLength += byteread;
double value = ((readLength / (length * 1.0)) * 100);
正在下载(value);
out.write(buffer, 0, byteread);
}
//in.close
//out.close
} catch (Exception e) {
return false;
}
return true;
}

private static byte[] formatData(Object obj, String charset) throws UnsupportedEncodingException, IOException {
byte[] bs = null;
if (obj instanceof String)
bs = ((String) obj).getBytes(charset);
else if (obj.getClass().getComponentType() == byte.class)
bs = (byte[]) obj;
else if (obj instanceof File)
bs = readAll(new FileInputStream((File) obj));
else
bs = String.valueOf(obj).getBytes(charset);
return bs;
}

private static byte[] readAll(InputStream input) throws IOException {
ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
byte[] buffer = new byte[2 ^ 32];
int n = 0;
while (-1 != (n = input.read(buffer))) {
output.write(buffer, 0, n);
}
byte[] ret = output.toByteArray();
output.close();
return ret;
}

private static void setSsl() {
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
e.printStackTrace();
}
}
}
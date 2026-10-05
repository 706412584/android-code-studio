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

public class AndroidResourceId extends android.net.Uri {
public static AndroidResourceId 赋值_op(String Uri编码内容) {
return 解析(Uri编码内容);
}

public static AndroidResourceId 解析(String Uri编码内容) {
return android.net.Uri.parse(Uri编码内容);
}

public static AndroidResourceId 从文件创建(JFile 文件对象) {
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

public AndroidResourceId 规范化方案() {
return this.normalizeScheme();
}

public static String 文本到Uri编码(String 编码内容) {
return android.net.Uri.encode(编码内容);
}

public static String 文本到Uri解码(String 编码内容) {
return android.net.Uri.decode(编码内容);
}

public int 比较(AndroidResourceId 比较对象) {
return this.compareTo(比较对象);
}

public String 到文本() {
return this.toString();
}


}
package ticode.zh.android;

import java.net.HttpURLConnection;
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
try {
return new JSON对象(到文本()) {};
} catch (Exception e) {
e.printStackTrace();
return null;
}
}

public JSON数组 到JSON数组() {
try {
return new JSON数组(到文本()) {};
} catch (Exception e) {
e.printStackTrace();
return null;
}
}
}
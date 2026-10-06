package ticode.zh.android;

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

import ticode.zh.jvm.文件;

public class 安卓资源标识符 {
private android.net.Uri 内部对象;

public 安卓资源标识符(android.net.Uri Uri对象) {
this.内部对象 = Uri对象;
}

public android.net.Uri 取内部对象() {
return this.内部对象;
}

public static 安卓资源标识符 赋值_op(String Uri编码内容) {
return 解析(Uri编码内容);
}

public static 安卓资源标识符 解析(String Uri编码内容) {
return new 安卓资源标识符(android.net.Uri.parse(Uri编码内容));
}

public static 安卓资源标识符 从文件创建(java.io.File 文件对象) {
return new 安卓资源标识符(android.net.Uri.fromFile(文件对象));
}

public String 协议名称() {
return this.内部对象.getScheme();
}

public String 协议内容() {
return this.内部对象.getSchemeSpecificPart();
}


public String 主机名() {
return this.内部对象.getAuthority();
}


public String 主机地址() {
return this.内部对象.getHost();
}


public int 主机端口() {
return this.内部对象.getPort();
}


public String 用户信息() {
return this.内部对象.getUserInfo();
}

public String 编码用户信息() {
return this.内部对象.getEncodedUserInfo();
}

public String 资源路径() {
return this.内部对象.getPath();
}

public String 片段内容() {
return this.内部对象.getFragment();
}

public String 查询参数() {
return this.内部对象.getQuery();
}

public boolean 是否为绝对Uri() {
return this.内部对象.isAbsolute();
}

public boolean 是否为不透明Uri() {
return this.内部对象.isOpaque();
}

public boolean 是否为相对Uri() {
return this.内部对象.isRelative();
}

public boolean 是否为绝对分层Uri() {
return this.内部对象.isHierarchical();
}

public String 取查询参数值(String 参数名称) {
return this.内部对象.getQueryParameter(参数名称);
}

public String[] 取所有参数名() {
return (String[])this.内部对象.getQueryParameterNames().toArray();
}

public java.util.List<String> 取路径片段() {
return (java.util.ArrayList)this.内部对象.getPathSegments();
}

public String 获取最后路径片段() {
return this.内部对象.getLastPathSegment();
}

public 安卓资源标识符 规范化方案() {
return new 安卓资源标识符(this.内部对象.normalizeScheme());
}

public static String 文本到Uri编码(String 编码内容) {
return android.net.Uri.encode(编码内容);
}

public static String 文本到Uri解码(String 编码内容) {
return android.net.Uri.decode(编码内容);
}

public int 比较(安卓资源标识符 比较对象) {
return this.内部对象.compareTo(比较对象.取内部对象());
}

public String 到文本() {
return this.内部对象.toString();
}


}

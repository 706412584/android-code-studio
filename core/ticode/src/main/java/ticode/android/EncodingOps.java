package ticode.android;

import java.io.*;
import java.util.*;
import java.lang.reflect.*;
import android.view.*;
import android.util.*;
import android.net.*;
import android.database.*;
import android.provider.*;
import android.content.*;
import android.content.res.*;
import android.os.*;
import android.system.*;
import android.graphics.*;
import android.os.*;
import android.app.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.io.*;
import java.io.*;
import java.net.*;
import java.math.*;
import java.io.*;

import ticode.base.JException;
import ticode.base.TextBox;
import ticode.jvm.JCollection;
import ticode.jvm.JHashMap;
import ticode.jvm.KeyValuePair;
import ticode.jvm.UUID;

public class EncodingOps {





public static String URL编码(String 值, String 编码) {
try {
return URLEncoder.encode(值, 编码);
} catch (UnsupportedEncodingException e) {
return 值;
}
}





public static String URL解码(String 值, String 编码) {
try {
return URLDecoder.decode(值, 编码);
} catch (UnsupportedEncodingException e) {
return 值;
}
}

//将文本转换成新的编码格式
public static String 转换编码(String 待转换文本, String 原编码, String 新编码) {
if (待转换文本 == null) {
return "";
}
try {
return new String(待转换文本.getBytes(原编码), 新编码);
} catch (UnsupportedEncodingException e) {
return 待转换文本;
}
}

//将ANSI文本转换成UCS2编码的文本，例如：EncodingOps.UCS2编码("结绳，你好！")
public static String UCS2编码(String 值) {
String content = 值;
if (content == null || content.isEmpty()) return null;
StringBuilder sb = new StringBuilder();
int len = content.length();
for (int i = 0; i < len; i++) {
String hex = Integer.toHexString(content.charAt(i));
int padding = 4 - hex.length();
sb.append("\\u");
for (int j = 0; j < padding; j++) {
sb.append('0');
}
sb.append(hex);
}
return sb.toString();
}

//将UCS2编码的文本转换成ANSI文本，例如：EncodingOps.UCS2解码("\u7ed3\u7ef3\uff0c\u4f60\u597d\uff01")
public static String UCS2解码(String 值) {
String content = 值;
if (content == null || content.isEmpty()) return null;
StringBuilder sb = new StringBuilder();
int len = content.length();
for (int i = 0; i < len; i++) {
if (i + 5 < len && content.charAt(i) == '\\' && content.charAt(i + 1) == 'u') {
int start = i + 2, end = start;
while (content.charAt(end) == '0') {
end++;
}
try {
String hex = content.substring(start, start + 4);
int code = Integer.parseInt(hex, 16);
sb.append((char) code);
i += 5;
} catch (Exception e) {
sb.append(content.charAt(i));
}
} else {
sb.append(content.charAt(i));
}
}
return sb.toString();
}

public static String 文本到二进制(String 内容, String 编码) {
String content = 内容;
if (content == null || content.isEmpty()) return null;
try {
BigInteger bigInteger = new BigInteger(1, content.getBytes(编码));
StringBuilder binaryStr = new StringBuilder(bigInteger.toString(2));
while (binaryStr.length() % 8 != 0) {
binaryStr.insert(0, '0');
}
return binaryStr.toString();
} catch (Exception e) {
e.printStackTrace();
return "";
}
}

public static String 二进制到文本(String 内容) {
String content = 内容;
if (content == null || content.isEmpty()) return null;
try {
int len = content.length();
StringBuilder sb = new StringBuilder();
for (int i = 0; i < len; i += 8) {
int value = Integer.parseInt(content.substring(i, i + 8), 2);
sb.append((char) value);
}
return sb.toString();
} catch (Exception e) {
e.printStackTrace();
return "";
}
}

public static String 文本到十六进制(String 内容, String 编码) {
String content = 内容;
if (content == null || content.isEmpty()) return null;
try {
String digits = "0123456789ABCDEF";
byte[] bytes = content.getBytes(编码);
int len = bytes.length;
char[] chars = new char[len << 1];
for (int i = 0; i < len; i++) {
int value = bytes[i] & 0xff;
int index = i << 1;
chars[index] = digits.charAt(value >> 4);
chars[index + 1] = digits.charAt(bytes[i] & 0x0f);
}
return new String(chars);
} catch (Exception e) {
e.printStackTrace();
return "";
}
}

public static String 十六进制到文本(String 内容, String 编码) {
String content = 内容;
if (content == null || content.isEmpty()) return null;
try {
int len = content.length();
byte[] bytes = new byte[len >> 1];
for (int i = 0; i < len; i += 2) {
int digit1 = Character.digit(content.charAt(i), 16) << 4;
int digit2 = Character.digit(content.charAt(i + 1), 16);
bytes[i >> 1] = (byte) (digit1 + digit2);
}
return new String(bytes, 编码);
} catch (Exception e) {
e.printStackTrace();
return "";
}
}

}
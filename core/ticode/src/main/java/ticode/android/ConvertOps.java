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
import android.app.*;
import java.util.regex.*;
import java.net.*;
import java.math.*;

public class ConvertOps {



public static String 对象到文本(Object 值) {
return 值.到文本();
}




public static byte 整数到字节(int 值) {
return (byte)(值);
}

//将中文转为unicode编码
public static String 中文转Unicode(String 值) {
char[] utfBytes = 值.toCharArray();
String unicodeBytes = "";
for (int i = 0; i < utfBytes.length; i++) {
String hexB = Integer.toHexString(utfBytes[i]);
if (hexB.length() <= 2) {
hexB = "00" + hexB;
}
unicodeBytes = unicodeBytes + "\\u" + hexB;
}
return unicodeBytes;
}

//将unicode编码转为中文
public static String Unicode转中文(String 值) {
Pattern pattern = Pattern.compile("(\\\\u(\\p{XDigit}{4}))");
Matcher matcher = pattern.matcher(值);
char ch;
while (matcher.find()) {
ch = (char) Integer.parseInt(matcher.group(2), 16);
值 = 值.replace(matcher.group(1), ch + "");
}
return 值;
}

//将字节型数组转为文本，方法名有异议，已废弃使用
public static String 字节到文本(byte[] 值, String 编码) {
try {
return new String(值, 编码);
} catch (Exception ex) {
}
return null;
}

//将文本转为字节型数组，方法名有异议，已废弃使用
public static byte[] 文本到字节(String 值, String 编码) {
try {
return 值.getBytes(编码);
} catch (Exception ex) {
throw new RuntimeException("文本到字节( 解码错误");
}
}

//将字节型数组转换成整数，方法名有异议，已废弃使用
public static int 字节到整数(byte[] 值) {
int targets = 值[0] & 0xFF | 值[1] << 8 & 0xFF00 | 值[2] << 24 >>> 8 | 值[3] << 24;
return targets;
}

//将整数转换成字节型数组，方法名有异议，已废弃使用

//将字节型数组转换成长整数，方法名有异议，已废弃使用
public static long 字节到长整数(byte[] 值) {
return (值[0] & 0xFF) << 56 | (值[1] & 0xFF) << 48 | (值[2] & 0xFF) << 40 | (值[3] & 0xFF) << 32 | (值[4] & 0xFF) << 24 | (值[5] & 0xFF) << 16 | (值[6] & 0xFF) << 8 | (值[7] & 0xFF) << 0;
}

//将长整数转换成字节型数组，方法名有异议，已废弃使用
public static byte[] 长整数到字节(long 值) {
byte[] bb = new byte[8];
bb[0] = ((byte)(int)(值 >> 56));
bb[1] = ((byte)(int)(值 >> 48));
bb[2] = ((byte)(int)(值 >> 40));
bb[3] = ((byte)(int)(值 >> 32));
bb[4] = ((byte)(int)(值 >> 24));
bb[5] = ((byte)(int)(值 >> 16));
bb[6] = ((byte)(int)(值 >> 8));
bb[7] = ((byte)(int)值);
return bb;
}

//将字节型数组转为文本
public static String 字节集到文本(byte[] 值, String 编码) {
try {
return new String(值, 编码);
} catch (Exception ex) {
}
return null;
}

//将文本转为字节型数组
public static byte[] 文本到字节集(String 值, String 编码) {
try {
return 值.getBytes(编码);
} catch (Exception ex) {
throw new RuntimeException("文本到字节( 解码错误");
}
}

//将字节型数组转换成整数
public static int 字节集到整数(byte[] 值) {
int targets = 值[0] & 0xFF | 值[1] << 8 & 0xFF00 | 值[2] << 24 >>> 8 | 值[3] << 24;
return targets;
}

//将整数转换成字节型数组
public static byte[] 整数到字节集(int 值) {
byte[] targets = new byte[4];
targets[0] = ((byte)(值 & 0xFF));
targets[1] = ((byte)(值 >> 8 & 0xFF));
targets[2] = ((byte)(值 >> 16 & 0xFF));
targets[3] = ((byte)(值 >>> 24));
return targets;
}

//将字节型数组转换成长整数
public static long 字节集到长整数(byte[] 值) {
return (值[0] & 0xFF) << 56 | (值[1] & 0xFF) << 48 | (值[2] & 0xFF) << 40 | (值[3] & 0xFF) << 32 | (值[4] & 0xFF) << 24 | (值[5] & 0xFF) << 16 | (值[6] & 0xFF) << 8 | (值[7] & 0xFF) << 0;
}

//将长整数转换成字节型数组
public static byte[] 长整数到字节集(long 值) {
byte[] bb = new byte[8];
bb[0] = ((byte)(int)(值 >> 56));
bb[1] = ((byte)(int)(值 >> 48));
bb[2] = ((byte)(int)(值 >> 40));
bb[3] = ((byte)(int)(值 >> 32));
bb[4] = ((byte)(int)(值 >> 24));
bb[5] = ((byte)(int)(值 >> 16));
bb[6] = ((byte)(int)(值 >> 8));
bb[7] = ((byte)(int)值);
return bb;
}

//将10进制数值转换成大写汉字的人民币金额文本
public static String 数值到金额(double 值) {
if ((值 > 1.0E+018D) || (值 < - 1.0E+018D)) {
return "";
}
String[] chineseDigits = new String[]{ "零", "壹", "贰", "叁", "肆", "伍", "陆", "柒", "捌", "玖" };
boolean negative = false;
if (值 < 0.0D) {
negative = true;
值 *= - 1.0D;
}
long temp = Math.round(值 * 100.0D);
int numFen = (int)(temp % 10L);
temp /= 10L;
int numJiao = (int)(temp % 10L);
temp /= 10L;
int[] parts = new int[20];
int numParts = 0;
for (int i = 0;
temp != 0L; i++) {
int part = (int)(temp % 10000L);
parts[i] = part;
numParts++;
temp /= 10000L;
}
boolean beforeWanIsZero = true;
String chineseStr = "";
for (int i = 0; i < numParts; i++) {
String partChinese = partTranslate(parts[i]);
if (i % 2 == 0) {
if ("".equals(partChinese))
beforeWanIsZero = true;
else {
beforeWanIsZero = false;
}
}
if (i != 0) {
if (i % 2 == 0) {
chineseStr = "亿" + chineseStr;
} else if (("".equals(partChinese)) && (!beforeWanIsZero)) {
chineseStr = "零" + chineseStr;
} else {
if ((parts[(i - 1)] < 1000) && (parts[(i - 1)] > 0)) {
chineseStr = "零" + chineseStr;
}
chineseStr = "万" + chineseStr;
}
}

chineseStr = partChinese + chineseStr;
}
if ("".equals(chineseStr))
chineseStr = chineseDigits[0];
else if (negative) {
chineseStr = "负" + chineseStr;
}
chineseStr = chineseStr + "元";
if ((numFen == 0) && (numJiao == 0))
chineseStr = chineseStr + "整";
else if (numFen == 0)
chineseStr = chineseStr + chineseDigits[numJiao] + "角";
else if (numJiao == 0)
chineseStr = chineseStr + "零" + chineseDigits[numFen] + "分";
else {
chineseStr = chineseStr + chineseDigits[numJiao] + "角" + chineseDigits[numFen] + "分";
}

return chineseStr;
}

//将字节集(字节型数组)转换成16进制文本
public static String 字节集到十六进制(byte[] 值) {
byte[] hex = "0123456789ABCDEF".getBytes();
byte[] buff = new byte[2 * 值.length];
for (int i = 0; i < 值.length; i++) {
buff[(2 * i)] = hex[(值[i] >> 4 & 0xF)];
buff[(2 * i + 1)] = hex[(值[i] & 0xF)];
}
return new String(buff);
}

//将16进制文本转换成字节集(字节型数组)
public static byte[] 十六进制到字节集(String 值) {
byte[] b = new byte[值.length() / 2];
int j = 0;
for (int i = 0; i < b.length; i++) {
char c0 = 值.charAt(j++);
char c1 = 值.charAt(j++);
b[i] = ((byte)(parse(c0) << 4 | parse(c1)));
}
return b;
}

private static String partTranslate(int amountPart) {
if ((amountPart < 0) || (amountPart > 10000)) {
return "";
}
String[] chineseDigits = new String[]{ "零", "壹", "贰", "叁", "肆", "伍", "陆", "柒", "捌", "玖" };
String[] units = new String[]{ "", "拾", "佰", "仟" };
int temp = amountPart;
String amountStr = new Integer(amountPart).toString();
int amountStrLength = amountStr.length();
boolean lastIsZero = true;
String chineseStr = "";
for (int i = 0;
(i < amountStrLength) && (temp != 0); i++) {
int digit = temp % 10;
if (digit == 0) {
if (!lastIsZero) {
chineseStr = "零" + chineseStr;
}
lastIsZero = true;
} else {
chineseStr = chineseDigits[digit] + units[i] + chineseStr;
lastIsZero = false;
}
temp /= 10;
}
return chineseStr;
}

private static int parse(char c) {
if (c >= 'a') {
return c - 'a' + 10 & 0xF;
}
if (c >= 'A') {
return c - 'A' + 10 & 0xF;
}
return c - '0' & 0xF;
}
}
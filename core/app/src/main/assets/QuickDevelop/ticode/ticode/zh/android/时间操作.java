package ticode.zh.android;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import java.util.regex.*;
import java.text.*;
import java.nio.channels.*;
import java.security.*;
import android.content.*;
import android.provider.*;
import android.graphics.*;

public class 时间操作 {

//根据格式获取时间文本，年为y，月为M，日为d，时为H，分为m，秒为s，如：取格式时间("yyyy-MM-dd")，返回xxxx-xx-xx，对应年份-月份-日
public static String 取格式时间(String 格式) {
return (new SimpleDateFormat(格式).format(System.currentTimeMillis()));
}

//将时间戳转换为指定时间文本格式，年为y，月为M，日为d，时为H，分为m，秒为s，如：时间戳到文本(1239552759,"yyyy-MM-dd")，返回xxxx-xx-xx，对应年份-月份-日
public static String 时间戳到文本(long 时间戳, String 时间格式文本) {
return (new SimpleDateFormat(时间格式文本).format(时间戳));
}

//返回现行时间戳长整数,单位为毫秒，也就是从1970年1月1日到现在的毫秒数
public static long 取当前时间戳() {
return System.currentTimeMillis();
}

public static long 取当前纳秒时间戳() {
return System.nanoTime();
}

//返回从某个不明确的起点到当前时间的纳秒数,它不会因为系统时间被调整而受到影响
public static long 取相对纳秒时间戳() {
return System.nanoTime();
}

//将时间文本转为时间戳
public static long 时间文本到时间戳(String 时间格式, String 时间文本) {
SimpleDateFormat format = new SimpleDateFormat(时间格式);
try {
return format.parse(时间文本).getTime();
} catch(Exception e) {
e.printStackTrace();
}
return 0;
}
}
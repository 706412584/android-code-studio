package ticode.zh.android;

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

import ticode.zh.base.异常;
import ticode.zh.base.数字;
import ticode.zh.base.文本;
import ticode.zh.base.长整数;
import ticode.zh.jvm.UUID;
import ticode.zh.jvm.哈希表;
import ticode.zh.jvm.键值对;
import ticode.zh.jvm.集合;

public class 数组操作 {
public static int 取数组长度(Object[] 数组) {
return 数组.length;
}

public Object[] 截取数组(Object[] 原始数组, int 起始索引, int 末尾索引) {
if (起始索引 < 0) {
运行报错("截取数组 起始索引错误");
} else if (起始索引 >= 取数组长度(原始数组)) {
运行报错("截取数组 起始索引错误");
} else if (末尾索引 < 起始索引) {
运行报错("截取数组 末尾索引错误");
} else if (末尾索引 >= 取数组长度(原始数组)) {
末尾索引 = 取数组长度(原始数组) - 1;
}
int 截取长度 = 末尾索引 - 起始索引 + 1;
Object[] 结果数组 = new Object[截取长度];
System.arraycopy(原始数组, 起始索引, 结果数组, 0, 截取长度);
return 结果数组;
}

public static 集合 数组到集合(Object[] 数组) {
java.util.ArrayList<Object> list = new java.util.ArrayList<>();
for (Object obj : 数组) {
list.add(obj);
}
return list;
}




public static String 数组到文本(Object 数组) {
return java.util.Arrays.toString(数组);
}




public static String 数组到文本2(Object 数组) {
return java.util.Arrays.deepToString(数组);
}




public static int[] 数组冒泡排序(int[] 整数数组) {
java.util.Arrays.sort(整数数组);
return 整数数组;
}




public static int 取数组最大数(int[] 整数数组) {
return java.util.stream.IntStream.of(整数数组).max().getAsInt();
}
}
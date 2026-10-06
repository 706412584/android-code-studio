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

public class StorageOps {

//获取储存卡根目录路径
public static String 取存储卡路径() {
return (Environment.getExternalStorageDirectory().getAbsolutePath());
}

//获取存储卡是否挂载
public static boolean 取存储卡状态() {
return "mounted".equals(Environment.getExternalStorageState());
}

//判断存储卡是否可写
public static boolean 存储卡是否可写() {
return Environment.getExternalStorageDirectory().canWrite();
}

//获取存储卡总容量，单位为MB
public static long 取存储卡总容量() {
File path = Environment.getExternalStorageDirectory();
StatFs sf = new StatFs(path.getPath());
long blockSize = sf.getBlockSize();
long allBlocks = sf.getBlockCount();
return allBlocks * blockSize / 1024L / 1024L;
}

//获取储存卡剩余容量，单位为MB
public static long 取存储卡剩余容量() {
File path = Environment.getExternalStorageDirectory();
StatFs statFs = new StatFs(path.getPath());
long blocSize = statFs.getBlockSize();
long availaBlock = statFs.getAvailableBlocks();
return availaBlock * blocSize / 1024L / 1024L;
}

//获取内部存储卡总容量，单位为MB
public static long 取内部存储卡总容量() {
File path = Environment.getDataDirectory();
StatFs stat = new StatFs(path.getPath());
long blockSize = stat.getBlockSize();
long totalBlocks = stat.getBlockCount();
return totalBlocks * blockSize / 1024L / 1024L;
}

//获取内部储存卡剩余容量，单位为MB
public static long 取内部存储卡剩余容量() {
File path = Environment.getDataDirectory();
StatFs stat = new StatFs(path.getPath());
long blockSize = stat.getBlockSize();
long availableBlocks = stat.getAvailableBlocks();
return availableBlocks * blockSize / 1024L / 1024L;
}

//获取手机总内存，单位为MB
public static long 取手机总内存() {
String str1 = "/proc/meminfo";
long initial_memory = 0L;
try {
FileReader localFileReader = new FileReader(str1);
BufferedReader localBufferedReader = new BufferedReader(localFileReader, 8192);
String str2 = localBufferedReader.readLine();
String[] arrayOfString = str2.split("\\s+");
initial_memory = Integer.valueOf(arrayOfString[1]).intValue();
localBufferedReader.close();
} catch (IOException e) {
return 0L;
}
return (initial_memory > 0) ? (initial_memory / 1024L) : 0L;
}

//获取手机剩余内存，单位为MB
public static long 取手机剩余内存(AndroidEnv 环境) {
ActivityManager am = (ActivityManager) 环境.getSystemService("activity");
ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
am.getMemoryInfo(mi);
return mi.availMem / 1024L / 1024L;
}

//获取手机CPU的主频
public static double 取CPU主频() {
int result = 0;
FileReader fr = null;
BufferedReader br = null;
try {
fr = new FileReader("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq");
br = new BufferedReader(fr);
String text = br.readLine();
result = Integer.parseInt(text.trim());
} catch (FileNotFoundException e) {
e.printStackTrace();
} catch (IOException e) {
e.printStackTrace();
}
finally {
if (fr != null) {
try {
fr.close();
} catch (IOException e) {
e.printStackTrace();
}
}
if (br != null) {
try {
br.close();
} catch (IOException e) {
e.printStackTrace();
}
}
}
return result / 1000 / 1000;
}
}
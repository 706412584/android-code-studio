package ticode.android;

import java.lang.System;
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

import ticode.base.TextBox;
import ticode.jvm.UUID;

public class SystemOps {
public static int 取屏幕宽度(AndroidEnv 环境) {
WindowManager wm = (WindowManager) 环境.getSystemService(Context.WINDOW_SERVICE);
DisplayMetrics outMetrics = new DisplayMetrics();
wm.getDefaultDisplay().getRealMetrics(outMetrics);
return outMetrics.widthPixels;
}

public static int 取屏幕高度(AndroidEnv 环境) {
WindowManager wm = (WindowManager) 环境.getSystemService(Context.WINDOW_SERVICE);
DisplayMetrics outMetrics = new DisplayMetrics();
wm.getDefaultDisplay().getRealMetrics(outMetrics);
return outMetrics.heightPixels;
}

public static int 取屏幕高度_不含导航栏(AndroidEnv 环境) {
if (!导航栏是否显示(环境)) {
return 取屏幕高度(环境);
}
DisplayMetrics outMetrics = new DisplayMetrics();
WindowManager wm = (WindowManager) 环境.getSystemService(Context.WINDOW_SERVICE);
wm.getDefaultDisplay().getMetrics(outMetrics);
int heightPixel = outMetrics.heightPixels;
if (Build.MANUFACTURER.equals("Xiaomi") && Settings.Global.getInt(环境.getContentResolver(), "force_fsg_nav_bar", 0) != 0) {
return heightPixel + 取导航栏高度(环境);
}
if (取导航栏高度(环境) + heightPixel < 取屏幕高度(环境)) {
return heightPixel + 取状态栏高度(环境);
}
return heightPixel;
}

public static int 取屏幕高度_不含导航栏和状态栏(AndroidEnv 环境) {
if (!导航栏是否显示(环境)) {
return 取屏幕高度(环境) - 取状态栏高度(环境);
}
DisplayMetrics outMetrics = new DisplayMetrics();
WindowManager wm = (WindowManager) 环境.getSystemService(Context.WINDOW_SERVICE);
wm.getDefaultDisplay().getMetrics(outMetrics);
int heightPixel = outMetrics.heightPixels;
int statusBarHeight = 取状态栏高度(环境);
if (Build.MANUFACTURER.equals("Xiaomi") && Settings.Global.getInt(环境.getContentResolver(), "force_fsg_nav_bar", 0) != 0) {
return heightPixel + 取导航栏高度(环境) - statusBarHeight;
}
if (取导航栏高度(环境) + heightPixel < 取屏幕高度(环境)) {
heightPixel = heightPixel + statusBarHeight;
}
return heightPixel - statusBarHeight;
}

public static double 取屏幕密度(AndroidEnv 环境) {
DisplayMetrics displaymetrics = new DisplayMetrics();
WindowManager wm = (WindowManager) 环境.getSystemService(Context.WINDOW_SERVICE);
wm.getDefaultDisplay().getMetrics(displaymetrics);
return displaymetrics.density;
}

public static int 取状态栏高度(AndroidEnv 环境) {
if (Build.VERSION.SDK_INT < 29) {
try {
Class<?> c = Class.forName("com.android.internal.R$dimen");
return 环境.getResources().getDimensionPixelSize(Integer.parseInt(c.getField("status_bar_height").get(c.newInstance()).toString()));
} catch (Exception e) {
e.printStackTrace();
return 0;
}
} else {
Resources resources = 环境.getResources();
return resources.getDimensionPixelSize(resources.getIdentifier("status_bar_height", "dimen", "android"));
}
}

public static int 取导航栏高度(AndroidEnv 环境) {
if (Build.VERSION.SDK_INT < 17) {
return 0;
}
Resources resources = 环境.getResources();
return resources.getDimensionPixelSize(resources.getIdentifier("navigation_bar_height", "dimen", "android"));
}

//判断系统导航栏是否开启
public static boolean 导航栏是否显示(AndroidEnv 环境) {
if (Build.VERSION.SDK_INT < 17) {
return false;
}
if (Build.MANUFACTURER.equals("Xiaomi") && Settings.Global.getInt(环境.getContentResolver(), "force_fsg_nav_bar", 0) != 0) {
return false;
}
DisplayMetrics outMetrics = new DisplayMetrics();
WindowManager wm = (WindowManager) 环境.getSystemService(Context.WINDOW_SERVICE);
wm.getDefaultDisplay().getRealMetrics(outMetrics);
int height1 = outMetrics.heightPixels;
wm.getDefaultDisplay().getMetrics(outMetrics);
if ((height1 - outMetrics.heightPixels) - 取状态栏高度(环境) > 0) {
return true;
}
return false;
}

public static void 置剪切板文本(AndroidEnv 环境, String 文本) {
ClipboardManager clipboard = (ClipboardManager) 环境.getSystemService("clipboard");
clipboard.setText(TextBox);
}

public static String 取剪切板文本(AndroidEnv 环境) {
ClipboardManager clipboard = (ClipboardManager) 环境.getSystemService("clipboard");
if (clipboard.hasText()) {
String clipText =  clipboard.getText().toString();
return clipText;
}
return "";
}

public static int 取屏幕刷新率(AndroidEnv 上下文环境) {
return (int) 上下文环境.getDisplay().getRefreshRate();
}

public static int 取屏幕最大刷新率(AndroidActivity 窗口环境) {
Display display = 窗口环境.getDisplay();
int maxRefreshRate = (int) display.getRefreshRate();
if (android.os.Build.VERSION.SDK_INT >= 23) {
for (Display.Mode mode : display.getSupportedModes()) {
int refreshRate = (int) mode.getRefreshRate();
if (refreshRate > maxRefreshRate) {
maxRefreshRate = refreshRate;
}
}
}
return maxRefreshRate;
}

public static void 置屏幕刷新率(AndroidActivity 窗口环境, int 刷新率) {
if (android.os.Build.VERSION.SDK_INT >= 23) {
Window window = 窗口环境.getWindow();
WindowManager.LayoutParams attributes = window.getAttributes();
Display display = 窗口环境.getDisplay();
Display.Mode maxMode = null;
for (Display.Mode mode : display.getSupportedModes()) {
int refreshRate = (int) mode.getRefreshRate();
if (refreshRate <= 刷新率) {
if (maxMode == null) {
maxMode = mode;
} else if (refreshRate > (int) maxMode.getRefreshRate()) {
maxMode = mode;
}
}
}
if (maxMode != null) {
attributes.preferredDisplayModeId = maxMode.getModeId();
window.setAttributes(attributes);
}
}
}

public static void 禁止截屏(Window2 窗口环境) {
窗口环境.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
}

public static void 截屏(AndroidActivity 窗口环境, String 输出路径) {
View decorView = 窗口环境.getWindow().getDecorView();
decorView.post(new Runnable() {
@Override
public void run() {
try {
Bitmap bitmap = Bitmap.createBitmap(decorView.getWidth(), decorView.getHeight(), Bitmap.Config.ARGB_8888);
Canvas canvas = new Canvas(bitmap);
decorView.draw(canvas);
bitmap.compress(Bitmap.CompressFormat.JPEG, 100, new FileOutputStream(输出路径));
bitmap.recycle();
} catch (Exception e) {
}
}
});
}

public static BitmapObject 截屏_位图(AndroidActivity 窗口环境) {
try {
View decorView = 窗口环境.getWindow().getDecorView();
Bitmap bitmap = Bitmap.createBitmap(decorView.getWidth(), decorView.getHeight(), Bitmap.Config.ARGB_8888);
Canvas canvas = new Canvas(bitmap);
decorView.draw(canvas);
return bitmap;
} catch (Exception e) {
return null;
}
}

public static String 取ANDROID_ID(AndroidActivity 环境) {
return Settings.System.getString(环境.getContentResolver(), Settings.System.ANDROID_ID);
}

public static String 取设备唯一标识符() {
String m_szDevIDShort = "35" + Build.BOARD.length() % 10
+ Build.BRAND.length() % 10 + Build.CPU_ABI.length() % 10
+ Build.DEVICE.length() % 10 + Build.DISPLAY.length() % 10
+ Build.HOST.length() % 10 + Build.ID.length() % 10
+ Build.MANUFACTURER.length() % 10 + Build.MODEL.length() % 10
+ Build.PRODUCT.length() % 10 + Build.TAGS.length() % 10
+ Build.TYPE.length() % 10 + Build.USER.length() % 10;
String serial = "serial";
try {
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
serial = android.os.Build.getSerial();
}
else {
serial = Build.SERIAL;
}
}
catch (Exception e) {
e.printStackTrace();
}
return new java.util.UUID(m_szDevIDShort.hashCode(), serial.hashCode()).toString();
}

public static void 置环境变量(String 名称, String 值, boolean 覆写) {
try {
Os.setenv(名称, 值, 覆写);
} catch (ErrnoException e) {
e.printStackTrace();
}
}

public static void 删除环境变量(String 名称) {
try {
Os.unsetenv(名称);
} catch (ErrnoException e) {
e.printStackTrace();
}
}

public static void 置文件权限(String 路径, int 权限模式) {
try {
Os.chmod(路径,权限模式);
} catch (ErrnoException e) {
e.printStackTrace();
}
}

public static void 创建硬链接(String 源路径, String 目标路径) {
try {
Os.link(源路径,目标路径);
} catch (ErrnoException e) {
e.printStackTrace();
}
}

public static void 创建软链接(String 源路径, String 目标路径) {
try {
Os.symlink(源路径,目标路径);
} catch (ErrnoException e) {
e.printStackTrace();
}
}

public static String 取软链接指向文件(String 路径) {
try {
return Os.readlink(路径);
} catch (ErrnoException e) {
e.printStackTrace();
}
return null;
}

public static int 取进程ID() {
return Os.getpid();
}

public static int 取父进程ID() {
return Os.getppid();
}

public static int 取用户ID() {
return Os.getuid();
}

//获取系统环境变量的值
public static String 取环境变量(String 名称) {
return System.getenv(名称);
}

public static java.util.Map<String,String> 取环境变量哈希表() {
Map<String, String> envMap = new HashMap<>();
for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
envMap.put(entry.getKey(), entry.getValue());
}
return (HashMap)envMap;
}

public static String 取系统属性(String 键名) {
return System.getProperty(键名);
}

public static java.util.Map<String,String> 取系统属性哈希表() {
Properties properties = System.getProperties();
HashMap<String, String> hashMap = new HashMap<>();
for (String key : properties.stringPropertyNames()) {
hashMap.put(key, properties.getProperty(key));
}
return hashMap;
}

public static void 置系统属性(String 键名, String 值) {
System.setProperty(键名,值);
}

public static void 清除系统属性(String 键名) {
System.clearProperty(键名);
}

//加载so库，so库路径可以为安装包lib下so库名称，也可以为绝对路径
public static void 加载SO库(String so库路径) {
if (so库路径.startsWith("/")) {
System.load(so库路径);
} else {
System.loadLibrary(so库路径);
}
}

public static void 优化内存() {
System.gc();
}

public static void 关闭程序() {
System.exit(0);
android.os.Process.killProcess(android.os.Process.myPid());
关闭程序();
}
}
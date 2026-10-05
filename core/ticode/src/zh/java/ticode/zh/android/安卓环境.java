package ticode.zh.android;

import android.os.Environment;
import android.content.pm.*;

import ticode.zh.jvm.文件;

import static ticode.zh.android.流程处理.提交到主线程运行2;
import static ticode.zh.android.流程处理.是否处于主线程;
import static ticode.zh.android.流程处理.结束提交到主线程;

public class 安卓环境 extends android.content.Context {

public static final int 绑定自动创建 = 0x1;





public void 弹出提示(Object 内容, boolean 长时显示) {
if (是否处于主线程()) {
android.widget.Toast.makeText(this, String.valueOf(内容), 长时显示 ? 1 : 0).show();
} else {
提交到主线程运行2();
android.widget.Toast.makeText(this, String.valueOf(内容), 长时显示 ? 1 : 0).show();
结束提交到主线程();
}
}

private static android.widget.Toast toast;
private static int delayed;
public void 快速提示(Object 内容, boolean 长时显示) {
流程处理.mainHandler.postDelayed(() -> {
if(toast != null) toast.cancel();
toast = android.widget.Toast.makeText(this.getApplicationContext(), String.valueOf(内容), 长时显示 ? 1 : 0);
toast.show();
delayed = delayed - 50;
},delayed);
delayed = delayed + 50;
}

//静态变量推荐使用全局环境，尽量避免使用窗口环境
public android.content.Context 取全局环境() {
return (android.content.Context)this.getApplicationContext();
}

//获取安卓资源管理器
public 安卓资源管理器 取安卓资源管理器() {
return (安卓资源管理器)this.getResources();
}

//获取安卓附加资源管理器
public android.content.res.AssetManager 取附加资源管理器() {
return (android.content.res.AssetManager)this.getAssets();
}





public void 发送广播(android.content.Intent 数据) {
this.sendBroadcast(数据);
}

public void 注册广播接收器(广播接收器 接收器, 启动信息过滤器 过滤器) {
this.registerReceiver(接收器,过滤器);
}

public void 注销广播接收器(广播接收器 接收器) {
this.unregisterReceiver(接收器);
}

public String 取自身包名() {
return this.getPackageName();
}

public int 取自身版本号() {
try {
PackageManager packageManager = this.getPackageManager();
PackageInfo packageInfo = packageManager.getPackageInfo(this.getPackageName(), 0);
return packageInfo.versionCode;
} catch (PackageManager.NameNotFoundException e) {
e.printStackTrace();
return 0;
}
}

public 安卓程序包管理器 取程序包管理器() {
return (安卓程序包管理器)this.getPackageManager();
}

public String 取自身版本名称() {
try {
return 取程序包管理器().取程序包信息(取自身包名()).版本名称;
} catch (Exception e) { }
return "";
}




public java.io.File 取私有目录() {
return (java.io.File)this.getFilesDir();
}




public String 取私有目录路径() {
return 取私有目录().取绝对路径();
}




public java.io.File 取内部私有缓存目录() {
return (java.io.File)this.getCacheDir();
}




public String 取内部私有缓存目录路径() {
return 取内部私有缓存目录().取绝对路径();
}

public java.io.File 取私有缓存目录() {
return (java.io.File)this.getExternalCacheDir();
}

public String 取私有缓存目录路径() {
return this.getExternalCacheDir().getAbsolutePath();
}

public java.io.File 取私有数据目录(String 目标) {
return (java.io.File)this.getExternalFilesDir(目标);
}

public String 取私有数据目录路径(String 目标) {
return this.getExternalFilesDir(目标).getAbsolutePath();
}




public java.io.File 取数据目录() {
return (java.io.File)this.getDataDir();
}




public String 取数据目录路径() {
return 取数据目录().取绝对路径();
}




public java.io.File 取公用下载目录() {
return (java.io.File)Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
}




public String 取公用下载目录路径() {
return 取公用下载目录().取绝对路径();
}




public java.io.File 取公用图片目录() {
return (java.io.File)Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
}




public String 取公用图片目录路径() {
return 取公用图片目录().取绝对路径();
}




public java.io.File 取公用文档目录() {
return (java.io.File)Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
}




public String 取公用文档目录路径() {
return 取公用文档目录().取绝对路径();
}
}
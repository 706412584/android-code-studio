package ticode.zh.android;

import android.content.Context;
import android.net.Uri;
import android.widget.Toast;
import android.provider.MediaStore;
import android.database.Cursor;
import android.provider.DocumentsContract;
import android.os.Environment;
import android.content.ContentUris;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.os.StrictMode;
import android.content.ComponentName;
import android.view.*;
import android.app.*;
import android.content.pm.*;
import android.os.*;
import android.content.*;
import android.graphics.drawable.*;

import ticode.zh.jvm.文件;

public abstract class 安卓窗口 extends android.app.Activity {

public static void newActivity(android.app.Activity activity, Class<?> clazz) {
android.content.Intent intent = new android.content.Intent(activity, clazz);
activity.startActivity(intent);
}

public static void newActivity2(
android.app.Activity activity, Class<?> clazz, android.content.Intent intent) {
intent.setClass(activity, clazz);
activity.startActivity(intent);
}

public static void newActivityForResult(android.app.Activity activity, Class<?> clazz, int requestCode) {
android.content.Intent intent = new android.content.Intent(activity, clazz);
activity.startActivityForResult(intent, requestCode);
}

public static void newActivityForResult2(
android.app.Activity activity, Class<?> clazz, int requestCode, android.content.Intent intent) {
intent.setClass(activity, clazz);
activity.startActivityForResult(intent, requestCode);
}





public void 主题(int 主题) {
this.setTheme(主题);
}

//设置窗口标题
public void 标题(String 标题) {
this.setTitle(标题);
}

//读取窗口标题
public String 标题() {
return (this.getTitle().toString());
}

//设置窗口标题颜色
public void 标题颜色(int 颜色) {
this.setTitleColor(颜色);
}

//读取窗口标题颜色
public int 标题颜色() {
return (this.getTitleColor());
}

//设置是否显示标题栏
public void 显示标题栏(boolean 是否显示标题栏) {
ActionBar actionBar = this.getActionBar();
if (actionBar == null) {
return;
}
if (是否显示标题栏) {
actionBar.show();
} else {
actionBar.hide();
}
}

//设置是否显示标题栏返回键
public void 显示标题栏返回键(boolean 是否显示) {
ActionBar actionBar = this.getActionBar();
if (actionBar == null) {
return;
}
if (是否显示) {
actionBar.setHomeButtonEnabled(true);
actionBar.setDisplayHomeAsUpEnabled(true);
} else {
actionBar.setHomeButtonEnabled(false);
actionBar.setDisplayHomeAsUpEnabled(false);
}
}

//设置状态栏颜色
public void 状态栏颜色(int 颜色) {
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
this.getWindow().setStatusBarColor(颜色);
}
}

//读取状态栏颜色
public int 状态栏颜色() {
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
return this.getWindow().getStatusBarColor();
}
return 0;
}

//设置导航栏颜色
public void 导航栏颜色(int 颜色) {
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
this.getWindow().setNavigationBarColor(颜色);
}
}

//读取导航栏颜色
public int 导航栏颜色() {
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
return this.getWindow().getNavigationBarColor();
}
return 0;
}

//设置状态栏字体颜色是否为黑色
public void 状态栏字体黑色(boolean 是否黑色) {
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
if (是否黑色 == true) {
this.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
} else {
this.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
}
}
}

//设置是否处于沉浸式体验模式
public void 沉浸模式(boolean 是否启用) {
this.setImmersive(是否启用);
if (是否启用) {
this.getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
this.getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
} else {
this.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
this.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
}
}

//设置是否处于沉浸式体验模式，和"沉浸模式"不同，该属性不会隐藏导航栏
public void 沉浸模式2(boolean 是否启用) {
this.setImmersive(是否启用);
if (是否启用) {
this.getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
} else {
this.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
}
}

//设置全屏模式
public void 全屏模式(boolean 是否启用) {
if (this.getWindow() != null) {
this.getWindow().setFlags(是否启用 ? WindowManager.LayoutParams.FLAG_FULLSCREEN : 0,WindowManager.LayoutParams.FLAG_FULLSCREEN);
}
}

//设置常亮模式
public void 常亮模式(boolean 是否启用) {
if (是否启用) {
this.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
} else {
this.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
}
}

//设置窗口亮度
public void 亮度(double 欲设置亮度) {
final Window window = this.getWindow();
WindowManager.LayoutParams params = window.getAttributes();
params.alpha = (float)欲设置亮度;
window.setAttributes(params);
}

//获取窗口亮度
public double 亮度() {
final Window window = this.getWindow();
WindowManager.LayoutParams params = window.getAttributes();
return params.alpha;
}

//设置屏幕方向
public void 屏幕方向(int 屏幕方向) {
this.setRequestedOrientation(屏幕方向);
}




public 应用 取全局应用() {
return (应用)this.getApplication();
}

//启动服务
public void 启动服务(安卓服务 欲启动服务) {
Intent intent = new Intent(this, 安卓服务.class);
this.startService(intent);
}

//启动服务
public void 启动服务(安卓服务 欲启动服务, android.content.Intent 欲传递参数) {
欲传递参数.setComponent(new ComponentName(this, 安卓服务.class));
this.startService(欲传递参数);
}

//绑定服务
public void 绑定服务(安卓服务 欲绑定服务, 服务连接 连接, int 标志) {
Intent intent = new Intent(this, 安卓服务.class);
this.bindService(intent,连接,标志);
}

//关闭指定服务类
public void 关闭服务(安卓服务 欲关闭服务) {
Intent intent = new Intent(this, 安卓服务.class);
this.stopService(intent);
}

//返回桌面，等同于手机按下Home键的效果
public void 返回桌面() {
Intent intent = new Intent(Intent.ACTION_MAIN);
intent.setAction(Intent.ACTION_MAIN);
intent.addCategory(Intent.CATEGORY_HOME);
this.startActivity(intent);
}

//将当前窗口所处任务移动到后台
public void 移动任务到后台() {
this.moveTaskToBack(true);
}




public void 关闭窗口() {
this.finish();
}






public void 切换窗口(安卓窗口 欲切换窗口, android.content.Intent 欲传递参数) {
if (欲传递参数 == null) {
安卓窗口.newActivity(this, 安卓窗口.class);
} else {
安卓窗口.newActivity2(this, 安卓窗口.class, 欲传递参数);
}
}








public void 切换窗口2(安卓窗口 欲切换窗口, int 请求码, android.content.Intent 欲传递参数) {
if (欲传递参数 == null) {
安卓窗口.newActivityForResult(this, 安卓窗口.class, 请求码);
} else {
安卓窗口.newActivityForResult2(this, 安卓窗口.class, 请求码, 欲传递参数);
}
}

//在切换窗口时播放自定义动画
public void 播放切换动画(int 进入新窗口动画资源ID, int 隐藏当前窗口动画资源ID) {
this.overridePendingTransition(进入新窗口动画资源ID, 隐藏当前窗口动画资源ID);
}

public static final int 从右往左 = 0;
public static final int 从左往右 = 1;
public static final int 淡入淡出 = 2;
public static final int 淡出淡入 = 3;

//播放安卓系统自带的一些窗口切换动画
public void 播放默认切换动画(int 动画类型) {
switch (动画类型) {
case 0:
this.overridePendingTransition(android.R.anim.slide_out_right, android.R.anim.slide_in_left);
break;
case 1:
this.overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right);
break;
case 2:
this.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
break;
case 3:
this.overridePendingTransition(android.R.anim.fade_out, android.R.anim.fade_in);
break;
}
}




public android.content.Intent 取启动信息() {
return (android.content.Intent)this.getIntent();
}

public Object 取系统服务(String 名称) {
return this.getSystemService(名称);
}

//如果上一个窗口启动本窗口时要求返回数据，可使用本方法进行返回
public void 置返回数据(int 结果码, android.content.Intent 欲返回数据) {
this.setResult(结果码, 欲返回数据);
}

//申请权限
public void 申请权限(int 请求码, String[] 欲申请权限) {
this.requestPermissions(欲申请权限,请求码);
}





public void 申请所有权限(int 请求码) {
if (设备信息.安卓版本号 < 23) {
return;
}
try {
PackageManager mPackageMgr = this.getPackageManager();
PackageInfo pack = mPackageMgr.getPackageInfo(this.getPackageName(), PackageManager.GET_PERMISSIONS);
String[] permissions = pack.requestedPermissions;
this.requestPermissions(permissions, 请求码);
} catch (Exception e) {
e.printStackTrace();
}
}




public void 申请文件管理权限() {
if (设备信息.安卓版本号 < 30) {
return;
}
android.content.Intent it = new android.content.Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
it.setData(android.net.Uri.parse("package:" + this.getPackageName()));
this.startActivity(it);
}
public void 选择图片(int 请求码) {
this.startActivityForResult(new android.content.Intent(启动信息.ACTION_PICK,MediaStore.Images.Media.EXTERNAL_CONTENT_URI), 请求码);
}
public String 解析图片地址(android.content.Intent 数据) {
if (null != 数据) {
Uri selectedImage = 数据.getData();
String[] filePathColumn = {MediaStore.Images.Media.DATA};
Cursor cursor = this.getContentResolver().query(selectedImage,filePathColumn, null, null, null);
cursor.moveToFirst();
int columnIndex = cursor.getColumnIndex(filePathColumn[0]);
String picturePath = cursor.getString(columnIndex);
cursor.close();
return picturePath;
}
return "";
}
public void 选择文件(int 请求码) {
Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
intent.setType("*/*");
intent.addCategory(Intent.CATEGORY_OPENABLE);
this.startActivityForResult(intent, 请求码);
}
public String 解析文件地址(android.content.Intent 数据) {
if (null != 数据) {
return FileChooseUtil.getInstance(this).getChooseFileResultPath(数据.getData());
}
return "";
}
static class FileChooseUtil {

private Context context;
private static FileChooseUtil util = null;

private FileChooseUtil(Context context) {
this.context = context;
}

public static FileChooseUtil getInstance(Context context) {
if (util == null) {
util = new FileChooseUtil(context);
}
return util;
}







public String getChooseFileResultPath(Uri uri) {
String chooseFilePath = null;
if ("file".equalsIgnoreCase(uri.getScheme())) {//使用第三方应用打开
chooseFilePath = uri.getPath();
Toast.makeText(context, chooseFilePath, Toast.LENGTH_SHORT).show();
return chooseFilePath;
}
if (Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT) {//4.4以后
chooseFilePath = getPath(context, uri);
}
else {//4.4以下下系统调用方法
chooseFilePath = getRealPathFromURI(uri);
}
return chooseFilePath;
}

private String getRealPathFromURI(Uri contentUri) {
String res = null;
String[] proj = {MediaStore.Images.Media.DATA};
Cursor cursor = context.getContentResolver().query(contentUri, proj, null, null, null);
if (null != cursor && cursor.moveToFirst()) {
int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
res = cursor.getString(column_index);
cursor.close();
}
return res;
}




private String getPath(final Context context, final Uri uri) {

final boolean isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;

// DocumentProvider
if (isKitKat && DocumentsContract.isDocumentUri(context, uri)) {
// ExternalStorageProvider
if (isExternalStorageDocument(uri)) {
final String docId = DocumentsContract.getDocumentId(uri);
final String[] split = docId.split(":");
final String type = split[0];

if ("primary".equalsIgnoreCase(type)) {
return Environment.getExternalStorageDirectory() + "/" + split[1];

}
}
else if (isDownloadsDocument(uri)) {
final String id = DocumentsContract.getDocumentId(uri);
final Uri contentUri = ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), Long.valueOf(id));
return getDataColumn(context, contentUri, null, null);

}
else if (isMediaDocument(uri)) {
final String docId = DocumentsContract.getDocumentId(uri);
final String[] split = docId.split(":");
final String type = split[0];

Uri contentUri = null;
if ("image".equals(type)) {
contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

}
else if ("video".equals(type)) {
contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;

}
else if ("audio".equals(type)) {
contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

}

final String selection = "_id=?";
final String[] selectionArgs = new String[]{split[1]};

return getDataColumn(context, contentUri, selection, selectionArgs);

}

}
else if ("content".equalsIgnoreCase(uri.getScheme())) {
return getDataColumn(context, uri, null, null);

}
else if ("file".equalsIgnoreCase(uri.getScheme())) {
uri.getPath();

}
return null;
}

private String getDataColumn(Context context, Uri uri, String selection, String[] selectionArgs) {
Cursor cursor = null;
final String column = "_data";
final String[] projection = {column};
try {
cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs,
null);
if (cursor != null && cursor.moveToFirst()) {
final int column_index = cursor.getColumnIndexOrThrow(column);
return cursor.getString(column_index);
}
}
finally {
if (cursor != null)
cursor.close();
}
return null;
}





private boolean isExternalStorageDocument(Uri uri) {
return "com.android.externalstorage.documents".equals(uri.getAuthority());
}





private boolean isDownloadsDocument(Uri uri) {
return "com.android.providers.downloads.documents".equals(uri.getAuthority());
}





private boolean isMediaDocument(Uri uri) {
return "com.android.providers.media.documents".equals(uri.getAuthority());
}
}
//打开指定Uri链接
public boolean 打开Uri(String uri文本) {
try {
android.net.Uri uri = android.net.Uri.parse(uri文本);
android.content.Intent intent = new android.content.Intent("android.intent.action.VIEW", uri);
this.startActivity(intent);
return true;
} catch (Exception e) {
return false;
}
}

//打开QQ进行临时会话，第一个参数为当前窗口环境，第二个参数为要会话的QQ号，注意:这里的QQ号码不能为自己的QQ号码
public boolean 打开QQ聊天(String QQ号码) {
try {
android.net.Uri uri = android.net.Uri.parse("mqqwpa://im/chat?chat_type=wpa&uin=" + QQ号码 + "&version=1");
android.content.Intent intent = new android.content.Intent("android.intent.action.VIEW", uri);
this.startActivity(intent);
return true;
} catch (Exception e) {
return false;
}
}

//打开QQ进行临时会话，第一个参数为当前窗口环境，第二个参数为QQ群号码
public boolean 打开QQ加群(安卓窗口 窗口, String 群号) {
try {
android.content.Intent intent = new android.content.Intent("android.intent.action.VIEW",
android.net.Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=" + 群号 + "&card_type=group&source=qrcode"));
窗口.startActivity(intent);
return true;
} catch (Exception e) {
return false;
}
}

//打开系统分享，分享图片与文本
public void 一键分享(String 内容, String 图片路径) {
android.content.Intent intent = new android.content.Intent("android.intent.action.SEND");
if (图片路径 == null) {
intent.setType("text/plain");
} else {
intent.setType("image/*");
intent.putExtra("android.intent.extra.STREAM", android.net.Uri.fromFile(new java.io.File(图片路径)));
}
intent.putExtra("android.intent.extra.SUBJECT", "分享到");
intent.putExtra("android.intent.extra.TEXT", 内容);
intent.setFlags(启动信息.FLAG_ACTIVITY_NEW_TASK);
this.startActivity(启动信息.createChooser(intent, "分享到"));
}
public void 打开严格模式() {
StrictMode.ThreadPolicy policy = new
StrictMode.ThreadPolicy.Builder().permitAll().build();
StrictMode.setThreadPolicy(policy);
}

public void 启用全屏模式() {
if (设备信息.安卓版本号 >= 19) {
this.getWindow().getDecorView().setSystemUiVisibility(
android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
| android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
| android.view.View.SYSTEM_UI_FLAG_FULLSCREEN | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
}
}

public void 显示到异形屏区域() {
if (设备信息.安卓版本号 >= 28) {
android.view.WindowManager.LayoutParams lp = this.getWindow().getAttributes();
lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
this.getWindow().setAttributes(lp);
}
}
}
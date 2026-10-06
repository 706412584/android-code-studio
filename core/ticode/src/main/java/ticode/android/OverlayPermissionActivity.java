package ticode.android;

import android.content.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import android.util.*;
import android.graphics.*;

import static ticode.android.AndroidThread.延时;
import static ticode.android.流程处理.提交到主线程运行;
import static ticode.android.流程处理.提交到新线程运行;
import static ticode.android.流程处理.结束提交到主线程;
import static ticode.android.流程处理.结束提交到新线程;

public class OverlayPermissionActivity extends Window2 {











public void 即将创建() {
if(mListener != null) mListener.onStart();
toSetting(this);
}

public void 获得返回数据(int 请求码, int 结果码, Intent2 数据) {
提交到新线程运行();
延时(100);
提交到主线程运行(this);
if(请求码 == 1010) {
if (isPermission(OverlayPermissionActivity.this)) {
if(mListener != null) mListener.onSuccess();
} else {
toSetting2(OverlayPermissionActivity.this);
return;
}
} else if(请求码 == 1011) {
if (isPermission(OverlayPermissionActivity.this)) {
if(mListener != null) mListener.onSuccess();
} else if(mListener != null) mListener.onFailed();
}
mListener = null;
关闭窗口();
结束提交到主线程();
结束提交到新线程();
}

public static FPListener mListener;
//public static boolean compatible;

//是否有悬浮窗权限
public static boolean isPermission(android.content.Context context) {
return android.os.Build.VERSION.SDK_INT >= 23 ? android.provider.Settings.canDrawOverlays(context) : true;
}

public static void toSetting(android.content.Context c) {
android.app.Activity activity = (android.app.Activity)c;
android.content.Intent intent = new android.content.Intent();
try {
if ("Xiaomi".equals(android.os.Build.MANUFACTURER)) {
intent = new android.content.Intent("miui.intent.action.APP_PERM_EDITOR");
intent.setPackage("com.miui.securitycenter");
intent.putExtra("extra_pkgname", activity.getPackageName());
} else if (android.text.TextUtils.equals("Meizu", android.os.Build.MANUFACTURER)) {
intent = new android.content.Intent("com.meizu.safe.security.SHOW_APPSEC");
intent.setClassName("com.meizu.safe",
"com.meizu.safe.security.AppSecActivity");
intent.putExtra("packageName", activity.getPackageName());
} else if ("Oppo".equalsIgnoreCase(android.os.Build.MANUFACTURER)) {
intent = new android.content.Intent("android.intent.action.MAIN");
intent.setComponent(new android.content.ComponentName("com.coloros.safecenter",
"com.coloros.safecenter.permission.floatwindow.FloatWindowListActivity"));
} else if (android.os.Build.VERSION.SDK_INT >= 23) {
intent.setAction(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
intent.setData(android.net.Uri.parse("package:" + activity.getPackageName()));
}
activity.startActivityForResult(intent, 1010);
} catch (Exception e) {
if(mListener != null) mListener.onFailed();
android.widget.Toast.makeText(activity, "悬浮窗权限申请失败", android.widget.Toast.LENGTH_SHORT).show();
}
}

public static void toSetting2(android.content.Context c) {
android.app.Activity activity = (android.app.Activity)c;
android.content.Intent intent = new android.content.Intent();
try {
intent.setAction(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
intent.setData(android.net.Uri.parse("package:" + activity.getPackageName()));
activity.startActivityForResult(intent, 1011);
android.widget.Toast.makeText(activity, "请在列表内找到本应用", android.widget.Toast.LENGTH_SHORT).show();
} catch (Exception e) {
if(mListener != null) mListener.onFailed();
android.widget.Toast.makeText(activity, "悬浮窗权限申请失败", android.widget.Toast.LENGTH_SHORT).show();
}
}

public static void requestPermission(android.content.Context context, FPListener listener) {
if (isPermission(context)) {
listener.onAcquired();
return;
}
mListener = listener;
android.content.Intent intent = new android.content.Intent(context, OverlayPermissionActivity.class);
intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
context.startActivity(intent);
}

public interface FPListener {
// 已获取
void onAcquired();
// 开始获取
void onStart();
// 获取成功
void onSuccess();
// 获取失败
void onFailed();
}
}
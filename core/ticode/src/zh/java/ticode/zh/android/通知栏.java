package ticode.zh.android;

import android.content.Context;
import android.content.Intent;
import android.view.*;
import android.app.*;
import android.content.pm.*;
import android.os.*;
import android.content.*;
import android.graphics.drawable.*;

import ticode.zh.jvm.Java类;

public class 通知栏 {
static int 通知栏_重要程度_最低 = 1;
static int 通知栏_重要程度_低 = 2;
static int 通知栏_重要程度_默认 = 3;
static int 通知栏_重要程度_高 = 4;
static int 通知栏_重要程度_最高 = 5;

int ID = 1;
int 图标;
String 标题 = "这是通知的标题";
String 内容 = "这是通知的内容";
String 提示 = "你有一条通知";
int 重要程度 = 通知栏_重要程度_默认;
boolean 自动取消 = true;
String 渠道ID;
String 渠道名称;

private Context mContext;
private static NotificationManager notificationManager;

public 通知栏(Context context) {
mContext = context;
notificationManager = context.getSystemService(NotificationManager.class);
ApplicationInfo appInfo = context.getApplicationInfo();
String appName = appInfo.loadLabel(context.getPackageManager()).toString();
图标 = appInfo.icon;
渠道ID = appName;
渠道名称 = appName;
}

public static 通知栏 创建通知栏(android.content.Context 环境) {
return new 通知栏(环境);
}

public void 单行通知() {
Notification.Builder notificationBuilder = new Notification.Builder(mContext);
if (android.os.Build.VERSION.SDK_INT >= 26) {
NotificationChannel notificationChannel = new NotificationChannel(渠道ID, 渠道名称, 重要程度);
notificationManager.createNotificationChannel(notificationChannel);
notificationBuilder.setChannelId(渠道ID);
}
Notification notification = notificationBuilder
.setSmallIcon(图标)
.setContentTitle(标题)
.setContentText(内容)
.setTicker(提示)
.setPriority(重要程度 - 3)
.setWhen(System.currentTimeMillis())
.build();
notificationManager.notify(ID, notification);
}

public void 多行通知() {
Notification.Builder notificationBuilder = new Notification.Builder(mContext);
if (android.os.Build.VERSION.SDK_INT >= 26) {
NotificationChannel notificationChannel = new NotificationChannel(渠道ID, 渠道名称, 重要程度);
notificationManager.createNotificationChannel(notificationChannel);
notificationBuilder.setChannelId(渠道ID);
}
Notification notification = notificationBuilder
.setSmallIcon(图标)
.setContentTitle(标题)
.setContentText(内容)
.setStyle(new Notification.BigTextStyle()
.bigText(内容))
.setTicker(提示)
.setPriority(重要程度 - 3)
.setWhen(System.currentTimeMillis())
.build();
notificationManager.notify(ID, notification);
}

public void 跳转通知(java.lang.Class 欲跳转窗口类, int 请求码, int 标志) {
Notification.Builder notificationBuilder = new Notification.Builder(mContext);
if (android.os.Build.VERSION.SDK_INT >= 26) {
NotificationChannel notificationChannel = new NotificationChannel(渠道ID, 渠道名称, 重要程度);
notificationManager.createNotificationChannel(notificationChannel);
notificationBuilder.setChannelId(渠道ID);
}
Intent intent = new Intent(mContext, 欲跳转窗口类);
PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 请求码, intent, 标志);
Notification notification = notificationBuilder
.setSmallIcon(图标)
.setContentTitle(标题)
.setContentText(内容)
.setContentIntent(pendingIntent)
.setTicker(提示)
.setPriority(重要程度 - 3)
.setAutoCancel(自动取消)
.setWhen(System.currentTimeMillis())
.build();
notificationManager.notify(ID, notification);
}

public void 关闭通知() {
notificationManager.cancel(ID);
}

public static void 关闭所有通知栏() {
notificationManager.cancelAll();
}
}
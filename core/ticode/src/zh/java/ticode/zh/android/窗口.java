package ticode.zh.android;

import android.app.Application;
import java.lang.reflect.Method;
import android.content.Context;
import android.net.Uri;
import android.widget.Toast;
import android.provider.MediaStore;
import android.database.Cursor;
import android.provider.DocumentsContract;
import android.os.Environment;
import android.content.ContentUris;
import android.view.*;
import android.app.*;
import android.content.Intent;
import android.os.Build;
import android.os.*;
import android.content.*;
import android.view.KeyEvent;
import android.content.res.Configuration;
import android.content.Intent;
import android.os.IBinder;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.*;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.drawable.*;

import ticode.zh.jvm.Java类;
import ticode.zh.jvm.文件;

public class 窗口 extends android.app.Activity {
private 可视化组件 root;

@Override
protected void onCreate(Bundle savedBundleInstance) {
即将创建();
super.onCreate(savedBundleInstance);
onInit();
创建完毕();
}

public void onInit() { }

protected void setLayout(可视化组件 root) {
this.root = root;
setContentView(root.getView());
}

@Override
protected void onStart() {
super.onStart();
被启动();
}

@Override
protected void onRestart() {
super.onRestart();
被重新启动();
}

@Override
protected void onStop() {
super.onStop();
被停止();
}

@Override
protected void onPause() {
super.onPause();
被暂停();
}

@Override
protected void onResume() {
super.onResume();
被恢复();
}

@Override
protected void onDestroy() {
super.onDestroy();
被销毁();
}

@Override
public boolean onCreateOptionsMenu(android.view.Menu menu) {
菜单被创建(menu);
return super.onCreateOptionsMenu(menu);
}

@Override
public boolean onOptionsItemSelected(android.view.MenuItem item) {
if (item.getItemId() == android.R.id.home) {
标题栏返回键被单击();
} else {
菜单项被选中(item);
}
return super.onOptionsItemSelected(item);
}

@Override
protected void onActivityResult(int requestCode, int resultCode, Intent data) {
super.onActivityResult(requestCode, resultCode, data);
获得返回数据(requestCode, resultCode, data);
}

@Override
public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
super.onRequestPermissionsResult(requestCode, permissions, grantResults);
申请权限完毕(requestCode, permissions, grantResults);
}

@Override
public boolean onKeyDown(int keyCode, KeyEvent event) {
return 按下某键(keyCode) ? true : super.onKeyDown(keyCode, event);
}

@Override
public void onConfigurationChanged(Configuration newConfig) {
super.onConfigurationChanged(newConfig);
if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
屏幕方向被改变(newConfig.orientation);
} else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
屏幕方向被改变(newConfig.orientation);
}
}




public void 屏幕方向被改变(int 方向) {
}




public void 即将创建() {
}




public void 创建完毕() {
}




public void 被启动() {
}




public void 被重新启动() {
}




public void 被暂停() {
}




public void 被停止() {
}

public void 被恢复() {
}

public void 被销毁() {
}

public void 菜单被创建(菜单 菜单) {
}

public void 菜单项被选中(菜单项 菜单项) {
}

public void 标题栏返回键被单击() {
}




public void 获得返回数据(int 请求码, int 结果码, 启动信息 数据) {
}

public void 申请权限完毕(int 请求码, String[] 权限集, int[] 允许结果) {
}

public void 返回键被按下() {
super.onBackPressed();
}

public boolean 按下某键(int 键代码) {
return false;
}




public void 置窗口布局(可视化组件 布局) {
setLayout(布局);
}




public void 置窗口布局2(组件容器 布局) {
setLayout(布局.取根布局());
布局.创建完毕();
}




public 可视化组件 取窗口布局() {
return this.root;
}
}
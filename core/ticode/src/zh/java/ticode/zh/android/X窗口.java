package ticode.zh.android;

import android.content.Intent;
import android.os.Bundle;

public class X窗口 extends 安卓X窗口 {
private 可视化组件 root;

@Override
protected void onCreate(Bundle savedBundleInstance) {
super.onCreate(savedBundleInstance);
即将创建();
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

public void 菜单被创建(android.view.Menu 菜单) {
}

public void 菜单项被选中(android.view.MenuItem 菜单项) {
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




public void 置窗口布局(可视化组件 布局) {
setLayout(布局);
}




public void 置窗口布局2(组件容器 布局) {
setLayout(布局.取根布局());
布局.布局被加载();
}




public 可视化组件 取窗口布局() {
return this.root;
}
}
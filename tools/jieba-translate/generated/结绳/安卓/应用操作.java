package 结绳.安卓;


public class 应用操作 {

public boolean 应用是否已安装(安卓环境 环境, String 应用包名) {
容错处理();
安卓程序包信息 信息 = 环境.取程序包管理器().取程序包信息(应用包名);
return 信息 != null;
结束容错();
return false;
}

public void 安装应用(安卓环境 环境, String 应用路径) {
容错处理();
Intent intent = new Intent(Intent.ACTION_VIEW);
StrictMode.VmPolicy.Builder builder = new StrictMode.VmPolicy.Builder();
StrictMode.setVmPolicy(builder.build());
Uri data = Uri.fromFile(new File(应用路径));
intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
intent.setDataAndType(data, "application/vnd.android.package-archive");
环境.startActivity(intent);
结束容错();
}

public void 卸载应用(安卓环境 环境, String 应用包名) {
容错处理();
Intent intent = new Intent(Intent.ACTION_DELETE);
intent.setData(Uri.parse("package:" + 应用包名));
环境.startActivity(intent);
结束容错();
}

public boolean 打开应用(安卓环境 环境, String 应用包名) {
容错处理();
启动信息 启动信息1 = 环境.取程序包管理器().取程序启动信息(应用包名);
启动信息1.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
环境.startActivity(启动信息1);
return true;
结束容错();
return false;
}

public void 打开应用信息页(安卓环境 环境, String 应用包名) {
容错处理();
Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
intent.setData(Uri.parse("package:" + 应用包名));
intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
环境.startActivity(intent);
结束容错();
}

}
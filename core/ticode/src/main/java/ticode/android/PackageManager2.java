package ticode.android;


public class PackageManager2 extends android.content.pm.PackageManager {

public static final int 获取标志_窗口信息 = 1;

public static final int 获取标志_功能配置信息 = 16384;

public static final int 获取标志_被禁用组件信息 = 512;

public static final int 获取标志_组id = 256;

public static final int 获取标志_测试器信息 = 16;

public static final int 获取标志_元数据 = 128;

public static final int 获取标志_权限信息 = 4096;

public static final int 获取标志_广播接收器信息 = 2;

public static final int 获取标志_服务信息 = 4;

public static final int 获取标志_动态库路径 = 1024;

public static final int 获取标志_签名数据 = 64;

public static final int 获取标志_签名信息 = 134217728;

public static final int 权限_未获得 = -1;

public static final int 权限_已授予 = 0;

public boolean 等于_op(PackageManager2 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(PackageManager2 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

public PackageInfo2 取程序包信息(String 应用包名, int 获取标志) {
try {
return this.getPackageInfo(应用包名,获取标志);
} catch(android.content.pm.PackageManager.NameNotFoundException e) {
throw new RuntimeException("应用不存在：" + 应用包名);
}
}

public PackageInfo2 取APK包信息(String APK路径, int 获取标志) {
return this.getPackageArchiveInfo(APK路径,获取标志);
}

public PackageInfo2[] 取所有已安装程序包信息(int 获取标志) {
return this.getInstalledPackages(获取标志).toArray(new android.content.pm.PackageInfo[0]);
}

public boolean 允许请求安装程序包() {
return this.canRequestPackageInstalls();
}

public int 检查权限(String 权限名, String 应用包名) {
return this.checkPermission(权限名,应用包名);
}

public DrawableObject 取默认窗口图标() {
return this.getDefaultActivityIcon();
}

public Intent2 取程序启动信息(String 程序包名) {
return this.getLaunchIntentForPackage(程序包名);
}

public AndroidResourceManager 取资源管理器(AppInfo 应用信息) {
try {
return this.getResourcesForApplication(应用信息);
} catch(android.content.pm.PackageManager.NameNotFoundException e) {
throw new RuntimeException(e.getMessage());
}
}

public AndroidResourceManager 取资源管理器2(String 应用包名) {
try {
return this.getResourcesForApplication(应用包名);
} catch(android.content.pm.PackageManager.NameNotFoundException e) {
throw new RuntimeException(e.getMessage());
}
}

}
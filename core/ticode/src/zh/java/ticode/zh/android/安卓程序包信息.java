package ticode.zh.android;


public class 安卓程序包信息 extends android.content.pm.PackageInfo {

public static final int 安装位置_自动 = 0;

public static final int 安装位置_内部 = 1;

public static final int 安装位置_首选外部 = 2;

public static final int 请求权限标志_已授予 = 2;

public boolean 等于_op(安卓程序包信息 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(安卓程序包信息 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

public 安卓窗口信息[] 窗口信息集() {
return (安卓窗口信息[])this.activities;
}

public void 窗口信息集(安卓窗口信息[] 窗口信息集) {
this.activities = 窗口信息集;
}

public 安卓窗口信息[] 广播接收器信息集() {
return (安卓窗口信息[])this.receivers;
}

public void 广播接收器信息集(安卓窗口信息[] 广播接收器信息集) {
this.receivers = 广播接收器信息集;
}

// 返回原生类型：applicationInfo 字段是原生 ApplicationInfo，不是 ticode 壳。
public android.content.pm.ApplicationInfo 应用信息() {
return this.applicationInfo;
}

public void 应用信息(android.content.pm.ApplicationInfo 应用信息) {
this.applicationInfo = 应用信息;
}

public 安卓程序配置信息[] 配置信息集() {
return (安卓程序配置信息[])this.configPreferences;
}

public void 配置信息集(安卓程序配置信息[] 配置信息集) {
this.configPreferences = 配置信息集;
}

public android.content.pm.FeatureGroupInfo[] 功能组信息集() {
return (android.content.pm.FeatureGroupInfo[])this.featureGroups;
}

public void 功能组信息集(android.content.pm.FeatureGroupInfo[] 功能组信息集) {
this.featureGroups = 功能组信息集;
}

public 安卓测试器信息[] 测试器信息集() {
return (安卓测试器信息[])this.instrumentation;
}

public void 测试器信息集(安卓测试器信息[] 测试器信息集) {
this.instrumentation = 测试器信息集;
}

public 安卓程序权限信息[] 权限信息集() {
return (安卓程序权限信息[])this.permissions;
}

public void 权限信息集(安卓程序权限信息[] 权限信息集) {
this.permissions = 权限信息集;
}

public 安卓程序功能信息[] 功能信息集() {
return (安卓程序功能信息[])this.reqFeatures;
}

public void 功能信息集(安卓程序功能信息[] 功能信息集) {
this.reqFeatures = 功能信息集;
}

public 安卓服务信息[] 服务信息集() {
return (安卓服务信息[])this.services;
}

public void 服务信息集(安卓服务信息[] 服务信息集) {
this.services = 服务信息集;
}

public 安卓程序签名数据[] 签名数据集() {
return (安卓程序签名数据[])this.signatures;
}

public void 签名数据集(安卓程序签名数据[] 签名数据集) {
this.signatures = 签名数据集;
}

public android.content.pm.SigningInfo 签名信息() {
return (android.content.pm.SigningInfo)this.signingInfo;
}

public void 签名信息(android.content.pm.SigningInfo 签名信息) {
this.signingInfo = 签名信息;
}

public int 修订码() {
return this.baseRevisionCode;
}

public void 修订码(int 修订码) {
this.baseRevisionCode = 修订码;
}

public long 首次安装时间() {
return this.firstInstallTime;
}

public void 首次安装时间(long 首次安装时间) {
this.firstInstallTime = 首次安装时间;
}

public int[] 组id() {
return this.gids;
}

public void 组id(int[] 组id) {
this.gids = 组id;
}

public int 安装位置() {
return this.installLocation;
}

public void 安装位置(int 安装位置) {
this.installLocation = 安装位置;
}

// isApex 是 API 29 字段，低版本无此字段 → NoSuchFieldError。反射读写。
public boolean 是apex包() {
try {
return (Boolean) android.content.pm.PackageInfo.class.getField("isApex").get(this);
} catch (Throwable t) {
return false;
}
}

public void 是apex包(boolean 是apex包) {
try {
android.content.pm.PackageInfo.class.getField("isApex").set(this, 是apex包);
} catch (Throwable t) {
// 低版本无此字段，忽略
}
}

public long 上次更新时间() {
return this.lastUpdateTime;
}

public void 上次更新时间(long 上次更新时间) {
this.lastUpdateTime = 上次更新时间;
}

public String 应用包名() {
return this.packageName;
}

public void 应用包名(String 应用包名) {
this.packageName = 应用包名;
}

public String[] 请求权限() {
return this.requestedPermissions;
}

public void 请求权限(String[] 请求权限) {
this.requestedPermissions = 请求权限;
}

public int[] 请求权限标志() {
return this.requestedPermissionsFlags;
}

public void 请求权限标志(int[] 请求权限标志) {
this.requestedPermissionsFlags = 请求权限标志;
}

public String 共享用户id() {
return this.sharedUserId;
}

public void 共享用户id(String 共享用户id) {
this.sharedUserId = 共享用户id;
}

public int 共享用户标签资源id() {
return this.sharedUserLabel;
}

public void 共享用户标签资源id(int 共享用户标签资源id) {
this.sharedUserLabel = 共享用户标签资源id;
}

public String[] 拆分名称() {
return this.splitNames;
}

public void 拆分名称(String[] 拆分名称) {
this.splitNames = 拆分名称;
}

public int[] 拆分修订码() {
return this.splitRevisionCodes;
}

public void 拆分修订码(int[] 拆分修订码) {
this.splitRevisionCodes = 拆分修订码;
}

public int 版本号() {
return this.versionCode;
}

public void 版本号(int 版本号) {
this.versionCode = 版本号;
}

public String 版本名称() {
return this.versionName;
}

public void 版本名称(String 版本名称) {
this.versionName = 版本名称;
}

}
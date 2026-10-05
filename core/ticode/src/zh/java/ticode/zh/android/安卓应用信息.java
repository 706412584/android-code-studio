package ticode.zh.android;

import android.os.StrictMode;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import android.provider.Settings;

public class 安卓应用信息 extends android.content.pm.ApplicationInfo {

public static final int 类别_未定义 = -1;

public static final int 类别_游戏 = 0;

public static final int 类别_音频 = 1;

public static final int 类别_视频 = 2;

public static final int 类别_图片 = 3;

public static final int 类别_社交 = 4;

public static final int 类别_新闻 = 5;

public static final int 类别_地图 = 6;

public static final int 类别_工作 = 7;

public static final int 标注_允许备份 = 32768;;

public static final int 标志_允许清除用户数据 = 64;;

public static final int 标志_允许任务修复 = 32;;

public static final int 标志_可调试 = 2;;

public static final int 标志_安装在外部存储 = 262144;;

public static final int 标志_提取动态库 = 268435456;;

public static final int 标志_工厂测试模式 = 16;;

public static final int 标志_仅完整备份 = 67108864;;

public static final int 标志_硬件加速 = 536870912;;

public static final int 标志_有代码 = 4;;

public static final int 标志_已安装 = 8388608;;

public static final int 标志_仅安装了数据 = 16777216;;

public static final int 标志_是游戏 = 33554432;;

public static final int 标志_系统恢复后杀死自身 = 65536;;

public static final int 标志_更大内存 = 1048576;;

public static final int 标志_可被其他进程加载 = -2147483648;;

public static final int 标志_持久 = 8;;

public static final int 标志_可调整大小 = 4096;;

public static final int 标志_可恢复高版本数据 = 131072;;

public static final int 标志_已停止 = 2097152;;

public static final int 标志_支持大屏 = 2048;;

public static final int 标志_支持普通屏幕 = 1024;;

public static final int 标志_支持从右到左模式 = 4194304;;

public static final int 标志_支持适应屏幕密度 = 8192;;

public static final int 标志_支持小屏 = 512;;

public static final int 标志_支持超大屏 = 524288;;

public static final int 标志_暂停 = 1073741824;;

public static final int 标志_系统 = 1;;

public static final int 标志_仅测试 = 256;;

public static final int 标志_更新到系统应用 = 128;;

public static final int 标志_使用明文请求 = 134217728;;

public static final int 标志_安全模式运行 = 16384;;

public boolean 等于_op(安卓应用信息 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(安卓应用信息 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

public String 备份器类名() {
return this.backupAgentName;
}

public void 备份器类名(String 备份器类名) {
this.backupAgentName = 备份器类名;
}

public int 类别() {
return this.category;
}

public void 类别(int 类别) {
this.category = 类别;
}

public String 全局应用类名() {
return this.className;
}

public void 全局应用类名(String 全局应用类名) {
this.className = 全局应用类名;
}

public String 数据目录() {
return this.dataDir;
}

public void 数据目录(String 数据目录) {
this.dataDir = 数据目录;
}

public int 描述资源id() {
return this.descriptionRes;
}

public void 描述资源id(int 描述资源id) {
this.descriptionRes = 描述资源id;
}

public String 设备保护数据目录() {
return this.deviceProtectedDataDir;
}

public void 设备保护数据目录(String 设备保护数据目录) {
this.deviceProtectedDataDir = 设备保护数据目录;
}

public boolean 启用() {
return this.enabled;
}

public void 启用(boolean 启用) {
this.enabled = 启用;
}

public int 标志() {
return this.flags;
}

public void 标志(int 标志) {
this.flags = 标志;
}

public String 管理空间窗口类名() {
return this.manageSpaceActivityName;
}

public void 管理空间窗口类名(String 管理空间窗口类名) {
this.manageSpaceActivityName = 管理空间窗口类名;
}

public int 最低支持SDK版本() {
return this.minSdkVersion;
}

public void 最低支持SDK版本(int 最低支持SDK版本) {
this.minSdkVersion = 最低支持SDK版本;
}

public String 动态库目录() {
return this.nativeLibraryDir;
}

public void 动态库目录(String 动态库目录) {
this.nativeLibraryDir = 动态库目录;
}

public String 访问所需权限() {
return this.permission;
}

public void 访问所需权限(String 访问所需权限) {
this.permission = 访问所需权限;
}

public String 进程名称() {
return this.processName;
}

public void 进程名称(String 进程名称) {
this.processName = 进程名称;
}

public String 公共源目录() {
return this.publicSourceDir;
}

public void 公共源目录(String 公共源目录) {
this.publicSourceDir = 公共源目录;
}

public String[] 动态库路径集() {
return this.sharedLibraryFiles;
}

public void 动态库路径集(String[] 动态库路径集) {
this.sharedLibraryFiles = 动态库路径集;
}

public String 安装包路径() {
return this.sourceDir;
}

public void 安装包路径(String 安装包路径) {
this.sourceDir = 安装包路径;
}

public int 目标SDK版本() {
return this.targetSdkVersion;
}

public void 目标SDK版本(int 目标SDK版本) {
this.targetSdkVersion = 目标SDK版本;
}

public int 主题资源id() {
return this.theme;
}

public void 主题资源id(int 主题资源id) {
this.theme = 主题资源id;
}

public String 取类别标题(安卓环境 环境, int 类别) {
return this.getCategoryTitle(环境,类别).toString();
}

public String 获取描述(安卓程序包管理器 管理器) {
return this.loadDescription(管理器).toString();
}

}
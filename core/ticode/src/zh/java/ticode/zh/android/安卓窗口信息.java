package ticode.zh.android;


public class 安卓窗口信息 extends android.content.pm.ActivityInfo {

public static final int 颜色模式_默认 = 0;

public static final int 颜色模式_宽色域 = 1;

public static final int 颜色模式_HDR = 2;

public static final int 配置_颜色模式 = 16384;

public static final int 配置_密度 = 4096;

public static final int 配置_字体缩放 = 1073741824;

public static final int 配置_键盘 = 16;

public static final int 配置_隐藏键盘 = 32;

public static final int 配置_布局方向 = 8192;

public static final int 配置_语言环境 = 4;

public static final int 配置_MCC = 1;

public static final int 配置_MNC = 2;

public static final int 配置_导航 = 64;

public static final int 配置_屏幕方向 = 128;

public static final int 配置_屏幕布局 = 256;

public static final int 配置_屏幕尺寸 = 1024;

public static final int 配置_最小屏幕尺寸 = 2048;

public static final int 配置_触摸屏 = 8;

public static final int 配置_UI模式 = 512;

public static final int 文档启动模式_空模式 = 0;

public static final int 文档启动模式_进入现有 = 1;

public static final int 文档启动模式_总是 = 2;

public static final int 文档启动模式_从不 = 3;

public static final int 标志_允许任务修复 = 64;

public static final int 标志_总是保留任务状态 = 8;

public static final int 标志_从最近任务中自动删除 = 8192;

public static final int 标志_从桌面启动清除任务栈 = 4;

public static final int 标志_启用VR模式 = 32768;

public static final int 标志_从最近任务排除 = 32;

public static final int 标志_系统对话关闭时关闭窗口 = 256;

public static final int 标志_从桌面重新启动时关闭窗口 = 2;

public static final int 标志_硬件加速 = 512;

public static final int 标志_沉浸式 = 2048;

public static final int 标志_多进程 = 1;

public static final int 标志_无历史 = 128;

public static final int 标志_首选最小后处理模式 = 33554432;

public static final int 标志_放弃任务身份 = 4096;

public static final int 标志_上一个窗口暂停时显示 = 16384;

public static final int 标志_单用户 = 1073741824;

public static final int 标志_不保存状态 = 16;

public static final int 启动模式_普通 = 0;

public static final int 启动模式_单例 = 3;

public static final int 启动模式_单任务 = 2;

public static final int 启动模式_栈顶单例 = 1;

public static final int 持久化模式_重启 = 2;

public static final int 持久化模式_不持久化 = 1;

public static final int 持久化模式_默认 = 0;

public static final int 屏幕方向配置_与上个窗口相同 = 3;

public static final int 屏幕方向配置_传感器决定_4方向 = 10;

public static final int 屏幕方向配置_用户决定_4方向 = 13;

public static final int 屏幕方向配置_横向 = 0;

public static final int 屏幕方向配置_锁定 = 14;

public static final int 屏幕方向配置_忽略传感器 = 5;

public static final int 屏幕方向配置_纵向 = 1;

public static final int 屏幕方向配置_横向_反转 = 8;

public static final int 屏幕方向配置_纵向_反转 = 9;

public static final int 屏幕方向配置_传感器决定 = 4;

public static final int 屏幕方向配置_基于传感器的横向 = 6;

public static final int 屏幕方向配置_基于传感器的纵向 = 7;

public static final int 屏幕方向配置_未指定 = -1;

public static final int 屏幕方向配置_用户决定 = 2;

public static final int 屏幕方向配置_基于用户决定的横向 = 11;

public static final int 屏幕方向配置_基于用户决定的纵向 = 12;

public boolean 等于_op(安卓窗口信息 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(安卓窗口信息 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

public int 颜色模式() {
return this.colorMode;
}

public void 颜色模式(int 颜色模式) {
this.colorMode = 颜色模式;
}

public int 可处理配置() {
return this.configChanges;
}

public void 可处理配置(int 可处理配置) {
this.configChanges = 可处理配置;
}

public int 文档启动模式() {
return this.documentLaunchMode;
}

public void 文档启动模式(int 文档启动模式) {
this.documentLaunchMode = 文档启动模式;
}

public int 标志() {
return this.flags;
}

public void 标志(int 标志) {
this.flags = 标志;
}

public int 启动模式() {
return this.launchMode;
}

public void 启动模式(int 启动模式) {
this.launchMode = 启动模式;
}

public int 最大任务数() {
return this.maxRecents;
}

public void 最大任务数(int 最大任务数) {
this.maxRecents = 最大任务数;
}

public String 父窗口名称() {
return this.parentActivityName;
}

public void 父窗口名称(String 父窗口名称) {
this.parentActivityName = 父窗口名称;
}

public String 访问所需权限() {
return this.permission;
}

public void 访问所需权限(String 访问所需权限) {
this.permission = 访问所需权限;
}

public int 持久化模式() {
return this.persistableMode;
}

public void 持久化模式(int 持久化模式) {
this.persistableMode = 持久化模式;
}

public int 屏幕方向配置() {
return this.screenOrientation;
}

public void 屏幕方向配置(int 屏幕方向配置) {
this.screenOrientation = 屏幕方向配置;
}

public int 软键盘输入模式() {
return this.softInputMode;
}

public void 软键盘输入模式(int 软键盘输入模式) {
this.softInputMode = 软键盘输入模式;
}

public String 窗口名称() {
return this.targetActivity;
}

public void 窗口名称(String 窗口名称) {
this.targetActivity = 窗口名称;
}

public int 主题资源id() {
return this.theme;
}

public void 主题资源id(int 主题资源id) {
this.theme = 主题资源id;
}

public android.content.pm.ActivityInfo.WindowLayout 布局信息() {
return (android.content.pm.ActivityInfo.WindowLayout)this.windowLayout;
}

public void 布局信息(android.content.pm.ActivityInfo.WindowLayout 布局信息) {
this.windowLayout = 布局信息;
}

public int 横幅资源id() {
return this.banner;
}
public int 图标资源id() {
return this.icon;
}
public int 标签资源id() {
return this.labelRes;
}
public int 徽标资源id() {
return this.logo;
}
public android.os.Bundle 元数据() {
return (android.os.Bundle)this.metaData;
}
public String 名称() {
return this.name;
}
public String 应用包名() {
return this.packageName;
}
public String 获取标签(安卓程序包管理器 管理器) {
return this.loadLabel(管理器).toString();
}
public android.graphics.drawable.Drawable 获取图标(安卓程序包管理器 管理器) {
// loadIcon 是框架原生实例，不是 可绘制对象 子类，强转必 ClassCastException。返回原生类型。
return this.loadIcon(管理器);
}
public android.graphics.drawable.Drawable 获取横幅(安卓程序包管理器 管理器) {
// loadBanner 是框架原生实例，不是 可绘制对象 子类，强转必 ClassCastException。返回原生类型。
return this.loadBanner(管理器);
}
public android.graphics.drawable.Drawable 获取徽标(安卓程序包管理器 管理器) {
// loadLogo 是框架原生实例，不是 可绘制对象 子类，强转必 ClassCastException。返回原生类型。
return this.loadLogo(管理器);
}
}
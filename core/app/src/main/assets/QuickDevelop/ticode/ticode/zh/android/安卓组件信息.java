package ticode.zh.android;


public class 安卓组件信息 extends android.content.pm.ComponentInfo {

public boolean 等于_op(安卓组件信息 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(安卓组件信息 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

// 返回原生类型：applicationInfo 字段是原生 ApplicationInfo，不是 ticode 壳。
public android.content.pm.ApplicationInfo 应用信息() {
return this.applicationInfo;
}

public void 应用信息(android.content.pm.ApplicationInfo 应用信息) {
this.applicationInfo = 应用信息;
}

public int 描述资源id() {
return this.descriptionRes;
}

public void 描述资源id(int 描述资源id) {
this.descriptionRes = 描述资源id;
}

public boolean 可实例化() {
return this.enabled;
}

public void 可实例化(boolean 可实例化) {
this.enabled = 可实例化;
}

public boolean 可被外部调用() {
return this.exported;
}

public void 可被外部调用(boolean 可被外部调用) {
this.exported = 可被外部调用;
}

public String 进程名称() {
return this.processName;
}

public void 进程名称(String 进程名称) {
this.processName = 进程名称;
}

public String 拆分名称() {
return this.splitName;
}

public void 拆分名称(String 拆分名称) {
this.splitName = 拆分名称;
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
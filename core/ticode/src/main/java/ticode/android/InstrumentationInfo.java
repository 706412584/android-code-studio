package ticode.android;


public class InstrumentationInfo extends android.content.pm.InstrumentationInfo {

public boolean 等于_op(InstrumentationInfo 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(InstrumentationInfo 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

public String 数据目录() {
return this.dataDir;
}

public void 数据目录(String 数据目录) {
this.dataDir = 数据目录;
}

public boolean 功能测试() {
return this.functionalTest;
}

public void 功能测试(boolean 功能测试) {
this.functionalTest = 功能测试;
}

public boolean 处理分析() {
return this.handleProfiling;
}

public void 处理分析(boolean 处理分析) {
this.handleProfiling = 处理分析;
}

public String 公共源目录() {
return this.publicSourceDir;
}

public void 公共源目录(String 公共源目录) {
this.publicSourceDir = 公共源目录;
}

public String 安装包路径() {
return this.sourceDir;
}

public void 安装包路径(String 安装包路径) {
this.sourceDir = 安装包路径;
}

public String[] 拆分公共源目录() {
return this.splitPublicSourceDirs;
}

public void 拆分公共源目录(String[] 拆分公共源目录) {
this.splitPublicSourceDirs = 拆分公共源目录;
}

public String[] 拆分安装包路径() {
return this.splitSourceDirs;
}

public void 拆分安装包路径(String[] 拆分安装包路径) {
this.splitSourceDirs = 拆分安装包路径;
}

public String[] 拆分名称() {
return this.splitNames;
}

public void 拆分名称(String[] 拆分名称) {
this.splitNames = 拆分名称;
}

public String 目标包名() {
return this.targetPackage;
}

public void 目标包名(String 目标包名) {
this.targetPackage = 目标包名;
}

public String 目标进程名() {
return this.targetProcesses;
}

public void 目标进程名(String 目标进程名) {
this.targetProcesses = 目标进程名;
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
public Bundle2 元数据() {
return this.metaData;
}
public String 名称() {
return this.name;
}
public String 应用包名() {
return this.packageName;
}
public String 获取标签(PackageManager2 管理器) {
return this.loadLabel(管理器).toString();
}
public DrawableObject 获取图标(PackageManager2 管理器) {
return this.loadIcon(管理器);
}
public DrawableObject 获取横幅(PackageManager2 管理器) {
return this.loadBanner(管理器);
}
public DrawableObject 获取徽标(PackageManager2 管理器) {
return this.loadLogo(管理器);
}
}
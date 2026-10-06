package ticode.android;


public class PackagePermissionInfo extends android.content.pm.PermissionInfo {

public static final int 标志_扣费 = 1;;

public static final int 标志_硬件限制 = 4;;

public static final int 标志_不可变限制 = 16;;

public static final int 标志_已安装 = 1073741824;;

public static final int 标志_软件限制 = 8;;

public boolean 等于_op(PackagePermissionInfo 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(PackagePermissionInfo 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

public int 描述资源id() {
return this.descriptionRes;
}

public void 描述资源id(int 描述资源id) {
this.descriptionRes = 描述资源id;
}

public int 标志() {
return this.flags;
}

public void 标志(int 标志) {
this.flags = 标志;
}

public String 组名() {
return this.group;
}

public void 组名(String 组名) {
this.group = 组名;
}

public String 获取描述(PackageManager2 管理器) {
return this.loadDescription(管理器).toString();
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
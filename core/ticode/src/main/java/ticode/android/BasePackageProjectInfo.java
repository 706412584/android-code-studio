package ticode.android;


public class BasePackageProjectInfo extends android.content.pm.PackageItemInfo {

public boolean 等于_op(BasePackageProjectInfo 另一个) {
if (this == null) {
return 另一个 == null;
}
return this.equals(另一个);
}

public boolean 不等于_op(BasePackageProjectInfo 另一个) {
if (this == null) {
return 另一个 != null;
}
return !this.equals(另一个);
}

public int 横幅资源id() {
return this.banner;
}

public void 横幅资源id(int 横幅资源id) {
this.banner = 横幅资源id;
}

public int 图标资源id() {
return this.icon;
}

public void 图标资源id(int 图标资源id) {
this.icon = 图标资源id;
}

public int 标签资源id() {
return this.labelRes;
}

public void 标签资源id(int 标签资源id) {
this.labelRes = 标签资源id;
}

public int 徽标资源id() {
return this.logo;
}

public void 徽标资源id(int 徽标资源id) {
this.logo = 徽标资源id;
}

public Bundle2 元数据() {
return this.metaData;
}

public void 元数据(Bundle2 元数据) {
this.metaData = 元数据;
}

public String 名称() {
return this.name;
}

public void 名称(String 名称) {
this.name = 名称;
}

public String 应用包名() {
return this.packageName;
}

public void 应用包名(String 应用包名) {
this.packageName = 应用包名;
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
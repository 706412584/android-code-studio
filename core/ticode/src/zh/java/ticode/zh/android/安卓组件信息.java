package ticode.zh.android;


public abstract class 安卓组件信息 extends android.content.pm.ComponentInfo {

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

public 安卓应用信息 应用信息() {
return (安卓应用信息)this.applicationInfo;
}

public void 应用信息(安卓应用信息 应用信息) {
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

}